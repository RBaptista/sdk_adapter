package cv.sitech.domain.exception;

public class InsufficientFundsException extends BusinessException {
  public InsufficientFundsException(String message) {
    super("4001", message);
  }

}
