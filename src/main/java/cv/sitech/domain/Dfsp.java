package cv.sitech.domain;

import java.math.BigDecimal;

public class Dfsp {
  private String fspId;
  private String displayName;
  private CurrencyEnum currency;
  /** the SDK inbound API as the hub reaches it; registered as the DFSP's callback endpoints */
  private String sdkInboundUrl;
  /** the SDK outbound API as this app reaches it */
  private String sdkOutboundUrl;
  private BigDecimal netDebitCap;
  private BigDecimal initialPosition;
  private BigDecimal initialFunds;

  public Dfsp(String fspId, String displayName, CurrencyEnum currency, String sdkInboundUrl,
      String sdkOutboundUrl, BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
    this.fspId = fspId;
    this.displayName = displayName;
    this.currency = currency;
    this.sdkInboundUrl = sdkInboundUrl;
    this.sdkOutboundUrl = sdkOutboundUrl;
    this.netDebitCap = netDebitCap != null ? netDebitCap : BigDecimal.ZERO;
    this.initialPosition = initialPosition != null ? initialPosition : BigDecimal.ZERO;
    this.initialFunds = initialFunds != null ? initialFunds : BigDecimal.ZERO;
  }

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

  public CurrencyEnum getCurrency() {
    return currency;
  }

  public void setCurrency(CurrencyEnum currency) {
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
