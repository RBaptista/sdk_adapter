package cv.sitech.domain;

public enum CurrencyEnum {
  CVE(2),
  USD(2);

  private final int scale;

  CurrencyEnum(int scale) {
    this.scale = scale;
  }

  public int scale() {
    return scale;
  }
}
