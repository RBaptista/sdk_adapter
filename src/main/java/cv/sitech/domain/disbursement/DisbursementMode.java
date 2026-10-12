package cv.sitech.domain.disbursement;

/** how a batch reaches the hub */
public enum DisbursementMode {
  /** each item is its own transfer: party lookup, quote and transfer per item */
  INDIVIDUAL,
  /** the SDK's /bulkTransactions: a party lookup per item, then one bulk quote and one bulk transfer per payee DFSP */
  BULK
}
