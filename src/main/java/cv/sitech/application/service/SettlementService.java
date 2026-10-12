package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.SettlementUseCase;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.application.port.out.HubSettlementGateway;
import cv.sitech.application.port.out.HubSettlementGateway.HubNet;
import cv.sitech.application.port.out.HubSettlementGateway.HubSettlement;
import cv.sitech.application.port.out.HubSettlementGateway.HubWindow;
import cv.sitech.application.port.out.SettlementReconciliationRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.settlement.ReconciliationKind;
import cv.sitech.domain.settlement.ReconciliationStatus;
import cv.sitech.domain.settlement.SettlementReconciliation;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

/**
 * Reconciles the inner ledger with the hub, which runs the settlement process itself. Positions are
 * compared as they stand now; each hub settlement's net amount is compared with the net of the
 * transfers committed during its windows. Once the hub marks a settlement SETTLED, it is booked
 * between the DFSP's POSITION and SETTLEMENT accounts.
 */
@ApplicationScoped
public class SettlementService implements SettlementUseCase {

  private static final Logger LOG = Logger.getLogger(SettlementService.class);
  private static final Duration LOOKBACK = Duration.ofDays(7);

  private final DfspRepository dfspRepository;
  private final AccountRepository accountRepository;
  private final TransferRepository transferRepository;
  private final SettlementReconciliationRepository reconciliationRepository;
  private final HubSettlementGateway hub;
  private final LedgerService ledgerService;

  public SettlementService(DfspRepository dfspRepository, AccountRepository accountRepository,
      TransferRepository transferRepository, SettlementReconciliationRepository reconciliationRepository,
      HubSettlementGateway hub, LedgerService ledgerService) {
    this.dfspRepository = dfspRepository;
    this.accountRepository = accountRepository;
    this.transferRepository = transferRepository;
    this.reconciliationRepository = reconciliationRepository;
    this.hub = hub;
    this.ledgerService = ledgerService;
  }

  @Override
  @ActivateRequestContext
  public List<SettlementReconciliation> reconcile() {
    Map<String, Dfsp> hosted = QuarkusTransaction.joiningExisting().call(() -> dfspRepository.findAll()).stream()
        .collect(Collectors.toMap(Dfsp::getFspId, Function.identity()));
    List<SettlementReconciliation> results = new ArrayList<>();
    Instant now = Instant.now();

    for (Dfsp dfsp : hosted.values()) {
      try {
        hub.positions(dfsp.getFspId()).stream()
            .filter(p -> p.currency().equals(dfsp.getCurrency().name()))
            .findFirst()
            .ifPresent(p -> results.add(QuarkusTransaction.joiningExisting().call(() -> {
              BigDecimal internal = account(dfsp.getFspId(), AccountTypeEnum.POSITION).getBalance();
              return reconciliationRepository.save(row(ReconciliationKind.POSITION, now.toString(),
                  dfsp.getFspId(), p.currency(), p.value(), internal, null, now));
            })));
      } catch (RuntimeException e) {
        LOG.warnf("Position reconciliation of %s failed: %s", dfsp.getFspId(), e.getMessage());
      }
    }

    List<HubSettlement> settlements;
    try {
      settlements = hub.settlementsSince(now.minus(LOOKBACK));
    } catch (RuntimeException e) {
      LOG.warnf("Could not read hub settlements: %s", e.getMessage());
      return results;
    }
    for (HubSettlement settlement : settlements) {
      for (HubNet net : settlement.nets()) {
        if (hosted.containsKey(net.fspId())) {
          results.add(QuarkusTransaction.joiningExisting().call(() -> reconcile(settlement, net, now)));
        }
      }
    }
    return results;
  }

  @Override
  public List<SettlementReconciliation> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> reconciliationRepository.findByFsp(fspId));
  }

  private SettlementReconciliation reconcile(HubSettlement settlement, HubNet net, Instant now) {
    BigDecimal internal = BigDecimal.ZERO;
    for (HubWindow window : settlement.windows()) {
      Instant to = window.closedAt() != null ? window.closedAt() : now;
      for (Transfer t : transferRepository.findCommitted(net.fspId(), window.openedAt(), to)) {
        // sending raises the payer's net debit, receiving lowers the payee's
        internal = t.getDirection() == TransferDirection.OUTBOUND ? internal.add(t.getTransferAmount())
            : internal.subtract(t.getTransferAmount());
      }
    }
    SettlementReconciliation row = reconciliationRepository.findSettlement(settlement.id(), net.fspId())
        .orElseGet(SettlementReconciliation::new);
    SettlementReconciliation updated = row(ReconciliationKind.SETTLEMENT, settlement.id(), net.fspId(),
        net.currency(), net.amount(), internal, settlement.state(), now);
    updated.setId(row.getId());
    updated.setPostedToLedger(row.isPostedToLedger());

    if ("SETTLED".equals(settlement.state()) && !updated.isPostedToLedger() && net.amount().signum() != 0) {
      ledgerService.post("SETTLEMENT:" + settlement.id() + ":" + net.fspId(), EntryType.SETTLEMENT, List.of(
          posting(account(net.fspId(), AccountTypeEnum.POSITION).getId(), net.amount().negate()),
          posting(account(net.fspId(), AccountTypeEnum.SETTLEMENT).getId(), net.amount())));
      updated.setPostedToLedger(true);
    }
    return reconciliationRepository.save(updated);
  }

  private Account account(String fspId, AccountTypeEnum type) {
    return accountRepository.findDfspAccount(fspId, type)
        .orElseThrow(() -> new NotFoundException(type + " account of " + fspId + " not Found"));
  }

  private static SettlementReconciliation row(ReconciliationKind kind, String reference, String fspId,
      String currency, BigDecimal hubAmount, BigDecimal internalAmount, String hubState, Instant now) {
    SettlementReconciliation r = new SettlementReconciliation();
    r.setKind(kind);
    r.setHubReference(reference);
    r.setFspId(fspId);
    r.setCurrency(currency);
    r.setHubAmount(hubAmount);
    r.setInternalAmount(internalAmount);
    r.setStatus(hubAmount.compareTo(internalAmount) == 0 ? ReconciliationStatus.MATCH : ReconciliationStatus.MISMATCH);
    r.setHubState(hubState);
    r.setReconciledAt(now);
    return r;
  }

}
