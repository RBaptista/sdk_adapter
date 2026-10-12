package cv.sitech.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;

public interface DisbursementItemRepository {
  DisbursementItem save(DisbursementItem item);

  Optional<DisbursementItem> findByHomeTransactionId(String homeTransactionId);

  /** loads the item with a row lock held until the current transaction ends */
  Optional<DisbursementItem> findByIdForUpdate(Long id);

  /** ordered by line number */
  List<DisbursementItem> findByDisbursementId(String disbursementId);

  /** ordered by line number */
  List<DisbursementItem> findByDisbursementId(String disbursementId, DisbursementItemState state);

  /** items in {@code state} not updated since {@code before}, e.g. SUBMITTED items for the sync job */
  List<DisbursementItem> findByStateUpdatedBefore(DisbursementItemState state, Instant before);

  /**
   * Locks up to {@code limit} QUEUED items, skipping rows another worker holds. Must run in a transaction
   * that marks them SUBMITTED before it commits.
   */
  List<DisbursementItem> lockQueued(int limit);

  /**
   * Locks the batch's items in {@code states}, waiting for a worker that holds some of them; an item the
   * worker moved out of {@code states} meanwhile is not returned. Ordered by line number.
   */
  List<DisbursementItem> lockByDisbursementId(String disbursementId, List<DisbursementItemState> states);
}
