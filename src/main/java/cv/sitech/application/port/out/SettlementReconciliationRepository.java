package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.settlement.SettlementReconciliation;

public interface SettlementReconciliationRepository {
  SettlementReconciliation save(SettlementReconciliation reconciliation);

  Optional<SettlementReconciliation> findSettlement(String hubSettlementId, String fspId);

  List<SettlementReconciliation> findByFsp(String fspId);
}
