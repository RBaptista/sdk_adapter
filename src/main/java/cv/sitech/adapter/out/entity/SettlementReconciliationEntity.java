package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "settlement_reconciliations")
public class SettlementReconciliationEntity {

  @Id
  @GeneratedValue
  private Long id;

  @Column(name = "kind")
  private String kind;

  @Column(name = "hub_reference")
  private String hubReference;

  @Column(name = "fsp_id")
  private String fspId;

  @Column(name = "currency")
  private String currency;

  @Column(name = "hub_amount")
  private BigDecimal hubAmount;

  @Column(name = "internal_amount")
  private BigDecimal internalAmount;

  @Column(name = "status")
  private String status;

  @Column(name = "hub_state")
  private String hubState;

  @Column(name = "posted_to_ledger")
  private boolean postedToLedger;

  @Column(name = "reconciled_at")
  private Instant reconciledAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getHubReference() {
    return hubReference;
  }

  public void setHubReference(String hubReference) {
    this.hubReference = hubReference;
  }

  public String getFspId() {
    return fspId;
  }

  public void setFspId(String fspId) {
    this.fspId = fspId;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getHubAmount() {
    return hubAmount;
  }

  public void setHubAmount(BigDecimal hubAmount) {
    this.hubAmount = hubAmount;
  }

  public BigDecimal getInternalAmount() {
    return internalAmount;
  }

  public void setInternalAmount(BigDecimal internalAmount) {
    this.internalAmount = internalAmount;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getHubState() {
    return hubState;
  }

  public void setHubState(String hubState) {
    this.hubState = hubState;
  }

  public boolean isPostedToLedger() {
    return postedToLedger;
  }

  public void setPostedToLedger(boolean postedToLedger) {
    this.postedToLedger = postedToLedger;
  }

  public Instant getReconciledAt() {
    return reconciledAt;
  }

  public void setReconciledAt(Instant reconciledAt) {
    this.reconciledAt = reconciledAt;
  }

}
