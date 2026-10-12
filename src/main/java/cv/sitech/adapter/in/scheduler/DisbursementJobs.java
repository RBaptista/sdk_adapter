package cv.sitech.adapter.in.scheduler;

import cv.sitech.application.service.BulkDisbursementService;
import cv.sitech.application.service.DisbursementService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DisbursementJobs {

  private final DisbursementService disbursementService;
  private final BulkDisbursementService bulkDisbursementService;

  public DisbursementJobs(DisbursementService disbursementService, BulkDisbursementService bulkDisbursementService) {
    this.disbursementService = disbursementService;
    this.bulkDisbursementService = bulkDisbursementService;
  }

  /** one pass over every batch; a run that takes longer than the interval delays the next one */
  @Scheduled(every = "${simulator.disbursement.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void process() {
    disbursementService.validatePending();
    disbursementService.executeQueued();
    bulkDisbursementService.timeouts();
    disbursementService.sync();
  }

}
