package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.in.QuoteRequest;
import cv.sitech.application.port.in.TransferPrepare;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.Customer;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.ledger.Posting;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Payee side of a P2P transfer, answering the DFSP's SDK scheme adapter: discovery and quotes for
 * hosted parties, and booking transfers. The SDK builds the ILP packet, condition and fulfilment.
 * The quote is stored as an INBOUND transfer row, and the later transfer is matched to it by quote id.
 */
@ApplicationScoped
public class PayeeService implements PayeeUseCase {

  private static final FspiopError PARTY_NOT_FOUND = new FspiopError("3204", "Party not found");

  private final TransferRepository transferRepository;
  private final PartyRepository partyRepository;
  private final AccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SimulatorConfig config;

  public PayeeService(TransferRepository transferRepository, PartyRepository partyRepository,
      AccountRepository accountRepository, LedgerService ledgerService, SimulatorConfig config) {
    this.transferRepository = transferRepository;
    this.partyRepository = partyRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.config = config;
  }

  static String ledgerReference(Transfer transfer) {
    return "IN:" + transfer.getTransferId();
  }

  @Override
  public PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue) {
    Party party = QuarkusTransaction.joiningExisting().call(() -> hostedParty(fspId, idType, idValue))
        .orElseThrow(() -> new BusinessException(PARTY_NOT_FOUND.code(), PARTY_NOT_FOUND.description()));
    return new PartyInfo(idType, idValue, fspId, displayName(party));
  }

  @Override
  public Transfer quote(String fspId, QuoteRequest request) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      var existing = transferRepository.findByQuoteId(TransferDirection.INBOUND, request.quoteId());
      if (existing.isPresent()) {
        // a resent quote request gets the same answer
        return existing.get();
      }
      return transferRepository.save(newQuote(fspId, request));
    });
  }

  @Override
  public Transfer prepareTransfer(String fspId, TransferPrepare prepare) {
    return prepare(fspId, prepare, "RESERVED".equalsIgnoreCase(config.payee().transferState()));
  }

  @Override
  public Transfer prepareBulkTransfer(String fspId, TransferPrepare prepare) {
    return prepare(fspId, prepare, false);
  }

  private Transfer prepare(String fspId, TransferPrepare prepare, boolean reserveOnly) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      var duplicate = transferRepository.findByTransferId(TransferDirection.INBOUND, prepare.transferId())
          .filter(t -> fspId.equals(t.getPayeeFspId()));
      if (duplicate.isPresent()) {
        Transfer t = duplicate.get();
        if (t.getState() != TransferState.COMMITTED && t.getState() != TransferState.RESERVED) {
          throw new BusinessException(t.getErrorCode() != null ? t.getErrorCode() : "3100",
              "Transfer " + prepare.transferId() + " is " + t.getState());
        }
        return t;
      }
      Transfer t = reserve(fspId, prepare);
      if (!reserveOnly) {
        ledgerService.commit(ledgerReference(t));
        t.setState(TransferState.COMMITTED);
        t.setCompletedAt(Instant.now());
        t = transferRepository.save(t);
      }
      return t;
    });
  }

  @Override
  public void transferNotified(String fspId, String transferId, String transferState) {
    if ("COMMITTED".equals(transferState)) {
      finish(fspId, transferId, TransferState.COMMITTED, null);
    } else if ("ABORTED".equals(transferState)) {
      finish(fspId, transferId, TransferState.ABORTED, new FspiopError("5000", "Transfer aborted by the hub"));
    }
  }

  @Override
  public List<Transfer> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository.findInboundByPayee(fspId));
  }

  /** expires an INBOUND quote or reservation past its expiration */
  public void expire(Transfer transfer) {
    if (transfer.getState() == TransferState.QUOTED) {
      QuarkusTransaction.joiningExisting().run(() -> {
        transfer.setState(TransferState.EXPIRED);
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);
      });
    } else {
      finish(transfer.getPayeeFspId(), transfer.getTransferId(), TransferState.EXPIRED,
          new FspiopError("3303", "Transfer expired"));
    }
  }

  private Transfer newQuote(String fspId, QuoteRequest request) {
    Party party = hostedParty(fspId, request.payee().idType(), request.payee().idValue())
        .orElseThrow(() -> new BusinessException(PARTY_NOT_FOUND.code(), PARTY_NOT_FOUND.description()));
    Account account = party.getAccount();
    if (request.currency() != account.getDfsp().getCurrency()) {
      throw new BusinessException("3100", "Payee account currency is " + account.getDfsp().getCurrency());
    }
    int scale = request.currency().scale();
    BigDecimal fee = request.amount().multiply(config.payee().feePercent())
        .divide(BigDecimal.valueOf(100), scale, RoundingMode.HALF_UP);
    boolean receive = "RECEIVE".equals(request.amountType());
    BigDecimal transferAmount = receive ? request.amount().add(fee) : request.amount();
    BigDecimal receiveAmount = receive ? request.amount() : request.amount().subtract(fee);
    if (receiveAmount.signum() <= 0) {
      throw new BusinessException("3100", "Amount does not cover the payee fee");
    }

    Instant now = Instant.now();
    Transfer t = new Transfer();
    t.setDirection(TransferDirection.INBOUND);
    t.setQuoteId(request.quoteId());
    t.setTransactionId(request.transactionId());
    t.setPayerFspId(request.payer().fspId());
    t.setPayerIdType(request.payer().idType());
    t.setPayerIdValue(request.payer().idValue());
    t.setPayeeFspId(fspId);
    t.setPayeeIdType(request.payee().idType());
    t.setPayeeIdValue(request.payee().idValue());
    t.setPayeeName(displayName(party));
    t.setPayeeAccountId(account.getId());
    t.setAmount(request.amount());
    t.setCurrency(request.currency());
    t.setTransferAmount(transferAmount);
    t.setPayeeReceiveAmount(receiveAmount);
    t.setPayeeFee(fee);
    t.setNote(request.note());
    t.setState(TransferState.QUOTED);
    t.setCreatedAt(now);
    t.setUpdatedAt(now);
    t.setExpiration(now.plus(config.transferExpiry()));
    return t;
  }

  private Transfer reserve(String fspId, TransferPrepare prepare) {
    Optional<Transfer> quote = Optional.empty();
    if (prepare.quoteId() != null) {
      quote = transferRepository.findByQuoteId(TransferDirection.INBOUND, prepare.quoteId());
    }
    Transfer t = quote.filter(q -> fspId.equals(q.getPayeeFspId()))
        .orElseThrow(() -> new BusinessException("3208", "No quote matches transfer " + prepare.transferId()));
    if (t.getState() != TransferState.QUOTED) {
      throw new BusinessException("3100", "Quote " + t.getQuoteId() + " is " + t.getState());
    }
    if (t.getTransferAmount().compareTo(prepare.amount()) != 0 || t.getCurrency() != prepare.currency()) {
      throw new BusinessException("3100", "Transfer amount does not match the quote");
    }
    if (t.getExpiration().isBefore(Instant.now())) {
      throw new BusinessException("3302", "Quote " + t.getQuoteId() + " expired");
    }

    Account position = accountRepository.findDfspAccount(fspId, AccountTypeEnum.POSITION)
        .orElseThrow(() -> new NotFoundException("Position account of " + fspId));
    Account settlement = accountRepository.findDfspAccount(fspId, AccountTypeEnum.SETTLEMENT)
        .orElseThrow(() -> new NotFoundException("Settlement account of " + fspId));
    t.setTransferId(prepare.transferId());
    List<Posting> postings = new ArrayList<>(List.of(
        posting(position.getId(), t.getTransferAmount().negate()),
        posting(t.getPayeeAccountId(), t.getPayeeReceiveAmount())));
    if (t.getPayeeFee().signum() > 0) {
      postings.add(posting(settlement.getId(), t.getPayeeFee()));
    }
    ledgerService.reserve(ledgerReference(t), EntryType.TRANSFER_IN, postings);
    t.setState(TransferState.RESERVED);
    t.setExpiration(Instant.now().plus(config.transferExpiry()));
    t.setUpdatedAt(Instant.now());
    return transferRepository.save(t);
  }

  private void finish(String fspId, String transferId, TransferState state, FspiopError error) {
    QuarkusTransaction.joiningExisting().run(() -> {
      var found = transferRepository.findByTransferId(TransferDirection.INBOUND, transferId)
          .filter(t -> fspId.equals(t.getPayeeFspId()));
      if (found.isEmpty()) {
        return;
      }
      Transfer t = found.get();
      if (state == TransferState.COMMITTED) {
        if (t.getState() != TransferState.RESERVED) {
          return;
        }
        ledgerService.commit(ledgerReference(t));
        t.setCompletedAt(Instant.now());
      } else if (t.getState() == TransferState.COMMITTED) {
        // the hub failed the transfer after the payee committed: undo the credit
        ledgerService.reverse(ledgerReference(t));
      } else if (t.getState().isFinal()) {
        return;
      } else {
        ledgerService.abort(ledgerReference(t));
      }
      t.setState(state);
      if (error != null) {
        t.setErrorCode(error.code());
        t.setErrorDescription(error.description());
      }
      t.setUpdatedAt(Instant.now());
      transferRepository.save(t);
    });
  }

  private Optional<Party> hostedParty(String fspId, IdTypeEnum idType, String idValue) {
    return partyRepository.findByIdentifier(idType, idValue)
        .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()));
  }

  private static String displayName(Party party) {
    Customer customer = party.getAccount().getCustomer();
    return customer != null ? customer.getName() : null;
  }

}
