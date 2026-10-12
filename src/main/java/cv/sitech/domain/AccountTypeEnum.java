package cv.sitech.domain;

public enum AccountTypeEnum {
  /** a customer's wallet; balance is what the DFSP owes the customer */
  CUSTOMER,
  /** the DFSP's net debit position towards the hub (positive = DFSP owes the hub) */
  POSITION,
  /** the DFSP's own funds: deposits, fees and settled amounts are booked against it */
  SETTLEMENT
}
