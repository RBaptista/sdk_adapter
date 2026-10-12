package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.TransferDtos.Acceptance;
import cv.sitech.adapter.in.rest.dto.TransferDtos.TransferRequest;
import cv.sitech.adapter.in.rest.dto.TransferDtos.TransferView;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PayeeUseCase;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/**
 * P2P transfers sent by the logged-in DFSP. The flow is asynchronous: poll {@code GET /{id}} and,
 * unless {@code autoAccept} was set, confirm the party and then the quote.
 */
@Path("/api/transfers")
public class TransferResource {

  private final CurrentDfsp current;
  private final OutboundTransferUseCase outbound;
  private final PayeeUseCase payee;

  public TransferResource(CurrentDfsp current, OutboundTransferUseCase outbound, PayeeUseCase payee) {
    this.current = current;
    this.outbound = outbound;
    this.payee = payee;
  }

  @GET
  public List<TransferView> list() {
    return outbound.findAll(current.fspId()).stream().map(TransferView::from).toList();
  }

  /** transfers received by the logged-in DFSP as payee */
  @GET
  @Path("/incoming")
  public List<TransferView> incoming() {
    return payee.findAll(current.fspId()).stream().map(TransferView::from).toList();
  }

  @POST
  public Response start(TransferRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    var transfer = outbound.start(current.fspId(), request.toCommand());
    return Response.accepted(TransferView.from(transfer)).build();
  }

  @GET
  @Path("/{id}")
  public TransferView get(@PathParam("id") String id) {
    return TransferView.from(outbound.findById(current.fspId(), id));
  }

  @PUT
  @Path("/{id}/accept-party")
  public TransferView acceptParty(@PathParam("id") String id, Acceptance request) {
    return TransferView.from(outbound.acceptParty(current.fspId(), id, request == null || request.accept()));
  }

  @PUT
  @Path("/{id}/accept-quote")
  public TransferView acceptQuote(@PathParam("id") String id, Acceptance request) {
    return TransferView.from(outbound.acceptQuote(current.fspId(), id, request == null || request.accept()));
  }

}
