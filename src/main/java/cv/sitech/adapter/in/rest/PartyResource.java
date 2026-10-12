package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.CustomerDtos.PartyRequest;
import cv.sitech.adapter.in.rest.dto.CustomerDtos.PartyView;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PartyUseCase;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.PartyInfo;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/api/parties")
public class PartyResource {

  private final CurrentDfsp current;
  private final PartyUseCase partyUseCase;
  private final OutboundTransferUseCase outbound;

  public PartyResource(CurrentDfsp current, PartyUseCase partyUseCase, OutboundTransferUseCase outbound) {
    this.current = current;
    this.partyUseCase = partyUseCase;
    this.outbound = outbound;
  }

  @GET
  public List<PartyView> list() {
    return partyUseCase.findAll(current.fspId()).stream().map(PartyView::from).toList();
  }

  /** maps an identifier to a customer account and registers it in the hub's ALS */
  @POST
  public Response create(PartyRequest request) {
    if (request == null || request.idType() == null || request.idValue() == null || request.accountId() == null) {
      throw new IllegalArgumentException("idType, idValue and accountId are required");
    }
    var party = partyUseCase.create(current.fspId(), request.idType(), request.idValue(), request.accountId());
    return Response.status(Response.Status.CREATED).entity(PartyView.from(party)).build();
  }

  @GET
  @Path("/{id}")
  public PartyView get(@PathParam("id") Long id) {
    return PartyView.from(partyUseCase.findById(current.fspId(), id));
  }

  /** registers the party in the hub's ALS again, e.g. after the hub was reset */
  @PUT
  @Path("/{id}/registration")
  public PartyView register(@PathParam("id") Long id) {
    return PartyView.from(partyUseCase.register(current.fspId(), id));
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") Long id) {
    partyUseCase.delete(current.fspId(), id);
    return Response.noContent().build();
  }

  /** FSPIOP discovery through the hub; waits for the callback */
  @GET
  @Path("/lookup/{idType}/{idValue}")
  public PartyInfo lookup(@PathParam("idType") IdTypeEnum idType, @PathParam("idValue") String idValue) {
    return outbound.lookupParty(current.fspId(), idType, idValue);
  }

}
