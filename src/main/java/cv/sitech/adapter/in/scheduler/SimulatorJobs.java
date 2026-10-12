package cv.sitech.adapter.in.scheduler;

import java.time.Instant;
import java.util.List;

import cv.sitech.application.port.in.SettlementUseCase;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.application.service.PayeeService;
import cv.sitech.application.service.PayerService;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SimulatorJobs {

  private final TransferRepository transferRepository;
  private final PayerService payerService;
  private final PayeeService payeeService;
  private final SettlementUseCase settlementUseCase;
  private final SimulatorConfig config;

  public SimulatorJobs(TransferRepository transferRepository, PayerService payerService, PayeeService payeeService,
      SettlementUseCase settlementUseCase, SimulatorConfig config) {
    this.transferRepository = transferRepository;
    this.payerService = payerService;
    this.payeeService = payeeService;
    this.settlementUseCase = settlementUseCase;
    this.config = config;
  }

  @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void expireTransfers() {
    Instant now = Instant.now();
    List<Transfer> expired = QuarkusTransaction.joiningExisting().call(() -> transferRepository.findExpired(now));
    for (Transfer t : expired) {
      // a reserved transfer is the hub's to time out; give its error callback time to arrive
      if (t.getState() == TransferState.RESERVED && t.getExpiration().plus(config.callbackTimeout()).isAfter(now)) {
        continue;
      }
      if (t.getDirection() == TransferDirection.OUTBOUND) {
        payerService.expire(t);
      } else {
        payeeService.expire(t);
      }
    }
  }

  @Scheduled(every = "${simulator.settlement.reconcile-every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void reconcileSettlements() {
    settlementUseCase.reconcile();
  }

}
