package cv.sitech.domain.settlement;

public enum ReconciliationKind {
  /** current hub position vs inner-ledger POSITION balance */
  POSITION,
  /** a hub settlement's net amount vs the inner ledger's net for the settled windows */
  SETTLEMENT
}
