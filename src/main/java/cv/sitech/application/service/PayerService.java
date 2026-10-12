package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.SdkOutboundGateway;
import cv.sitech.application.port.out.SdkOutboundGateway.SdkTransfer;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Payer side of a P2P transfer, driven through the DFSP's SDK scheme adapter: discovery, then the
 * quote, then the transfer, each one synchronous SDK call. Each step stores its state before calling
 * the SDK, so a crash or timeout leaves a row that says how far the transfer got.
 */
@ApplicationScoped
public class PayerService implements OutboundTransferUseCase {

  private static final Logger LOG = Logger.getLogger(PayerService.class);

  private final TransferRepository transferRepository;
  private final PartyRepository partyRepository;
  private final AccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SdkOutboundGateway sdk;
  private final SimulatorConfig config;

  public PayerService(TransferRepository transferRepository, PartyRepository partyRepository,
      AccountRepository accountRepository, LedgerService ledgerService, SdkOutboundGateway sdk,
      SimulatorConfig config) {
    this.transferRepository = transferRepository;
    this.partyRepository = partyRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.sdk = sdk;
    this.config = config;
  }

  /** keyed by homeTransactionId, which never changes, unlike the transferId the SDK assigns */
  static String ledgerReference(Transfer transfer) {
    return "OUT:" + transfer.getHomeTransactionId();
  }

  // --- user-facing API ---

  @Override
  public PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue) {
    return sdk.lookupParty(fspId, idType, idValue);
  }

  @Override
  public Transfer start(String fspId, StartTransferCommand command) {
    if (command.homeTransactionId() != null) {
      var existing = QuarkusTransaction.joiningExisting().call(
          () -> transferRepository.findOutboundByHomeTransactionId(command.homeTransactionId()));
      if (existing.isPresent()) {
        if (!fspId.equals(existing.get().getPayerFspId())) {
          throw new IllegalArgumentException("homeTransactionId " + command.homeTransactionId() + " is taken");
        }
        // already started: the row says how far it got, and the expiry job resolves a RESERVED one
        return existing.get();
      }
    }
    Transfer transfer = QuarkusTransaction.joiningExisting().call(() -> {
      Party payer = partyRepository.findByIdentifier(command.payerIdType(), command.payerIdValue())
          .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()))
          .orElseThrow(() -> new NotFoundException(
              "Payer " + command.payerIdType() + "/" + command.payerIdValue() + " not Found"));
      CurrencyEnum currency = payer.getAccount().getDfsp().getCurrency();
      if (command.currency() != null && command.currency() != currency) {
        throw new IllegalArgumentException("Payer account currency is " + currency);
      }
      validateAmount(command.amount(), currency);

      Instant now = Instant.now();
      String reference = command.homeTransactionId() != null ? command.homeTransactionId()
          : UUID.randomUUID().toString();
      Transfer t = new Transfer();
      t.setDirection(TransferDirection.OUTBOUND);
      // replaced by the SDK's transfer id once discovery returns
      t.setTransferId(reference);
      t.setHomeTransactionId(reference);
      t.setPayerFspId(fspId);
      t.setPayerIdType(command.payerIdType());
      t.setPayerIdValue(command.payerIdValue());
      t.setPayerAccountId(payer.getAccount().getId());
      t.setPayeeIdType(command.payeeIdType());
      t.setPayeeIdValue(command.payeeIdValue());
      t.setAmount(command.amount());
      t.setCurrency(currency);
      t.setNote(command.note());
      t.setAutoAccept(command.autoAccept());
      t.setState(TransferState.WAITING_FOR_PARTY_INFO);
      t.setCreatedAt(now);
      t.setUpdatedAt(now);
      t.setExpiration(now.plus(config.transferExpiry()));
      return transferRepository.save(t);
    });

    SdkTransfer result;
    try {
      result = sdk.startTransfer(fspId, transfer);
    } catch (RuntimeException e) {
      return abort(transfer, error(e));
    }
    if (result.failed()) {
      return abort(transfer, failure(result));
    }
    Transfer resolved = QuarkusTransaction.joiningExisting().call(() -> {
      Transfer t = reload(transfer);
      if (result.transferId() != null) {
        t.setTransferId(result.transferId());
      }
      if (result.payee() != null) {
        t.setPayeeFspId(result.payee().fspId());
        t.setPayeeName(result.payee().name());
      }
      t.setState(TransferState.WAITING_FOR_PARTY_ACCEPTANCE);
      t.setUpdatedAt(Instant.now());
      return transferRepository.save(t);
    });
    return resolved.isAutoAccept() ? requestQuote(resolved) : resolved;
  }

  @Override
  public Transfer acceptParty(String fspId, String transferId, boolean accept) {
    Transfer transfer = findById(fspId, transferId);
    requireState(transfer, TransferState.WAITING_FOR_PARTY_ACCEPTANCE);
    if (!accept) {
      return reject(transfer, sdk::acceptParty, new FspiopError("5101", "Payee rejected by payer"));
    }
    return requestQuote(transfer);
  }

  @Override
  public Transfer acceptQuote(String fspId, String transferId, boolean accept) {
    Transfer transfer = findById(fspId, transferId);
    requireState(transfer, TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);
    if (!accept) {
      return reject(transfer, sdk::acceptQuote, new FspiopError("5101", "Quote rejected by payer"));
    }
    return executeTransfer(transfer);
  }

  @Override
  public Transfer findById(String fspId, String transferId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository
        .findByTransferId(TransferDirection.OUTBOUND, transferId)
        .filter(t -> fspId.equals(t.getPayerFspId()))
        .orElseThrow(() -> new NotFoundException("Transfer " + transferId + " not Found")));
  }

  @Override
  public List<Transfer> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository.findOutboundByPayer(fspId));
  }

  /**
   * Called for non-final transfers past their expiration. A RESERVED transfer may have reached the
   * hub, so its state is asked through the SDK first; only an unresolved one is expired.
   */
  public void expire(Transfer transfer) {
    if (transfer.getState() == TransferState.RESERVED) {
      try {
        SdkTransfer result = sdk.getTransfer(transfer.getPayerFspId(), transfer.getTransferId());
        if (result.committed()) {
          commit(transfer, result);
          return;
        }
        if (result.failed()) {
          abort(transfer, failure(result));
          return;
        }
      } catch (RuntimeException e) {
        LOG.warnf("Could not resolve reserved transfer %s through the SDK: %s", transfer.getTransferId(),
            e.getMessage());
      }
    }
    finish(transfer, TransferState.EXPIRED, new FspiopError("3303", "Transfer expired"));
  }

  // --- bulk transactions (DisbursementService) ---

  /**
   * Stores a quoted item of a bulk transaction as an OUTBOUND transfer and reserves its funds, in one
   * transaction: a {@code BusinessException} (e.g. insufficient funds) leaves neither behind.
   */
  public Transfer reserveQuoted(Transfer quoted) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      Account position = accountRepository.findDfspAccount(quoted.getPayerFspId(), AccountTypeEnum.POSITION)
          .orElseThrow(() -> new NotFoundException("Position account of " + quoted.getPayerFspId()));
      ledgerService.reserve(ledgerReference(quoted), EntryType.TRANSFER_OUT, List.of(
          posting(quoted.getPayerAccountId(), quoted.getTransferAmount().negate()),
          posting(position.getId(), quoted.getTransferAmount())));
      quoted.setState(TransferState.RESERVED);
      quoted.setExpiration(Instant.now().plus(config.transferExpiry()));
      quoted.setUpdatedAt(Instant.now());
      return transferRepository.save(quoted);
    });
  }

  /** books the outcome the SDK reported for a RESERVED bulk item: committed, or aborted with {@code error} */
  public Transfer settle(Transfer transfer, boolean committed, String fulfilment, Instant completedAt,
      FspiopError error) {
    if (committed) {
      return commit(transfer, new SdkTransfer(transfer.getTransferId(), null, null, null, null, null, null, null, null,
          null, fulfilment, "COMMITTED", completedAt, null));
    }
    return abort(transfer, error != null ? error : new FspiopError("5000", "Transfer aborted"));
  }

  // --- steps ---

  private Transfer requestQuote(Transfer transfer) {
    Transfer t = save(transfer, TransferState.WAITING_FOR_QUOTE);
    SdkTransfer result;
    try {
      result = sdk.acceptParty(t.getPayerFspId(), t.getTransferId(), true);
    } catch (RuntimeException e) {
      return abort(t, error(e));
    }
    if (result.failed()) {
      return abort(t, failure(result));
    }
    Transfer quoted = QuarkusTransaction.joiningExisting().call(() -> {
      Transfer current = reload(t);
      current.setQuoteId(result.quoteId());
      // the SDK uses the transfer id as the quote's transaction id
      current.setTransactionId(current.getTransferId());
      current.setTransferAmount(result.transferAmount() != null ? result.transferAmount() : current.getAmount());
      current.setPayeeReceiveAmount(result.payeeReceiveAmount());
      current.setPayeeFee(result.payeeFee());
      current.setIlpPacket(result.ilpPacket());
      current.setIlpCondition(result.condition());
      if (result.expiration() != null) {
        current.setExpiration(result.expiration());
      }
      current.setState(TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);
      current.setUpdatedAt(Instant.now());
      return transferRepository.save(current);
    });
    return quoted.isAutoAccept() ? executeTransfer(quoted) : quoted;
  }

  private Transfer executeTransfer(Transfer transfer) {
    Transfer t;
    try {
      t = QuarkusTransaction.joiningExisting().call(() -> {
        Account position = accountRepository.findDfspAccount(transfer.getPayerFspId(), AccountTypeEnum.POSITION)
            .orElseThrow(() -> new NotFoundException("Position account of " + transfer.getPayerFspId()));
        ledgerService.reserve(ledgerReference(transfer), EntryType.TRANSFER_OUT, List.of(
            posting(transfer.getPayerAccountId(), transfer.getTransferAmount().negate()),
            posting(position.getId(), transfer.getTransferAmount())));
        transfer.setState(TransferState.RESERVED);
        // the SDK sends its own expiration; this one bounds how long the transfer may stay unresolved
        transfer.setExpiration(Instant.now().plus(config.transferExpiry()));
        transfer.setUpdatedAt(Instant.now());
        return transferRepository.save(transfer);
      });
    } catch (BusinessException e) {
      return reject(transfer, sdk::acceptQuote, new FspiopError(e.getCode(), e.getMessage()));
    }

    SdkTransfer result;
    try {
      result = sdk.acceptQuote(t.getPayerFspId(), t.getTransferId(), true);
    } catch (RuntimeException e) {
      // the transfer may have reached the hub; keep the reservation and let the expiry job resolve it
      LOG.warnf("Outcome of transfer %s unknown, keeping it RESERVED: %s", t.getTransferId(), e.getMessage());
      return reload(t);
    }
    if (result.committed()) {
      return commit(t, result);
    }
    if (result.failed()) {
      return abort(t, failure(result));
    }
    LOG.warnf("Transfer %s ended in SDK state %s without a final transfer state", t.getTransferId(),
        result.currentState());
    return reload(t);
  }

  private Transfer commit(Transfer transfer, SdkTransfer result) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      Transfer t = reload(transfer);
      if (t.getState() != TransferState.RESERVED) {
        return t;
      }
      ledgerService.commit(ledgerReference(t));
      t.setFulfilment(result.fulfilment());
      t.setState(TransferState.COMMITTED);
      t.setCompletedAt(result.completedAt() != null ? result.completedAt() : Instant.now());
      t.setUpdatedAt(Instant.now());
      return transferRepository.save(t);
    });
  }

  private interface Decision {
    SdkTransfer send(String fspId, String transferId, boolean accept);
  }

  /** tells the SDK the payer declined, so it drops the transfer, and aborts it here */
  private Transfer reject(Transfer transfer, Decision decision, FspiopError error) {
    try {
      decision.send(transfer.getPayerFspId(), transfer.getTransferId(), false);
    } catch (RuntimeException e) {
      LOG.debugf("SDK did not take the rejection of %s: %s", transfer.getTransferId(), e.getMessage());
    }
    return abort(transfer, error);
  }

  private Transfer save(Transfer transfer, TransferState state) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      transfer.setState(state);
      transfer.setUpdatedAt(Instant.now());
      return transferRepository.save(transfer);
    });
  }

  private Transfer abort(Transfer transfer, FspiopError error) {
    return finish(transfer, TransferState.ABORTED, error);
  }

  private Transfer finish(Transfer transfer, TransferState state, FspiopError error) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      Transfer current = reload(transfer);
      if (current.getState().isFinal()) {
        return current;
      }
      ledgerService.abort(ledgerReference(current));
      current.setState(state);
      current.setErrorCode(error.code());
      current.setErrorDescription(error.description());
      current.setUpdatedAt(Instant.now());
      return transferRepository.save(current);
    });
  }

  private Transfer reload(Transfer transfer) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository
        .findByTransferId(TransferDirection.OUTBOUND, transfer.getTransferId()).orElse(transfer));
  }

  private static void requireState(Transfer transfer, TransferState expected) {
    if (transfer.getState() != expected) {
      throw new BusinessException("3100",
          "Transfer " + transfer.getTransferId() + " is " + transfer.getState() + ", expected " + expected);
    }
  }

  static void validateAmount(BigDecimal amount, CurrencyEnum currency) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
    if (amount.stripTrailingZeros().scale() > currency.scale()) {
      throw new IllegalArgumentException(currency + " amounts have at most " + currency.scale() + " decimals");
    }
  }

  private static FspiopError failure(SdkTransfer result) {
    return result.error() != null ? result.error()
        : new FspiopError("5000", "Transfer " + (result.currentState() != null ? result.currentState() : "failed"));
  }

  private static FspiopError error(RuntimeException e) {
    return e instanceof BusinessException be ? new FspiopError(be.getCode(), be.getMessage())
        : new FspiopError("1001", "SDK unreachable: " + e.getMessage());
  }

}
