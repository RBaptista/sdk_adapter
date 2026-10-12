package cv.sitech.domain.exception;

/** A rule violation; {@code code} is the FSPIOP error code reported to the hub when relevant. */
public class BusinessException extends RuntimeException {
  private final String code;

  public BusinessException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }

}
