package cv.sitech.testhub;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * In-process stand-in for the hub services the app calls directly, used by tests: the
 * central-ledger admin API, ALS removal and the central-settlement reads. FSPIOP traffic goes
 * through {@link FakeSdkResource} instead.
 */
@Path("/test-hub")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class FakeHubResource {

  /** hub positions per fspId, moved by the transfers FakeSdkResource routes */
  public static final Map<String, BigDecimal> POSITIONS = new ConcurrentHashMap<>();
  /** admin calls received, as "METHOD path body" */
  public static final List<String> ADMIN_CALLS = new CopyOnWriteArrayList<>();
  /** central-settlement GET /settlements response */
  public static volatile String settlementsJson = "[]";

  // --- ALS ---

  @DELETE
  @Path("/als/participants/{type}/{id}")
  public Response deleteParticipant(@PathParam("type") String type, @PathParam("id") String id) {
    FakeSdkResource.ORACLE.remove(type + "/" + id);
    return Response.accepted().build();
  }

  // --- central-ledger admin ---

  @POST
  @Path("/admin/{segments: .+}")
  public Response adminPost(@PathParam("segments") String segments, String body) {
    ADMIN_CALLS.add("POST /" + segments + " " + body);
    return Response.status(Response.Status.CREATED).build();
  }

  @PUT
  @Path("/admin/{segments: .+}")
  public Response adminPut(@PathParam("segments") String segments, String body) {
    ADMIN_CALLS.add("PUT /" + segments + " " + body);
    return Response.ok().build();
  }

  @GET
  @Path("/admin/participants")
  @Produces(MediaType.APPLICATION_JSON)
  public String participants() {
    StringBuilder json = new StringBuilder("[");
    for (String fspId : FakeSdkResource.ORACLE.values().stream().distinct().toList()) {
      json.append(json.length() > 1 ? "," : "").append("{\"name\":\"").append(fspId)
          .append("\",\"accounts\":[{\"id\":\"pos-").append(fspId).append("\",\"ledgerAccountType\":\"POSITION\"}]}");
    }
    return json.append("]").toString();
  }

  @GET
  @Path("/admin/participants/{name}/accounts")
  @Produces(MediaType.APPLICATION_JSON)
  public String accounts(@PathParam("name") String name) {
    return "[{\"id\":\"pos-" + name + "\",\"ledgerAccountType\":\"POSITION\",\"currency\":\"CVE\"},"
        + "{\"id\":\"set-" + name + "\",\"ledgerAccountType\":\"SETTLEMENT\",\"currency\":\"CVE\"}]";
  }

  @GET
  @Path("/admin/participants/{name}/positions")
  @Produces(MediaType.APPLICATION_JSON)
  public String positions(@PathParam("name") String name) {
    return "[{\"currency\":\"CVE\",\"value\":" + POSITIONS.getOrDefault(name, BigDecimal.ZERO).toPlainString()
        + "}]";
  }

  // --- central-settlement ---

  @GET
  @Path("/settlement/settlements")
  @Produces(MediaType.APPLICATION_JSON)
  public String settlements() {
    return settlementsJson;
  }

}
