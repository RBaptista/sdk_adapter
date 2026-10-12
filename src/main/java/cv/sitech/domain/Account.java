package cv.sitech.domain;

import java.math.BigDecimal;

public class Account {
  private Long id;
  private AccountTypeEnum type;
  private Customer customer;
  private Dfsp dfsp;
  // maintained by the ledger only
  private BigDecimal balance = BigDecimal.ZERO;
  private BigDecimal reservedBalance = BigDecimal.ZERO;
  private BigDecimal pendingCredit = BigDecimal.ZERO;

  public Account(AccountTypeEnum type, Customer customer, Dfsp dfsp) {
    this.type = type;
    this.customer = customer;
    this.dfsp = dfsp;
  }

  /** balance minus outgoing amounts held by reservations */
  public BigDecimal availableBalance() {
    return balance.subtract(reservedBalance);
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public AccountTypeEnum getType() {
    return type;
  }

  public void setType(AccountTypeEnum type) {
    this.type = type;
  }

  public Customer getCustomer() {
    return customer;
  }

  public void setCustomer(Customer customer) {
    this.customer = customer;
  }

  public Dfsp getDfsp() {
    return dfsp;
  }

  public void setDfsp(Dfsp dfsp) {
    this.dfsp = dfsp;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public void setBalance(BigDecimal balance) {
    this.balance = balance;
  }

  public BigDecimal getReservedBalance() {
    return reservedBalance;
  }

  public void setReservedBalance(BigDecimal reservedBalance) {
    this.reservedBalance = reservedBalance;
  }

  public BigDecimal getPendingCredit() {
    return pendingCredit;
  }

  public void setPendingCredit(BigDecimal pendingCredit) {
    this.pendingCredit = pendingCredit;
  }

}
