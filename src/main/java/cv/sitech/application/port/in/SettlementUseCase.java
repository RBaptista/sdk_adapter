package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.settlement.SettlementReconciliation;

public interface SettlementUseCase {
  /** compares hub positions and settlements with the inner ledger for every hosted DFSP */
  List<SettlementReconciliation> reconcile();

  List<SettlementReconciliation> findAll(String fspId);
}
