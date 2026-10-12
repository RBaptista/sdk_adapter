package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "disbursement_items")
public class DisbursementItemEntity {

  @Id
  @GeneratedValue
  private Long id;

  @Column(name = "disbursement_id")
  private String disbursementId;

  @Column(name = "line_no")
  private int lineNo;

  @Column(name = "payee_id_type")
  private String payeeIdType;

  @Column(name = "payee_id_value")
  private String payeeIdValue;

  @Column(name = "amount")
  private BigDecimal amount;

  @Column(name = "note")
  private String note;

  @Column(name = "state")
  private String state;

  @Column(name = "home_transaction_id")
  private String homeTransactionId;

  @Column(name = "transfer_id")
  private String transferId;

  @Column(name = "payee_fsp_id")
  private String payeeFspId;

  @Column(name = "payee_name")
  private String payeeName;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "error_description")
  private String errorDescription;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
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

  public String getState() {
    return state;
  }

  public void setState(String state) {
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
