package cv.sitech.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.SettlementReconciliationEntity;
import cv.sitech.adapter.out.persistence.mapper.SettlementReconciliationMapper;
import cv.sitech.application.port.out.SettlementReconciliationRepository;
import cv.sitech.domain.settlement.ReconciliationKind;
import cv.sitech.domain.settlement.SettlementReconciliation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SettlementReconciliationPersistenceAdapter
    extends PanacheAdapter<SettlementReconciliation, SettlementReconciliationEntity, Long>
    implements SettlementReconciliationRepository {

  protected SettlementReconciliationPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public SettlementReconciliationPersistenceAdapter(SettlementReconciliationPanacheRepository panache,
      SettlementReconciliationMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<SettlementReconciliation> findSettlement(String hubSettlementId, String fspId) {
    return first("kind = ?1 and hubReference = ?2 and fspId = ?3", ReconciliationKind.SETTLEMENT.name(),
        hubSettlementId, fspId);
  }

  @Override
  public List<SettlementReconciliation> findByFsp(String fspId) {
    return list("fspId = ?1 order by reconciledAt desc", fspId);
  }

}
