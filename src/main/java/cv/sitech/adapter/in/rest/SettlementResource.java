package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.TransferDtos.ReconciliationView;
import cv.sitech.application.port.in.SettlementUseCase;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/api/settlements")
public class SettlementResource {

  private final CurrentDfsp current;
  private final SettlementUseCase settlementUseCase;

  public SettlementResource(CurrentDfsp current, SettlementUseCase settlementUseCase) {
    this.current = current;
    this.settlementUseCase = settlementUseCase;
  }

  /** reconciliation results of the logged-in DFSP */
  @GET
  public List<ReconciliationView> list() {
    return settlementUseCase.findAll(current.fspId()).stream().map(ReconciliationView::from).toList();
  }

  /** reconciles every hosted DFSP now instead of waiting for the scheduled run */
  @POST
  @Path("/reconcile")
  @RolesAllowed("admin")
  public List<ReconciliationView> reconcile() {
    return settlementUseCase.reconcile().stream().map(ReconciliationView::from).toList();
  }

}
