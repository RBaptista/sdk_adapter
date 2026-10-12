package cv.sitech.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.CreateDisbursementCommand;
import cv.sitech.application.port.in.DisbursementUseCase;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.DisbursementItemRepository;
import cv.sitech.application.port.out.DisbursementRepository;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import cv.sitech.domain.disbursement.DisbursementMode;
import cv.sitech.domain.disbursement.DisbursementState;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.InsufficientFundsException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * G2P disbursements. The user calls create, validate and approve; how a batch reaches the hub depends on
 * its {@link DisbursementMode}, fixed when it is created:
 * <ul>
 * <li>BULK: the SDK's /bulkTransactions, see {@link BulkDisbursementService};</li>
 * <li>INDIVIDUAL: each item is paid as its own auto-accepted transfer through {@link OutboundTransferUseCase},
 * keyed by the item's {@code homeTransactionId} so that a retried item never pays twice. The scheduler
 * calls {@link #validatePending()} and {@link #executeQueued()}.</li>
 * </ul>
 * {@link #sync()} updates items from their transfers and closes batches in both modes. No database
 * transaction is held across an SDK call.
 */
@ApplicationScoped
public class DisbursementService implements DisbursementUseCase {

  private static final Logger LOG = Logger.getLogger(DisbursementService.class);

  /** SDK unreachable or timed out: the lookup is tried again on the next run */
  private static final List<String> RETRYABLE = List.of("1001", "2004");
  private static final List<DisbursementItemState> IN_FLIGHT = List.of(DisbursementItemState.QUEUED,
      DisbursementItemState.QUOTING, DisbursementItemState.SUBMITTED);

  private final DisbursementRepository disbursementRepository;
  private final DisbursementItemRepository itemRepository;
  private final PartyRepository partyRepository;
  private final AccountRepository accountRepository;
  private final TransferRepository transferRepository;
  private final OutboundTransferUseCase outbound;
  private final BulkDisbursementService bulk;
  private final SimulatorConfig config;

  public DisbursementService(DisbursementRepository disbursementRepository, DisbursementItemRepository itemRepository,
      PartyRepository partyRepository, AccountRepository accountRepository, TransferRepository transferRepository,
      OutboundTransferUseCase outbound, BulkDisbursementService bulk, SimulatorConfig config) {
    this.disbursementRepository = disbursementRepository;
    this.itemRepository = itemRepository;
    this.partyRepository = partyRepository;
    this.accountRepository = accountRepository;
    this.transferRepository = transferRepository;
    this.outbound = outbound;
    this.bulk = bulk;
    this.config = config;
  }

  // --- user-facing API ---

  @Override
  public Disbursement create(String fspId, CreateDisbursementCommand command) {
    if (command == null || command.items() == null || command.items().isEmpty()) {
      throw new IllegalArgumentException("items are required");
    }
    if (command.items().size() > config.disbursement().maxItems()) {
      throw new IllegalArgumentException("A disbursement has at most " + config.disbursement().maxItems() + " items");
    }
    return QuarkusTransaction.joiningExisting().call(() -> {
      Party payer = partyRepository.findByIdentifier(command.payerIdType(), command.payerIdValue())
          .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()))
          .orElseThrow(() -> new NotFoundException(
              "Payer " + command.payerIdType() + "/" + command.payerIdValue() + " not Found"));
      CurrencyEnum currency = payer.getAccount().getDfsp().getCurrency();
      if (command.currency() != null && command.currency() != currency) {
        throw new IllegalArgumentException("Payer account currency is " + currency);
      }

      BigDecimal total = BigDecimal.ZERO;
      Map<String, Integer> lines = new HashMap<>();
      for (int i = 0; i < command.items().size(); i++) {
        int line = i + 1;
        CreateDisbursementCommand.Item item = command.items().get(i);
        if (item == null || item.payeeIdType() == null || item.payeeIdValue() == null
            || item.payeeIdValue().isBlank()) {
          throw new IllegalArgumentException("Line " + line + ": payee is required");
        }
        try {
          PayerService.validateAmount(item.amount(), currency);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Line " + line + ": " + e.getMessage());
        }
        if (item.payeeIdType() == command.payerIdType() && item.payeeIdValue().equals(command.payerIdValue())) {
          throw new IllegalArgumentException("Line " + line + ": payee is the payer");
        }
        Integer first = lines.putIfAbsent(item.payeeIdType() + "/" + item.payeeIdValue(), line);
        if (first != null) {
          throw new IllegalArgumentException("Line " + line + " repeats the payee of line " + first);
        }
        total = total.add(item.amount());
      }

      Instant now = Instant.now();
      Disbursement d = new Disbursement();
      d.setDisbursementId(UUID.randomUUID().toString());
      d.setPayerFspId(fspId);
      d.setPayerIdType(command.payerIdType());
      d.setPayerIdValue(command.payerIdValue());
      d.setPayerAccountId(payer.getAccount().getId());
      d.setCurrency(currency);
      d.setReference(command.reference());
      d.setNote(command.note());
      d.setState(DisbursementState.CREATED);
      d.setMode(config.disbursement().mode());
      d.setItemCount(command.items().size());
      d.setTotalAmount(total);
      d.setCommittedAmount(BigDecimal.ZERO);
      d.setCreatedAt(now);
      d.setUpdatedAt(now);
      Disbursement saved = disbursementRepository.save(d);

      for (int i = 0; i < command.items().size(); i++) {
        CreateDisbursementCommand.Item source = command.items().get(i);
        DisbursementItem item = new DisbursementItem();
        item.setDisbursementId(saved.getDisbursementId());
        item.setLineNo(i + 1);
        item.setPayeeIdType(source.payeeIdType());
        item.setPayeeIdValue(source.payeeIdValue());
        item.setAmount(source.amount());
        item.setNote(source.note());
        item.setState(DisbursementItemState.PENDING);
        item.setHomeTransactionId(UUID.randomUUID().toString());
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemRepository.save(item);
      }
      return saved;
    });
  }

  @Override
  public Disbursement validate(String fspId, String disbursementId) {
    Disbursement validating = QuarkusTransaction.joiningExisting().call(() -> {
      Disbursement d = lock(fspId, disbursementId);
      if (d.getMode() == DisbursementMode.BULK) {
        // the SDK holds the bulk transaction once it has the parties; it can't be looked up again
        requireState(d, DisbursementState.CREATED);
      } else {
        requireState(d, DisbursementState.CREATED, DisbursementState.VALIDATED);
      }
      // validating again looks every payee up again
      for (DisbursementItem item : itemRepository.lockByDisbursementId(disbursementId,
          List.of(DisbursementItemState.VALID, DisbursementItemState.INVALID))) {
        item.setState(DisbursementItemState.PENDING);
        item.setPayeeFspId(null);
        item.setPayeeName(null);
        item.setErrorCode(null);
        item.setErrorDescription(null);
        item.setUpdatedAt(Instant.now());
        itemRepository.save(item);
      }
      return save(d, DisbursementState.VALIDATING);
    });
    if (validating.getMode() == DisbursementMode.BULK) {
      try {
        bulk.start(validating);
      } catch (RuntimeException e) {
        revert(disbursementId, DisbursementState.VALIDATING, DisbursementState.CREATED, null, null);
        throw e;
      }
    }
    return validating;
  }

  @Override
  public Disbursement approve(String fspId, String disbursementId) {
    Disbursement approved = QuarkusTransaction.joiningExisting().call(() -> {
      Disbursement d = lock(fspId, disbursementId);
      requireState(d, DisbursementState.VALIDATED);
      List<DisbursementItem> valid = itemRepository.lockByDisbursementId(disbursementId,
          List.of(DisbursementItemState.VALID));
      if (valid.isEmpty()) {
        throw new BusinessException("3100", "Disbursement " + disbursementId + " has no valid items");
      }
      BigDecimal total = valid.stream().map(DisbursementItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
      // each item still reserves its own funds; this only refuses a batch that can't be paid in full
      Account payer = accountRepository.findById(d.getPayerAccountId())
          .orElseThrow(() -> new NotFoundException("Account " + d.getPayerAccountId() + " not Found"));
      if (payer.availableBalance().compareTo(total) < 0) {
        throw new InsufficientFundsException(
            "Available balance " + payer.availableBalance() + " is below the valid items' total " + total);
      }
      Instant now = Instant.now();
      // BULK items wait for the SDK's bulk quote; INDIVIDUAL items for a worker
      DisbursementItemState next = d.getMode() == DisbursementMode.BULK ? DisbursementItemState.QUOTING
          : DisbursementItemState.QUEUED;
      for (DisbursementItem item : valid) {
        item.setState(next);
        item.setUpdatedAt(now);
        itemRepository.save(item);
      }
      d.setApprovedAt(now);
      return save(d, DisbursementState.EXECUTING);
    });
    if (approved.getMode() == DisbursementMode.BULK) {
      try {
        bulk.acceptParties(approved);
      } catch (RuntimeException e) {
        revert(disbursementId, DisbursementState.EXECUTING, DisbursementState.VALIDATED,
            DisbursementItemState.QUOTING, DisbursementItemState.VALID);
        throw e;
      }
    }
    return approved;
  }

  @Override
  public Disbursement cancel(String fspId, String disbursementId) {
    // the state before cancelling: a VALIDATED BULK batch has an SDK waiting for party acceptance
    Map.Entry<Disbursement, DisbursementState> cancelled = QuarkusTransaction.joiningExisting().call(() -> {
      Disbursement d = lock(fspId, disbursementId);
      DisbursementState was = d.getState();
      if (d.getState().isFinal()) {
        throw new BusinessException("3100", "Disbursement " + disbursementId + " is " + d.getState());
      }
      // waits for a worker claiming QUEUED items; the ones it sent are SUBMITTED by then and finish normally
      // a QUOTING (BULK) item is skipped too: its quote is rejected when the SDK reports it
      for (DisbursementItem item : itemRepository.lockByDisbursementId(disbursementId, List.of(
          DisbursementItemState.PENDING, DisbursementItemState.VALID, DisbursementItemState.QUEUED,
          DisbursementItemState.QUOTING))) {
        item.setState(DisbursementItemState.SKIPPED);
        item.setUpdatedAt(Instant.now());
        itemRepository.save(item);
      }
      return Map.entry(save(d, DisbursementState.CANCELLED), was);
    });
    if (cancelled.getKey().getMode() == DisbursementMode.BULK && cancelled.getValue() == DisbursementState.VALIDATED) {
      bulk.rejectParties(cancelled.getKey());
    }
    return refresh(disbursementId);
  }

  @Override
  public Disbursement findById(String fspId, String disbursementId) {
    return QuarkusTransaction.joiningExisting().call(() -> disbursementRepository
        .findByDisbursementId(disbursementId)
        .filter(d -> fspId.equals(d.getPayerFspId()))
        .orElseThrow(() -> new NotFoundException("Disbursement " + disbursementId + " not Found")));
  }

  @Override
  public List<Disbursement> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> disbursementRepository.findByPayer(fspId));
  }

  @Override
  public List<DisbursementItem> items(String fspId, String disbursementId, DisbursementItemState state) {
    findById(fspId, disbursementId);
    return QuarkusTransaction.joiningExisting().call(() -> state == null
        ? itemRepository.findByDisbursementId(disbursementId)
        : itemRepository.findByDisbursementId(disbursementId, state));
  }

  // --- scheduler ---

  /** looks up the payee of every PENDING item of the VALIDATING batches */
  public void validatePending() {
    List<Disbursement> batches = QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByState(DisbursementState.VALIDATING)).stream()
        .filter(d -> d.getMode() == DisbursementMode.INDIVIDUAL).toList();
    for (Disbursement d : batches) {
      Queue<DisbursementItem> pending = new ConcurrentLinkedQueue<>(QuarkusTransaction.joiningExisting()
          .call(() -> itemRepository.findByDisbursementId(d.getDisbursementId(), DisbursementItemState.PENDING)));
      runWorkers(pending.size(), () -> {
        for (DisbursementItem item; (item = pending.poll()) != null;) {
          validateItem(d, item);
        }
      });
      QuarkusTransaction.joiningExisting().run(() -> {
        Disbursement current = disbursementRepository.findByDisbursementIdForUpdate(d.getDisbursementId())
            .orElseThrow();
        boolean done = itemRepository.findByDisbursementId(d.getDisbursementId(), DisbursementItemState.PENDING)
            .isEmpty();
        if (current.getState() == DisbursementState.VALIDATING && done) {
          save(current, DisbursementState.VALIDATED);
        }
      });
    }
  }

  /** pays the QUEUED items; each worker claims one item at a time, so a slow transfer holds up no other */
  public void executeQueued() {
    runWorkers(config.disbursement().parallelism(), () -> {
      for (Optional<DisbursementItem> item; (item = claim()).isPresent();) {
        executeItem(item.get());
      }
    });
  }

  /**
   * Resolves SUBMITTED items from their transfers (a RESERVED transfer is settled by the expiry job),
   * queues again those that were never sent, and updates or closes the running batches.
   */
  public void sync() {
    Instant now = Instant.now();
    List<DisbursementItem> submitted = QuarkusTransaction.joiningExisting()
        .call(() -> itemRepository.findByStateUpdatedBefore(DisbursementItemState.SUBMITTED, now));
    for (DisbursementItem item : submitted) {
      Optional<Transfer> transfer = QuarkusTransaction.joiningExisting()
          .call(() -> transferRepository.findOutboundByHomeTransactionId(item.getHomeTransactionId()));
      if (transfer.isPresent()) {
        apply(item.getId(), transfer.get());
      } else if (item.getUpdatedAt().isBefore(now.minus(config.disbursement().resubmitAfter()))
          && modeOf(item) == DisbursementMode.INDIVIDUAL) {
        // PayerService saves the transfer before it calls the SDK: no row means nothing was sent
        update(item.getId(), DisbursementItemState.SUBMITTED, i -> i.setState(DisbursementItemState.QUEUED));
        LOG.warnf("Disbursement item %s was never sent, queued again", item.getHomeTransactionId());
      }
    }

    List<Disbursement> running = new ArrayList<>(QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByState(DisbursementState.EXECUTING)));
    running.addAll(QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByState(DisbursementState.CANCELLED)).stream()
        .filter(d -> d.getCompletedAt() == null).toList());
    for (Disbursement d : running) {
      refresh(d.getDisbursementId());
    }
  }

  // --- steps ---

  private void validateItem(Disbursement d, DisbursementItem item) {
    PartyInfo party;
    try {
      party = outbound.lookupParty(d.getPayerFspId(), item.getPayeeIdType(), item.getPayeeIdValue());
    } catch (BusinessException e) {
      if (RETRYABLE.contains(e.getCode())) {
        LOG.warnf("Lookup of %s/%s failed, trying again later: %s", item.getPayeeIdType(), item.getPayeeIdValue(),
            e.getMessage());
        return;
      }
      update(item.getId(), DisbursementItemState.PENDING, i -> {
        i.setState(DisbursementItemState.INVALID);
        i.setErrorCode(e.getCode());
        i.setErrorDescription(e.getMessage());
      });
      return;
    }
    update(item.getId(), DisbursementItemState.PENDING, i -> {
      i.setState(DisbursementItemState.VALID);
      i.setPayeeFspId(party.fspId());
      i.setPayeeName(party.name());
    });
  }

  /** locks one QUEUED item, skipping those other workers hold, and marks it SUBMITTED */
  private Optional<DisbursementItem> claim() {
    return QuarkusTransaction.requiringNew().call(() -> itemRepository.lockQueued(1).stream().findFirst().map(i -> {
      i.setState(DisbursementItemState.SUBMITTED);
      i.setUpdatedAt(Instant.now());
      return itemRepository.save(i);
    }));
  }

  private void executeItem(DisbursementItem item) {
    Disbursement d = QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByDisbursementId(item.getDisbursementId()).orElseThrow());
    String note = item.getNote() != null ? item.getNote() : d.getReference() != null ? d.getReference() : d.getNote();
    Transfer transfer;
    try {
      transfer = outbound.start(d.getPayerFspId(), new StartTransferCommand(d.getPayerIdType(), d.getPayerIdValue(),
          item.getPayeeIdType(), item.getPayeeIdValue(), item.getAmount(), d.getCurrency(), note, true,
          item.getHomeTransactionId()));
    } catch (RuntimeException e) {
      Optional<Transfer> started = QuarkusTransaction.joiningExisting()
          .call(() -> transferRepository.findOutboundByHomeTransactionId(item.getHomeTransactionId()));
      if (started.isPresent()) {
        // the transfer exists, so its outcome is the item's; sync() picks it up once it is final
        LOG.warnf(e, "Disbursement item %s failed after its transfer started", item.getHomeTransactionId());
        apply(item.getId(), started.get());
        return;
      }
      update(item.getId(), DisbursementItemState.SUBMITTED, i -> {
        i.setState(DisbursementItemState.FAILED);
        i.setErrorCode(e instanceof BusinessException be ? be.getCode() : "2001");
        i.setErrorDescription(e.getMessage());
      });
      return;
    }
    apply(item.getId(), transfer);
  }

  /** copies the transfer's outcome to its SUBMITTED item; a transfer still running leaves it SUBMITTED */
  private void apply(Long itemId, Transfer transfer) {
    update(itemId, DisbursementItemState.SUBMITTED, i -> {
      i.setTransferId(transfer.getTransferId());
      if (transfer.getPayeeFspId() != null) {
        i.setPayeeFspId(transfer.getPayeeFspId());
        i.setPayeeName(transfer.getPayeeName());
      }
      switch (transfer.getState()) {
        case COMMITTED -> {
          i.setState(DisbursementItemState.COMMITTED);
          i.setErrorCode(null);
          i.setErrorDescription(null);
        }
        case ABORTED, EXPIRED -> {
          i.setState(DisbursementItemState.FAILED);
          i.setErrorCode(transfer.getErrorCode());
          i.setErrorDescription(transfer.getErrorDescription());
        }
        default -> {
          // still running: the item stays SUBMITTED until sync() sees the transfer final
        }
      }
    });
  }

  /** updates the batch's counters and, once no item is in flight, closes it */
  private Disbursement refresh(String disbursementId) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      Disbursement d = disbursementRepository.findByDisbursementIdForUpdate(disbursementId).orElseThrow();
      List<DisbursementItem> all = itemRepository.findByDisbursementId(disbursementId);
      List<DisbursementItem> committed = all.stream()
          .filter(i -> i.getState() == DisbursementItemState.COMMITTED).toList();
      d.setCommittedCount(committed.size());
      d.setCommittedAmount(committed.stream().map(DisbursementItem::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add));
      // not paid: failed transfers and payees the lookup did not find
      d.setFailedCount((int) all.stream().filter(i -> i.getState() == DisbursementItemState.FAILED
          || i.getState() == DisbursementItemState.INVALID).count());
      boolean done = all.stream().noneMatch(i -> IN_FLIGHT.contains(i.getState()));
      DisbursementState state = d.getState();
      if (done && d.getCompletedAt() == null) {
        d.setCompletedAt(Instant.now());
        if (state == DisbursementState.EXECUTING) {
          state = committed.size() == d.getItemCount() ? DisbursementState.COMPLETED
              : committed.isEmpty() ? DisbursementState.FAILED : DisbursementState.PARTIALLY_COMPLETED;
        }
      }
      return save(d, state);
    });
  }

  private interface ItemChange {
    void apply(DisbursementItem item);
  }

  /** changes the item under a row lock, only if it is still in {@code expected} */
  private void update(Long itemId, DisbursementItemState expected, ItemChange change) {
    QuarkusTransaction.joiningExisting().run(() -> itemRepository.findByIdForUpdate(itemId)
        .filter(i -> i.getState() == expected)
        .ifPresent(i -> {
          change.apply(i);
          if (i.getState() != expected) {
            i.setUpdatedAt(Instant.now());
          }
          itemRepository.save(i);
        }));
  }

  /** runs {@code work} on up to {@code parallelism} threads at once and waits for all of them */
  private void runWorkers(int tasks, Runnable work) {
    int workers = Math.min(tasks, config.disbursement().parallelism());
    if (workers <= 0) {
      return;
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
      for (int i = 0; i < workers; i++) {
        executor.submit(() -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            LOG.error("Disbursement worker failed", e);
          }
        });
      }
    }
  }

  private DisbursementMode modeOf(DisbursementItem item) {
    return QuarkusTransaction.joiningExisting()
        .call(() -> disbursementRepository.findByDisbursementId(item.getDisbursementId()).orElseThrow()).getMode();
  }

  /** undoes a state change the SDK refused: the batch from {@code from} to {@code to}, its items likewise */
  private void revert(String disbursementId, DisbursementState from, DisbursementState to,
      DisbursementItemState itemsFrom, DisbursementItemState itemsTo) {
    QuarkusTransaction.joiningExisting().run(() -> {
      Disbursement d = disbursementRepository.findByDisbursementIdForUpdate(disbursementId).orElseThrow();
      if (d.getState() != from) {
        return;
      }
      if (itemsFrom != null) {
        for (DisbursementItem item : itemRepository.lockByDisbursementId(disbursementId, List.of(itemsFrom))) {
          item.setState(itemsTo);
          item.setUpdatedAt(Instant.now());
          itemRepository.save(item);
        }
      }
      if (to == DisbursementState.VALIDATED) {
        d.setApprovedAt(null);
      }
      save(d, to);
    });
  }

  private Disbursement lock(String fspId, String disbursementId) {
    return disbursementRepository.findByDisbursementIdForUpdate(disbursementId)
        .filter(d -> fspId.equals(d.getPayerFspId()))
        .orElseThrow(() -> new NotFoundException("Disbursement " + disbursementId + " not Found"));
  }

  private Disbursement save(Disbursement d, DisbursementState state) {
    d.setState(state);
    d.setUpdatedAt(Instant.now());
    return disbursementRepository.save(d);
  }

  private static void requireState(Disbursement d, DisbursementState... expected) {
    for (DisbursementState s : expected) {
      if (d.getState() == s) {
        return;
      }
    }
    throw new BusinessException("3100", "Disbursement " + d.getDisbursementId() + " is " + d.getState()
        + ", expected " + List.of(expected));
  }

}
