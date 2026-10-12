package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.DisbursementDtos.DisbursementRequest;
import cv.sitech.adapter.in.rest.dto.DisbursementDtos.DisbursementView;
import cv.sitech.adapter.in.rest.dto.DisbursementDtos.ItemView;
import cv.sitech.application.port.in.DisbursementUseCase;
import cv.sitech.domain.disbursement.DisbursementItemState;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * G2P disbursements paid by the logged-in DFSP. Create the batch, validate it (a party lookup per
 * item), poll {@code GET /{id}} until VALIDATED, check the items, then approve it and poll until it
 * is COMPLETED, PARTIALLY_COMPLETED or FAILED.
 */
@Path("/api/disbursements")
public class DisbursementResource {

  private final CurrentDfsp current;
  private final DisbursementUseCase disbursements;

  public DisbursementResource(CurrentDfsp current, DisbursementUseCase disbursements) {
    this.current = current;
    this.disbursements = disbursements;
  }

  @GET
  public List<DisbursementView> list() {
    return disbursements.findAll(current.fspId()).stream().map(DisbursementView::from).toList();
  }

  @POST
  public Response create(DisbursementRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    var disbursement = disbursements.create(current.fspId(), request.toCommand());
    return Response.status(Response.Status.CREATED).entity(DisbursementView.from(disbursement)).build();
  }

  @GET
  @Path("/{id}")
  public DisbursementView get(@PathParam("id") String id) {
    return DisbursementView.from(disbursements.findById(current.fspId(), id));
  }

  @GET
  @Path("/{id}/items")
  public List<ItemView> items(@PathParam("id") String id, @QueryParam("state") String state) {
    DisbursementItemState filter = state == null || state.isBlank() ? null
        : DisbursementItemState.valueOf(state.toUpperCase());
    return disbursements.items(current.fspId(), id, filter).stream().map(ItemView::from).toList();
  }

  @PUT
  @Path("/{id}/validate")
  public Response validate(@PathParam("id") String id) {
    return Response.accepted(DisbursementView.from(disbursements.validate(current.fspId(), id))).build();
  }

  @PUT
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") String id) {
    return Response.accepted(DisbursementView.from(disbursements.approve(current.fspId(), id))).build();
  }

  @PUT
  @Path("/{id}/cancel")
  public DisbursementView cancel(@PathParam("id") String id) {
    return DisbursementView.from(disbursements.cancel(current.fspId(), id));
  }

}
