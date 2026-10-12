package cv.sitech.domain.ledger;

/** RESERVED entries only hold funds; balances move when the entry is COMMITTED. */
public enum EntryStatus {
  RESERVED,
  COMMITTED,
  ABORTED
}
