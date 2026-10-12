package cv.sitech.domain.transfer;

public enum TransferState {
  // OUTBOUND (payer)
  WAITING_FOR_PARTY_INFO,
  WAITING_FOR_PARTY_ACCEPTANCE,
  WAITING_FOR_QUOTE,
  WAITING_FOR_QUOTE_ACCEPTANCE,
  // INBOUND (payee)
  QUOTED,
  // both
  RESERVED,
  COMMITTED,
  ABORTED,
  EXPIRED;

  public boolean isFinal() {
    return this == COMMITTED || this == ABORTED || this == EXPIRED;
  }
}
