package cv.sitech.domain.disbursement;

public enum DisbursementState {
  CREATED,
  VALIDATING,
  VALIDATED,
  EXECUTING,
  COMPLETED,
  PARTIALLY_COMPLETED,
  FAILED,
  CANCELLED;

  public boolean isFinal() {
    return this == COMPLETED || this == PARTIALLY_COMPLETED || this == FAILED || this == CANCELLED;
  }
}
