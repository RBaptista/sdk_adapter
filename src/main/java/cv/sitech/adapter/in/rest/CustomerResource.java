package cv.sitech.adapter.in.rest;

import java.util.List;

import cv.sitech.adapter.in.rest.dto.CustomerDtos.CustomerRequest;
import cv.sitech.adapter.in.rest.dto.CustomerDtos.CustomerView;
import cv.sitech.application.port.in.CustomerUseCase;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/api/customers")
public class CustomerResource {

  private final CurrentDfsp current;
  private final CustomerUseCase customerUseCase;

  public CustomerResource(CurrentDfsp current, CustomerUseCase customerUseCase) {
    this.current = current;
    this.customerUseCase = customerUseCase;
  }

  @GET
  public List<CustomerView> list() {
    return customerUseCase.findAll(current.fspId()).stream().map(CustomerView::from).toList();
  }

  @POST
  public Response create(CustomerRequest request) {
    var customer = customerUseCase.create(current.fspId(), request.name(),
        request.contact() != null ? request.contact().toDomain() : null, request.address(), request.nif());
    return Response.status(Response.Status.CREATED).entity(CustomerView.from(customer)).build();
  }

  @GET
  @Path("/{id}")
  public CustomerView get(@PathParam("id") Long id) {
    return CustomerView.from(customerUseCase.findById(current.fspId(), id));
  }

  @PUT
  @Path("/{id}")
  public CustomerView update(@PathParam("id") Long id, CustomerRequest request) {
    return CustomerView.from(customerUseCase.update(current.fspId(), id, request.name(),
        request.contact() != null ? request.contact().toDomain() : null, request.address(), request.nif()));
  }

}
