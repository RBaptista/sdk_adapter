package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.DfspDtos.CreateDfsp;
import cv.sitech.adapter.in.rest.dto.DfspDtos.DfspView;
import cv.sitech.adapter.in.rest.dto.DfspDtos.UpdateDfsp;
import cv.sitech.application.port.in.DfspUseCase;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Simulator administration: the DFSPs this app hosts and their hub onboarding. */
@Path("/api/admin/dfsps")
@RolesAllowed("admin")
public class DfspAdminResource {

  private final DfspUseCase dfspUseCase;

  public DfspAdminResource(DfspUseCase dfspUseCase) {
    this.dfspUseCase = dfspUseCase;
  }

  @GET
  public List<DfspView> list() {
    return dfspUseCase.findAll().stream().map(DfspView::from).toList();
  }

  @POST
  public Response create(CreateDfsp request) {
    if (request == null || request.fspId() == null || request.currency() == null) {
      throw new IllegalArgumentException("fspId and currency are required");
    }
    var dfsp = dfspUseCase.create(request.fspId(), request.displayName(), request.currency(),
        request.sdkOutboundUrl(), request.sdkInboundUrl(), request.netDebitCap(), request.initialPosition(),
        request.initialFunds());
    return Response.status(Response.Status.CREATED).entity(DfspView.from(dfsp)).build();
  }

  @GET
  @Path("/{fspId}")
  public DfspView get(@PathParam("fspId") String fspId) {
    return DfspView.from(dfspUseCase.findById(fspId));
  }

  @PUT
  @Path("/{fspId}")
  public DfspView update(@PathParam("fspId") String fspId, UpdateDfsp request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    return DfspView.from(dfspUseCase.update(fspId, request.displayName(), request.sdkOutboundUrl(),
        request.sdkInboundUrl(), request.netDebitCap()));
  }

  /** creates the participant in the hub with endpoints pointing at the DFSP's SDK inbound API */
  @POST
  @Path("/{fspId}/onboard")
  public Response onboard(@PathParam("fspId") String fspId) {
    dfspUseCase.onboard(fspId);
    return Response.noContent().build();
  }

}
