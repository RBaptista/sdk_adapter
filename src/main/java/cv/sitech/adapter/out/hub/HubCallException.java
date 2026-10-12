package cv.sitech.adapter.out.hub;

import cv.sitech.domain.exception.BusinessException;

/** The hub could not be reached or rejected a request. */
public class HubCallException extends BusinessException {
  private final int status;

  public HubCallException(int status, String message) {
    super("1001", message);
    this.status = status;
  }

  public int getStatus() {
    return status;
  }

}
