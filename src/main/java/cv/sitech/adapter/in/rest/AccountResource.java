package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.CustomerDtos.Deposit;
import cv.sitech.adapter.in.rest.dto.CustomerDtos.EntryView;
import cv.sitech.adapter.in.rest.dto.CustomerDtos.OpenAccount;
import cv.sitech.adapter.in.rest.dto.DfspDtos.AccountView;
import cv.sitech.application.port.in.AccountUseCase;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/api/accounts")
public class AccountResource {

  private final CurrentDfsp current;
  private final AccountUseCase accountUseCase;

  public AccountResource(CurrentDfsp current, AccountUseCase accountUseCase) {
    this.current = current;
    this.accountUseCase = accountUseCase;
  }

  @GET
  public List<AccountView> list() {
    return accountUseCase.findAll(current.fspId()).stream().map(AccountView::from).toList();
  }

  @POST
  public Response open(OpenAccount request) {
    var account = accountUseCase.open(current.fspId(), request.customerId());
    return Response.status(Response.Status.CREATED).entity(AccountView.from(account)).build();
  }

  @GET
  @Path("/{id}")
  public AccountView get(@PathParam("id") Long id) {
    return AccountView.from(accountUseCase.findById(current.fspId(), id));
  }

  @POST
  @Path("/{id}/deposits")
  public AccountView deposit(@PathParam("id") Long id, Deposit request) {
    return AccountView.from(accountUseCase.deposit(current.fspId(), id, request.amount()));
  }

  @GET
  @Path("/{id}/statement")
  public List<EntryView> statement(@PathParam("id") Long id) {
    return accountUseCase.statement(current.fspId(), id).stream().map(EntryView::from).toList();
  }

}
