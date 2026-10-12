package cv.sitech.application.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.BulkTransactionCallbackUseCase;
import cv.sitech.application.port.in.BulkTransactionUpdate;
import cv.sitech.application.port.out.DisbursementItemRepository;
import cv.sitech.application.port.out.DisbursementRepository;
import cv.sitech.application.port.out.SdkOutboundGateway;
import cv.sitech.application.port.out.SdkOutboundGateway.BulkItem;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import cv.sitech.domain.disbursement.DisbursementMode;
import cv.sitech.domain.disbursement.DisbursementState;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * BULK disbursements through the payer SDK's {@code /bulkTransactions}, the Mojaloop G2P sequence:
 * <ol>
 * <li>validate: {@code POST /bulkTransactions}; the SDK looks each payee up and reports
 * WAITING_FOR_PARTY_ACCEPTANCE, which makes the items VALID or INVALID;</li>
 * <li>approve: the parties are accepted; the SDK sends one {@code POST /bulkQuotes} per payee DFSP and reports
 * WAITING_FOR_QUOTE_ACCEPTANCE; each quoted item becomes an OUTBOUND transfer with its funds reserved, and
 * the quotes it could reserve are accepted;</li>
 * <li>the SDK sends one {@code POST /bulkTransfers} per payee DFSP (bulk-api-adapter) and reports each
 * transfer's outcome, which commits or releases its reservation.</li>
 * </ol>
 * Items are updated from their transfers by {@link DisbursementService#sync()}, as in INDIVIDUAL mode; a
 * RESERVED transfer whose outcome never arrives is resolved by the expiry job.
 */
@ApplicationScoped
public class BulkDisbursementService implements BulkTransactionCallbackUseCase {

  private static final Logger LOG = Logger.getLogger(BulkDisbursementService.class);
  private static final FspiopError PARTY_NOT_FOUND = new FspiopError("3204", "Party not found");
  private static final FspiopError NO_ANSWER = new FspiopError("2004", "No answer from the SDK");

  private final DisbursementRepository disbursementRepository;
  private final DisbursementItemRepository itemRepository;
  private final TransferRepository transferRepository;
  private final PayerService payerService;
  private final SdkOutboundGateway sdk;
  private final SimulatorConfig config;
  /** acceptances are sent from here, not from inside the SDK's callback request */
  private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

  public BulkDisbursementService(DisbursementRepository disbursementRepository,
      DisbursementItemRepository itemRepository, TransferRepository transferRepository, PayerService payerService,
      SdkOutboundGateway sdk, SimulatorConfig config) {
    this.disbursementRepository = disbursementRepository;
    this.itemRepository = itemRepository;
    this.transferRepository = transferRepository;
    this.payerService = payerService;
    this.sdk = sdk;
    this.config = config;
  }

  @PreDestroy
  void close() {
    async.shutdown();
  }

  // --- called by DisbursementService ---

  /** sends the batch to the payer's SDK, which starts looking the payees up */
  void start(Disbursement d) {
    List<BulkItem> items = QuarkusTransaction.joiningExisting()
        .call(() -> itemRepository.findByDisbursementId(d.getDisbursementId())).stream()
        .map(i -> new BulkItem(i.getHomeTransactionId(), i.getPayeeIdType(), i.getPayeeIdValue(), i.getAmount(),
            d.getCurrency(), i.getNote() != null ? i.getNote() : d.getReference()))
        .toList();
    sdk.startBulkTransaction(d.getPayerFspId(), d.getDisbursementId(), d.getPayerIdType(), d.getPayerIdValue(),
        items, Instant.now().plus(config.disbursement().bulkExpiry()));
  }

  /** accepts the payees of the QUOTING items and rejects the invalid ones the SDK knows */
  void acceptParties(Disbursement d) {
    Map<String, Boolean> decisions = new LinkedHashMap<>();
    for (DisbursementItem item : QuarkusTransaction.joiningExisting()
        .call(() -> itemRepository.findByDisbursementId(d.getDisbursementId()))) {
      if (item.getTransferId() != null) {
        decisions.put(item.getTransferId(), item.getState() == DisbursementItemState.QUOTING);
      }
    }
    sdk.acceptBulkParties(d.getPayerFspId(), d.getDisbursementId(), decisions);
  }

  /** a cancelled VALIDATED batch: the SDK is waiting for party acceptance, so reject every payee */
  void rejectParties(Disbursement d) {
    Map<String, Boolean> decisions = QuarkusTransaction.joiningExisting()
        .call(() -> itemRepository.findByDisbursementId(d.getDisbursementId())).stream()
        .filter(i -> i.getTransferId() != null)
        .collect(Collectors.toMap(DisbursementItem::getTransferId, i -> false, (a, b) -> a, LinkedHashMap::new));
    try {
      sdk.acceptBulkParties(d.getPayerFspId(), d.getDisbursementId(), decisions);
    } catch (RuntimeException e) {
      LOG.warnf("SDK did not take the rejection of bulk transaction %s: %s", d.getDisbursementId(), e.getMessage());
    }
  }

  // --- SDK callbacks ---

  @Override
  public void bulkTransactionUpdated(String fspId, BulkTransactionUpdate update) {
    Optional<Disbursement> found = QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByDisbursementId(update.bulkTransactionId()))
        .filter(d -> fspId.equals(d.getPayerFspId()) && d.getMode() == DisbursementMode.BULK);
    if (found.isEmpty()) {
      LOG.warnf("%s reported unknown bulk transaction %s", fspId, update.bulkTransactionId());
      return;
    }
    Disbursement d = found.get();
    LOG.infof("Bulk transaction %s: %s, %d items", d.getDisbursementId(), update.currentState(),
        update.items().size());
    switch (update.currentState()) {
      case BulkTransactionUpdate.WAITING_FOR_PARTY_ACCEPTANCE -> partiesFound(d, update);
      case BulkTransactionUpdate.WAITING_FOR_QUOTE_ACCEPTANCE -> quotesReceived(d, update);
      default -> outcomes(d, update);
    }
  }

  /** the lookups are done: each item is VALID with its payee, or INVALID with the lookup's error */
  private void partiesFound(Disbursement d, BulkTransactionUpdate update) {
    Map<String, BulkTransactionUpdate.Item> results = byHomeTransactionId(update);
    QuarkusTransaction.joiningExisting().run(() -> {
      Disbursement current = disbursementRepository.findByDisbursementIdForUpdate(d.getDisbursementId())
          .orElseThrow();
      if (current.getState() != DisbursementState.VALIDATING) {
        LOG.warnf("Ignoring party results for bulk transaction %s, which is %s", d.getDisbursementId(),
            current.getState());
        return;
      }
      for (DisbursementItem item : itemRepository.lockByDisbursementId(d.getDisbursementId(),
          List.of(DisbursementItemState.PENDING))) {
        BulkTransactionUpdate.Item result = results.get(item.getHomeTransactionId());
        if (result != null) {
          item.setTransferId(result.transferId());
        }
        if (result != null && result.error() == null && result.payee() != null && result.payee().fspId() != null) {
          item.setState(DisbursementItemState.VALID);
          item.setPayeeFspId(result.payee().fspId());
          item.setPayeeName(result.payee().name());
        } else {
          FspiopError error = result != null && result.error() != null ? result.error()
              : update.error() != null ? update.error() : PARTY_NOT_FOUND;
          item.setState(DisbursementItemState.INVALID);
          item.setErrorCode(error.code());
          item.setErrorDescription(error.description());
        }
        item.setUpdatedAt(Instant.now());
        itemRepository.save(item);
      }
      current.setState(DisbursementState.VALIDATED);
      current.setUpdatedAt(Instant.now());
      disbursementRepository.save(current);
    });
  }

  /**
   * The bulk quotes are in: each QUOTING item with a quote becomes a RESERVED transfer and its quote is
   * accepted; an item that can't be reserved (e.g. 4001), was cancelled, or got no quote is rejected.
   */
  private void quotesReceived(Disbursement d, BulkTransactionUpdate update) {
    Map<String, Boolean> decisions = new LinkedHashMap<>();
    for (BulkTransactionUpdate.Item result : update.items()) {
      if (result.transferId() == null) {
        continue;
      }
      Optional<DisbursementItem> item = itemOf(d, result.homeTransactionId());
      if (item.isEmpty()) {
        decisions.put(result.transferId(), false);
      } else if (item.get().getState() == DisbursementItemState.SUBMITTED) {
        // a repeated report: accept again what was reserved the first time
        decisions.put(result.transferId(), QuarkusTransaction.joiningExisting()
            .call(() -> transferRepository.findOutboundByHomeTransactionId(result.homeTransactionId()))
            .filter(t -> t.getState() == TransferState.RESERVED).isPresent());
      } else if (result.quote() == null || result.error() != null) {
        fail(item.get(), DisbursementItemState.QUOTING,
            result.error() != null ? result.error() : new FspiopError("3200", "No quote for " + result.transferId()));
        decisions.put(result.transferId(), false);
      } else {
        decisions.put(result.transferId(), reserve(d, item.get(), result));
      }
    }
    LOG.infof("Bulk transaction %s: accepting %d of %d quotes", d.getDisbursementId(),
        decisions.values().stream().filter(Boolean::booleanValue).count(), decisions.size());
    async.submit(() -> {
      try {
        sdk.acceptBulkQuotes(d.getPayerFspId(), d.getDisbursementId(), decisions);
      } catch (RuntimeException e) {
        // the reserved transfers stay RESERVED; the expiry job asks the hub about each one
        LOG.warnf("Accepting the quotes of bulk transaction %s failed: %s", d.getDisbursementId(), e.getMessage());
      }
    });
  }

  /** reserves the item's funds and marks it SUBMITTED in one transaction; false if it can't be paid */
  private boolean reserve(Disbursement d, DisbursementItem item, BulkTransactionUpdate.Item result) {
    try {
      return QuarkusTransaction.joiningExisting().call(() -> {
        DisbursementItem current = itemRepository.findByIdForUpdate(item.getId()).orElseThrow();
        if (current.getState() != DisbursementItemState.QUOTING) {
          // cancelled meanwhile
          return false;
        }
        payerService.reserveQuoted(transfer(d, current, result));
        current.setState(DisbursementItemState.SUBMITTED);
        current.setTransferId(result.transferId());
        current.setUpdatedAt(Instant.now());
        itemRepository.save(current);
        return true;
      });
    } catch (BusinessException e) {
      fail(item, DisbursementItemState.QUOTING, new FspiopError(e.getCode(), e.getMessage()));
      return false;
    }
  }

  /** the outcome of each transfer (COMPLETED), or of the whole bulk transaction (e.g. ERROR_OCCURRED) */
  private void outcomes(Disbursement d, BulkTransactionUpdate update) {
    if (d.getState() == DisbursementState.VALIDATING) {
      // the SDK failed before it could report the parties: nothing can be paid
      partiesFound(d, update);
      return;
    }
    for (BulkTransactionUpdate.Item result : update.items()) {
      Optional<DisbursementItem> item = itemOf(d, result.homeTransactionId());
      if (item.isEmpty()) {
        continue;
      }
      if (item.get().getState() == DisbursementItemState.QUOTING) {
        fail(item.get(), DisbursementItemState.QUOTING,
            result.error() != null ? result.error() : update.error() != null ? update.error() : NO_ANSWER);
        continue;
      }
      boolean committed = "COMMITTED".equals(result.transferState());
      boolean aborted = result.error() != null || "ABORTED".equals(result.transferState());
      if (!committed && !aborted) {
        continue;
      }
      QuarkusTransaction.joiningExisting()
          .call(() -> transferRepository.findOutboundByHomeTransactionId(result.homeTransactionId()))
          .filter(t -> t.getState() == TransferState.RESERVED)
          .ifPresent(t -> payerService.settle(t, committed, result.fulfilment(), result.completedAt(),
              result.error()));
    }
  }

  // --- scheduler ---

  /** items still waiting for a party or quote report after {@code bulk-timeout} fail with 2004 */
  public void timeouts() {
    Instant cutoff = Instant.now().minus(config.disbursement().bulkTimeout());
    for (DisbursementItemState waiting : List.of(DisbursementItemState.PENDING, DisbursementItemState.QUOTING)) {
      for (DisbursementItem item : QuarkusTransaction.joiningExisting()
          .call(() -> itemRepository.findByStateUpdatedBefore(waiting, cutoff))) {
        Disbursement d = QuarkusTransaction.joiningExisting()
            .call(() -> disbursementRepository.findByDisbursementId(item.getDisbursementId()).orElseThrow());
        // PENDING items of a CREATED or INDIVIDUAL batch are not waiting for the SDK
        if (d.getMode() == DisbursementMode.BULK && (waiting == DisbursementItemState.QUOTING
            || d.getState() == DisbursementState.VALIDATING)) {
          LOG.warnf("Bulk transaction %s: no %s report for item %s", d.getDisbursementId(),
              waiting == DisbursementItemState.PENDING ? "party" : "quote", item.getHomeTransactionId());
          fail(item, waiting, NO_ANSWER);
          if (waiting == DisbursementItemState.PENDING) {
            closeValidation(d.getDisbursementId());
          }
        }
      }
    }
  }

  private void closeValidation(String disbursementId) {
    QuarkusTransaction.joiningExisting().run(() -> {
      Disbursement d = disbursementRepository.findByDisbursementIdForUpdate(disbursementId).orElseThrow();
      if (d.getState() == DisbursementState.VALIDATING
          && itemRepository.findByDisbursementId(disbursementId, DisbursementItemState.PENDING).isEmpty()) {
        d.setState(DisbursementState.VALIDATED);
        d.setUpdatedAt(Instant.now());
        disbursementRepository.save(d);
      }
    });
  }

  // --- helpers ---

  private Transfer transfer(Disbursement d, DisbursementItem item, BulkTransactionUpdate.Item result) {
    BulkTransactionUpdate.Quote quote = result.quote();
    Instant now = Instant.now();
    Transfer t = new Transfer();
    t.setDirection(TransferDirection.OUTBOUND);
    t.setTransferId(result.transferId());
    t.setHomeTransactionId(item.getHomeTransactionId());
    t.setQuoteId(quote.quoteId());
    // the SDK uses the transfer id as each individual quote's transaction id, as for single transfers
    t.setTransactionId(result.transferId());
    t.setPayerFspId(d.getPayerFspId());
    t.setPayerIdType(d.getPayerIdType());
    t.setPayerIdValue(d.getPayerIdValue());
    t.setPayerAccountId(d.getPayerAccountId());
    t.setPayeeFspId(item.getPayeeFspId());
    t.setPayeeIdType(item.getPayeeIdType());
    t.setPayeeIdValue(item.getPayeeIdValue());
    t.setPayeeName(item.getPayeeName());
    t.setAmount(item.getAmount());
    t.setCurrency(d.getCurrency());
    t.setTransferAmount(quote.transferAmount() != null ? quote.transferAmount() : item.getAmount());
    t.setPayeeReceiveAmount(quote.payeeReceiveAmount());
    t.setPayeeFee(quote.payeeFee());
    t.setIlpPacket(quote.ilpPacket());
    t.setIlpCondition(quote.condition());
    t.setNote(item.getNote() != null ? item.getNote() : d.getReference());
    t.setAutoAccept(true);
    t.setCreatedAt(now);
    t.setUpdatedAt(now);
    return t;
  }

  private Optional<DisbursementItem> itemOf(Disbursement d, String homeTransactionId) {
    if (homeTransactionId == null) {
      return Optional.empty();
    }
    return QuarkusTransaction.joiningExisting()
        .call(() -> itemRepository.findByHomeTransactionId(homeTransactionId))
        .filter(i -> d.getDisbursementId().equals(i.getDisbursementId()));
  }

  /** marks the item FAILED (INVALID while validating) under a row lock, if it is still {@code expected} */
  private void fail(DisbursementItem item, DisbursementItemState expected, FspiopError error) {
    QuarkusTransaction.joiningExisting().run(() -> itemRepository.findByIdForUpdate(item.getId())
        .filter(i -> i.getState() == expected)
        .ifPresent(i -> {
          i.setState(expected == DisbursementItemState.PENDING ? DisbursementItemState.INVALID
              : DisbursementItemState.FAILED);
          i.setErrorCode(error.code());
          i.setErrorDescription(error.description());
          i.setUpdatedAt(Instant.now());
          itemRepository.save(i);
        }));
  }

  private static Map<String, BulkTransactionUpdate.Item> byHomeTransactionId(BulkTransactionUpdate update) {
    return update.items().stream().filter(i -> i.homeTransactionId() != null)
        .collect(Collectors.toMap(BulkTransactionUpdate.Item::homeTransactionId, Function.identity(),
            (a, b) -> a));
  }

}
