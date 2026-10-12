package cv.sitech.adapter.in.rest;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import cv.sitech.adapter.in.rest.dto.ApiError;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class ErrorMappers {

  @ServerExceptionMapper
  public Response notFound(NotFoundException e) {
    return error(Response.Status.NOT_FOUND, null, e.getMessage());
  }

  @ServerExceptionMapper
  public Response business(BusinessException e) {
    Response.Status status = switch (e.getCode()) {
      case "1001" -> Response.Status.BAD_GATEWAY;
      case "2004" -> Response.Status.GATEWAY_TIMEOUT;
      case "3204" -> Response.Status.NOT_FOUND;
      default -> Response.Status.CONFLICT;
    };
    return error(status, e.getCode(), e.getMessage());
  }

  @ServerExceptionMapper
  public Response badRequest(IllegalArgumentException e) {
    return error(Response.Status.BAD_REQUEST, null, e.getMessage());
  }

  private static Response error(Response.Status status, String code, String message) {
    return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ApiError(code, message)).build();
  }

}
