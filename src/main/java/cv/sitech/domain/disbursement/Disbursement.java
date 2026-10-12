package cv.sitech.domain.disbursement;

import java.math.BigDecimal;
import java.time.Instant;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;

/** A G2P batch: one payer party of a hosted DFSP paying many payees, each item as its own transfer. */
public class Disbursement {
  private Long id;
  private String disbursementId;
  private String payerFspId;
  private IdTypeEnum payerIdType;
  private String payerIdValue;
  private Long payerAccountId;
  private CurrencyEnum currency;
  private String reference;
  private String note;
  private DisbursementState state;
  private DisbursementMode mode;
  private int itemCount;
  private BigDecimal totalAmount;
  private int committedCount;
  private BigDecimal committedAmount;
  private int failedCount;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant approvedAt;
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

  public CurrencyEnum getCurrency() {
    return currency;
  }

  public void setCurrency(CurrencyEnum currency) {
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

  public DisbursementState getState() {
    return state;
  }

  public void setState(DisbursementState state) {
    this.state = state;
  }

  public DisbursementMode getMode() {
    return mode;
  }

  public void setMode(DisbursementMode mode) {
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
