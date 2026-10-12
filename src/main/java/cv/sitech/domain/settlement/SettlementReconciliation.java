package cv.sitech.domain.settlement;

import java.math.BigDecimal;
import java.time.Instant;

public class SettlementReconciliation {
  private Long id;
  private ReconciliationKind kind;
  private String hubReference;
  private String fspId;
  private String currency;
  private BigDecimal hubAmount;
  private BigDecimal internalAmount;
  private ReconciliationStatus status;
  private String hubState;
  private boolean postedToLedger;
  private Instant reconciledAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public ReconciliationKind getKind() {
    return kind;
  }

  public void setKind(ReconciliationKind kind) {
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

  public ReconciliationStatus getStatus() {
    return status;
  }

  public void setStatus(ReconciliationStatus status) {
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
