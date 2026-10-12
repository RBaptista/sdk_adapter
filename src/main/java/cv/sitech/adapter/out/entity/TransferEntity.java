package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class TransferEntity {

  @Id
  @GeneratedValue
  private Long id;

  @Column(name = "direction")
  private String direction;

  @Column(name = "transfer_id")
  private String transferId;

  @Column(name = "home_transaction_id")
  private String homeTransactionId;

  @Column(name = "quote_id")
  private String quoteId;

  @Column(name = "transaction_id")
  private String transactionId;

  @Column(name = "payer_fsp_id")
  private String payerFspId;

  @Column(name = "payer_id_type")
  private String payerIdType;

  @Column(name = "payer_id_value")
  private String payerIdValue;

  @Column(name = "payer_account_id")
  private Long payerAccountId;

  @Column(name = "payee_fsp_id")
  private String payeeFspId;

  @Column(name = "payee_id_type")
  private String payeeIdType;

  @Column(name = "payee_id_value")
  private String payeeIdValue;

  @Column(name = "payee_name")
  private String payeeName;

  @Column(name = "payee_account_id")
  private Long payeeAccountId;

  @Column(name = "amount")
  private BigDecimal amount;

  @Column(name = "currency")
  private String currency;

  @Column(name = "transfer_amount")
  private BigDecimal transferAmount;

  @Column(name = "payee_receive_amount")
  private BigDecimal payeeReceiveAmount;

  @Column(name = "payee_fee")
  private BigDecimal payeeFee;

  @Column(name = "ilp_packet")
  private String ilpPacket;

  @Column(name = "ilp_condition")
  private String ilpCondition;

  @Column(name = "fulfilment")
  private String fulfilment;

  @Column(name = "expiration")
  private Instant expiration;

  @Column(name = "state")
  private String state;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "error_description")
  private String errorDescription;

  @Column(name = "auto_accept")
  private boolean autoAccept;

  @Column(name = "note")
  private String note;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(String direction) {
    this.direction = direction;
  }

  public String getTransferId() {
    return transferId;
  }

  public void setTransferId(String transferId) {
    this.transferId = transferId;
  }

  public String getHomeTransactionId() {
    return homeTransactionId;
  }

  public void setHomeTransactionId(String homeTransactionId) {
    this.homeTransactionId = homeTransactionId;
  }

  public String getQuoteId() {
    return quoteId;
  }

  public void setQuoteId(String quoteId) {
    this.quoteId = quoteId;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public void setTransactionId(String transactionId) {
    this.transactionId = transactionId;
  }

  public String getPayerFspId() {
    return payerFspId;
  }

  public void setPayerFspId(String payerFspId) {
    this.payerFspId = payerFspId;
  }

  public String getPayerIdType() {
    return payerIdType;
  }

  public void setPayerIdType(String payerIdType) {
    this.payerIdType = payerIdType;
  }

  public String getPayerIdValue() {
    return payerIdValue;
  }

  public void setPayerIdValue(String payerIdValue) {
    this.payerIdValue = payerIdValue;
  }

  public Long getPayerAccountId() {
    return payerAccountId;
  }

  public void setPayerAccountId(Long payerAccountId) {
    this.payerAccountId = payerAccountId;
  }

  public String getPayeeFspId() {
    return payeeFspId;
  }

  public void setPayeeFspId(String payeeFspId) {
    this.payeeFspId = payeeFspId;
  }

  public String getPayeeIdType() {
    return payeeIdType;
  }

  public void setPayeeIdType(String payeeIdType) {
    this.payeeIdType = payeeIdType;
  }

  public String getPayeeIdValue() {
    return payeeIdValue;
  }

  public void setPayeeIdValue(String payeeIdValue) {
    this.payeeIdValue = payeeIdValue;
  }

  public String getPayeeName() {
    return payeeName;
  }

  public void setPayeeName(String payeeName) {
    this.payeeName = payeeName;
  }

  public Long getPayeeAccountId() {
    return payeeAccountId;
  }

  public void setPayeeAccountId(Long payeeAccountId) {
    this.payeeAccountId = payeeAccountId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getTransferAmount() {
    return transferAmount;
  }

  public void setTransferAmount(BigDecimal transferAmount) {
    this.transferAmount = transferAmount;
  }

  public BigDecimal getPayeeReceiveAmount() {
    return payeeReceiveAmount;
  }

  public void setPayeeReceiveAmount(BigDecimal payeeReceiveAmount) {
    this.payeeReceiveAmount = payeeReceiveAmount;
  }

  public BigDecimal getPayeeFee() {
    return payeeFee;
  }

  public void setPayeeFee(BigDecimal payeeFee) {
    this.payeeFee = payeeFee;
  }

  public String getIlpPacket() {
    return ilpPacket;
  }

  public void setIlpPacket(String ilpPacket) {
    this.ilpPacket = ilpPacket;
  }

  public String getIlpCondition() {
    return ilpCondition;
  }

  public void setIlpCondition(String ilpCondition) {
    this.ilpCondition = ilpCondition;
  }

  public String getFulfilment() {
    return fulfilment;
  }

  public void setFulfilment(String fulfilment) {
    this.fulfilment = fulfilment;
  }

  public Instant getExpiration() {
    return expiration;
  }

  public void setExpiration(Instant expiration) {
    this.expiration = expiration;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getErrorDescription() {
    return errorDescription;
  }

  public void setErrorDescription(String errorDescription) {
    this.errorDescription = errorDescription;
  }

  public boolean isAutoAccept() {
    return autoAccept;
  }

  public void setAutoAccept(boolean autoAccept) {
    this.autoAccept = autoAccept;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

}
