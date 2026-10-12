package cv.sitech.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.DisbursementEntity;
import cv.sitech.adapter.out.persistence.mapper.DisbursementMapper;
import cv.sitech.application.port.out.DisbursementRepository;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

@ApplicationScoped
public class DisbursementPersistenceAdapter extends PanacheAdapter<Disbursement, DisbursementEntity, Long>
    implements DisbursementRepository {

  protected DisbursementPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public DisbursementPersistenceAdapter(DisbursementPanacheRepository panache, DisbursementMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<Disbursement> findByDisbursementId(String disbursementId) {
    return first("disbursementId", disbursementId);
  }

  @Override
  public Optional<Disbursement> findByDisbursementIdForUpdate(String disbursementId) {
    return panache.find("disbursementId", disbursementId).withLock(LockModeType.PESSIMISTIC_WRITE)
        .firstResultOptional().map(mapper::toDomain);
  }

  @Override
  public List<Disbursement> findByPayer(String payerFspId) {
    return list("payerFspId = ?1 order by createdAt desc", payerFspId);
  }

  @Override
  public List<Disbursement> findByState(DisbursementState state) {
    return list("state", state.name());
  }

}
