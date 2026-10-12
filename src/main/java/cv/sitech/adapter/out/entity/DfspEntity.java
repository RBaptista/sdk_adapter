package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dfsp")
public class DfspEntity {

  @Id
  @Column(name = "fsp_id", nullable = false, length = 32)
  private String fspId;

  @Column(name = "display_name")
  private String displayName;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "sdk_inbound_url", nullable = false)
  private String sdkInboundUrl;

  @Column(name = "sdk_outbound_url", nullable = false)
  private String sdkOutboundUrl;

  @Column(name = "net_debit_cap", nullable = false)
  private BigDecimal netDebitCap;

  @Column(name = "initial_position", nullable = false)
  private BigDecimal initialPosition;

  @Column(name = "initial_funds", nullable = false)
  private BigDecimal initialFunds;

  public String getFspId() {
    return fspId;
  }

  public void setFspId(String fspId) {
    this.fspId = fspId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getSdkInboundUrl() {
    return sdkInboundUrl;
  }

  public void setSdkInboundUrl(String sdkInboundUrl) {
    this.sdkInboundUrl = sdkInboundUrl;
  }

  public String getSdkOutboundUrl() {
    return sdkOutboundUrl;
  }

  public void setSdkOutboundUrl(String sdkOutboundUrl) {
    this.sdkOutboundUrl = sdkOutboundUrl;
  }

  public BigDecimal getNetDebitCap() {
    return netDebitCap;
  }

  public void setNetDebitCap(BigDecimal netDebitCap) {
    this.netDebitCap = netDebitCap;
  }

  public BigDecimal getInitialPosition() {
    return initialPosition;
  }

  public void setInitialPosition(BigDecimal initialPosition) {
    this.initialPosition = initialPosition;
  }

  public BigDecimal getInitialFunds() {
    return initialFunds;
  }

  public void setInitialFunds(BigDecimal initialFunds) {
    this.initialFunds = initialFunds;
  }

}
