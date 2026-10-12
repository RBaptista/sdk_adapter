package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "disbursements")
public class DisbursementEntity {

  @Id
  @GeneratedValue
  private Long id;

  @Column(name = "disbursement_id")
  private String disbursementId;

  @Column(name = "payer_fsp_id")
  private String payerFspId;

  @Column(name = "payer_id_type")
  private String payerIdType;

  @Column(name = "payer_id_value")
  private String payerIdValue;

  @Column(name = "payer_account_id")
  private Long payerAccountId;

  @Column(name = "currency")
  private String currency;

  @Column(name = "reference")
  private String reference;

  @Column(name = "note")
  private String note;

  @Column(name = "state")
  private String state;

  @Column(name = "mode")
  private String mode;

  @Column(name = "item_count")
  private int itemCount;

  @Column(name = "total_amount")
  private BigDecimal totalAmount;

  @Column(name = "committed_count")
  private int committedCount;

  @Column(name = "committed_amount")
  private BigDecimal committedAmount;

  @Column(name = "failed_count")
  private int failedCount;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

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

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
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

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public int getItemCount() {
    return itemCount;
  }

  public void setItemCount(int itemCount) {
    this.itemCount = itemCount;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public int getCommittedCount() {
    return committedCount;
  }

  public void setCommittedCount(int committedCount) {
    this.committedCount = committedCount;
  }

  public BigDecimal getCommittedAmount() {
    return committedAmount;
  }

  public void setCommittedAmount(BigDecimal committedAmount) {
    this.committedAmount = committedAmount;
  }

  public int getFailedCount() {
    return failedCount;
  }

  public void setFailedCount(int failedCount) {
    this.failedCount = failedCount;
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

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(Instant approvedAt) {
    this.approvedAt = approvedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

}
