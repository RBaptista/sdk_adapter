package cv.sitech.adapter.out.sdk;

import cv.sitech.domain.exception.BusinessException;

/**
 * The SDK could not be reached, timed out (504) or failed without an FSPIOP error. The outcome at
 * the hub is unknown, unlike an FSPIOP error the SDK reports, which is final.
 */
public class SdkCallException extends BusinessException {
  private final int status;

  public SdkCallException(int status, String message) {
    super(status == 504 ? "2004" : "1001", message);
    this.status = status;
  }

  public int getStatus() {
    return status;
  }

}
