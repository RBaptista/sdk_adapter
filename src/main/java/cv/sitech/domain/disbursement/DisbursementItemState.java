package cv.sitech.domain.disbursement;

public enum DisbursementItemState {
  // validation (party lookup)
  PENDING,
  VALID,
  INVALID,
  // execution
  QUEUED,
  /** BULK: party accepted, waiting for the bulk quote */
  QUOTING,
  SUBMITTED,
  COMMITTED,
  FAILED,
  SKIPPED;

  public boolean isFinal() {
    return this == INVALID || this == COMMITTED || this == FAILED || this == SKIPPED;
  }
}
