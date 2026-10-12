package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;

/**
 * User-facing G2P API of the logged-in (payer) DFSP. A batch is created, validated (a party lookup per
 * item), then approved; the scheduler does the lookups and pays each valid item as its own transfer.
 */
public interface DisbursementUseCase {
  Disbursement create(String fspId, CreateDisbursementCommand command);

  /** starts the party lookups; a VALIDATED batch can be validated again */
  Disbursement validate(String fspId, String disbursementId);

  /** queues the VALID items for payment; the payer's available balance must cover them */
  Disbursement approve(String fspId, String disbursementId);

  /** skips every item not yet sent; items already sent finish normally */
  Disbursement cancel(String fspId, String disbursementId);

  Disbursement findById(String fspId, String disbursementId);

  List<Disbursement> findAll(String fspId);

  /** the batch's items, all of them when {@code state} is null */
  List<DisbursementItem> items(String fspId, String disbursementId, DisbursementItemState state);
}
