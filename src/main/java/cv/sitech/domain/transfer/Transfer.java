package cv.sitech.domain.transfer;

import java.math.BigDecimal;
import java.time.Instant;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;

public class Transfer {
  private Long id;
  private TransferDirection direction;
  private String transferId;
  /** this app's reference, sent to the SDK as homeTransactionId; the SDK assigns the transferId */
  private String homeTransactionId;
  private String quoteId;
  private String transactionId;
  private String payerFspId;
  private IdTypeEnum payerIdType;
  private String payerIdValue;
  private Long payerAccountId;
  private String payeeFspId;
  private IdTypeEnum payeeIdType;
  private String payeeIdValue;
  private String payeeName;
  private Long payeeAccountId;
  private BigDecimal amount;
  private CurrencyEnum currency;
  private BigDecimal transferAmount;
  private BigDecimal payeeReceiveAmount;
  private BigDecimal payeeFee;
  private String ilpPacket;
  private String ilpCondition;
  private String fulfilment;
  private Instant expiration;
  private TransferState state;
  private String errorCode;
  private String errorDescription;
  private boolean autoAccept;
  private String note;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant completedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public TransferDirection getDirection() {
    return direction;
  }

  public void setDirection(TransferDirection direction) {
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

  public IdTypeEnum getPayerIdType() {
    return payerIdType;
  }

  public void setPayerIdType(IdTypeEnum payerIdType) {
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

  public IdTypeEnum getPayeeIdType() {
    return payeeIdType;
  }

  public void setPayeeIdType(IdTypeEnum payeeIdType) {
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

  public CurrencyEnum getCurrency() {
    return currency;
  }

  public void setCurrency(CurrencyEnum currency) {
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

  public TransferState getState() {
    return state;
  }

  public void setState(TransferState state) {
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
