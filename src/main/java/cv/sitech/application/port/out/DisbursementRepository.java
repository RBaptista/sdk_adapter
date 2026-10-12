package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementState;

public interface DisbursementRepository {
  Disbursement save(Disbursement disbursement);

  Optional<Disbursement> findByDisbursementId(String disbursementId);

  /** loads the batch with a row lock held until the current transaction ends, to serialize state changes */
  Optional<Disbursement> findByDisbursementIdForUpdate(String disbursementId);

  /** newest first */
  List<Disbursement> findByPayer(String payerFspId);

  /** used by the sync job to close EXECUTING batches */
  List<Disbursement> findByState(DisbursementState state);
}
