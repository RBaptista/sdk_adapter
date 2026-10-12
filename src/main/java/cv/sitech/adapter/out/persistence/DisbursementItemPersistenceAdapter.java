package cv.sitech.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.DisbursementItemEntity;
import cv.sitech.adapter.out.persistence.mapper.DisbursementItemMapper;
import cv.sitech.application.port.out.DisbursementItemRepository;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

@ApplicationScoped
public class DisbursementItemPersistenceAdapter extends PanacheAdapter<DisbursementItem, DisbursementItemEntity, Long>
    implements DisbursementItemRepository {

  /** Hibernate's LockOptions.SKIP_LOCKED: PESSIMISTIC_WRITE becomes "for update skip locked" on PostgreSQL */
  private static final int SKIP_LOCKED = -2;

  protected DisbursementItemPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public DisbursementItemPersistenceAdapter(DisbursementItemPanacheRepository panache, DisbursementItemMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<DisbursementItem> findByHomeTransactionId(String homeTransactionId) {
    return first("homeTransactionId", homeTransactionId);
  }

  @Override
  public Optional<DisbursementItem> findByIdForUpdate(Long id) {
    return panache.findByIdOptional(id, LockModeType.PESSIMISTIC_WRITE).map(mapper::toDomain);
  }

  @Override
  public List<DisbursementItem> findByDisbursementId(String disbursementId) {
    return list("disbursementId = ?1 order by lineNo", disbursementId);
  }

  @Override
  public List<DisbursementItem> findByDisbursementId(String disbursementId, DisbursementItemState state) {
    return list("disbursementId = ?1 and state = ?2 order by lineNo", disbursementId, state.name());
  }

  @Override
  public List<DisbursementItem> findByStateUpdatedBefore(DisbursementItemState state, Instant before) {
    return list("state = ?1 and updatedAt < ?2 order by id", state.name(), before);
  }

  @Override
  public List<DisbursementItem> lockQueued(int limit) {
    return panache.find("state = ?1 order by id", DisbursementItemState.QUEUED.name())
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .withHint("jakarta.persistence.lock.timeout", SKIP_LOCKED)
        .page(0, limit)
        .list().stream().map(mapper::toDomain).toList();
  }

  @Override
  public List<DisbursementItem> lockByDisbursementId(String disbursementId, List<DisbursementItemState> states) {
    return panache.find("disbursementId = ?1 and state in ?2 order by lineNo", disbursementId,
        states.stream().map(Enum::name).toList())
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .list().stream().map(mapper::toDomain).toList();
  }

}
