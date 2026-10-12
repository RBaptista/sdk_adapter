package cv.sitech.domain.disbursement;

import java.math.BigDecimal;
import java.time.Instant;

import cv.sitech.domain.IdTypeEnum;

/** One payment of a disbursement, executed as an OUTBOUND transfer linked by {@code homeTransactionId}. */
public class DisbursementItem {
  private Long id;
  private String disbursementId;
  private int lineNo;
  /** the payee is at another DFSP, so only its identifier is known here */
  private IdTypeEnum payeeIdType;
  private String payeeIdValue;
  private BigDecimal amount;
  private String note;
  private DisbursementItemState state;
  /** generated with the item and passed to the transfer, so a retry never pays twice */
  private String homeTransactionId;
  /** the SDK's transfer id, copied from the transfer once discovery returns */
  private String transferId;
  /** set by validation (party lookup) */
  private String payeeFspId;
  private String payeeName;
  private String errorCode;
  private String errorDescription;
  private Instant createdAt;
  private Instant updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getDisbursementId() {
    return disbursementId;
  }

  public void setDisbursementId(String disbursementId) {
    this.disbursementId = disbursementId;
  }

  public int getLineNo() {
    return lineNo;
  }

  public void setLineNo(int lineNo) {
    this.lineNo = lineNo;
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

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public DisbursementItemState getState() {
    return state;
  }

  public void setState(DisbursementItemState state) {
    this.state = state;
  }

  public String getHomeTransactionId() {
    return homeTransactionId;
  }

  public void setHomeTransactionId(String homeTransactionId) {
    this.homeTransactionId = homeTransactionId;
  }

  public String getTransferId() {
    return transferId;
  }

  public void setTransferId(String transferId) {
    this.transferId = transferId;
  }

  public String getPayeeFspId() {
    return payeeFspId;
  }

  public void setPayeeFspId(String payeeFspId) {
    this.payeeFspId = payeeFspId;
  }

  public String getPayeeName() {
    return payeeName;
  }

  public void setPayeeName(String payeeName) {
    this.payeeName = payeeName;
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

}
