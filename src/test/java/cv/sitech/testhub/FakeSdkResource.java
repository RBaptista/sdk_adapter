package cv.sitech.testhub;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * In-process stand-in for the SDK scheme adapters of the hosted DFSPs, used by tests. It serves the
 * SDK outbound API at {@code /test-sdk/{fspId}} and plays the hub behind it: an ALS oracle, and
 * quotes and transfers routed straight to the payee's backend API at {@code /sdk/{payeeFspId}} of
 * this same app. Like the real SDK with AUTO_ACCEPT_PARTY/QUOTES off, it stops for each acceptance.
 */
@Path("/test-sdk/{fspId}")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class FakeSdkResource {

  public static final String BACKEND = "http://localhost:8081/sdk/";

  public static String outboundUrl(String fspId) {
    return "http://localhost:8081/test-sdk/" + fspId;
  }

  /** ALS oracle: "TYPE/id" -> fspId */
  public static final Map<String, String> ORACLE = new ConcurrentHashMap<>();

  /**
   * payee identifier values whose transfer commits at the hub but whose acceptQuote answers 504, as
   * when the SDK times out after the hub's fulfil: the payer can't tell the outcome until it asks
   */
  public static final Set<String> TIMEOUT_AFTER_COMMIT = ConcurrentHashMap.newKeySet();

  private static final Map<String, Map<String, Object>> TRANSFERS = new ConcurrentHashMap<>();
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private static final ObjectMapper JSON = new ObjectMapper();

  record Backend(int status, JsonNode body) {
    boolean ok() {
      return status / 100 == 2;
    }
  }

  @POST
  @Path("/accounts")
  public Map<String, Object> accounts(@PathParam("fspId") String fspId, String body) throws Exception {
    for (JsonNode account : JSON.readTree(body)) {
      ORACLE.put(account.path("idType").asText() + "/" + account.path("idValue").asText(), fspId);
    }
    return Map.of("currentState", "COMPLETED", "response", List.of());
  }

  @GET
  @Path("/parties/{type}/{id}")
  public Response parties(@PathParam("fspId") String fspId, @PathParam("type") String type,
      @PathParam("id") String id) {
    Object party = lookup(type, id);
    if (party instanceof JsonNode error) {
      return Response.status(404).entity(Map.of("errorInformation", error)).build();
    }
    return Response.ok(Map.of("party", Map.of("body", party), "currentState", "COMPLETED")).build();
  }

  @POST
  @Path("/transfers")
  public Response start(@PathParam("fspId") String fspId, String body) throws Exception {
    JsonNode request = JSON.readTree(body);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("transferId", UUID.randomUUID().toString());
    state.put("homeTransactionId", request.path("homeTransactionId").asText());
    state.put("payerFsp", fspId);
    state.put("from", JSON.convertValue(request.path("from"), Map.class));
    state.put("to", JSON.convertValue(request.path("to"), Map.class));
    state.put("amountType", request.path("amountType").asText());
    state.put("amount", request.path("amount").asText());
    state.put("currency", request.path("currency").asText());
    Object party = lookup(request.path("to").path("idType").asText(), request.path("to").path("idValue").asText());
    if (party instanceof JsonNode error) {
      return failed(state, error);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> partyBody = (Map<String, Object>) party;
    @SuppressWarnings("unchecked")
    Map<String, Object> idInfo = (Map<String, Object>) partyBody.get("partyIdInfo");
    state.put("payeeFsp", idInfo.get("fspId"));
    state.put("getPartiesResponse", Map.of("body", Map.of("party", party)));
    state.put("currentState", "WAITING_FOR_PARTY_ACCEPTANCE");
    TRANSFERS.put((String) state.get("transferId"), state);
    return Response.ok(state).build();
  }

  @PUT
  @Path("/transfers/{transferId}")
  public Response accept(@PathParam("transferId") String transferId, String body) throws Exception {
    Map<String, Object> state = TRANSFERS.get(transferId);
    if (state == null) {
      return Response.status(404).entity(Map.of("message", "unknown transfer")).build();
    }
    JsonNode decision = JSON.readTree(body);
    if (decision.has("acceptParty")) {
      return decision.path("acceptParty").asBoolean() ? quote(state) : aborted(state);
    }
    return decision.path("acceptQuote").asBoolean() ? transfer(state) : aborted(state);
  }

  @GET
  @Path("/transfers/{transferId}")
  public Response get(@PathParam("transferId") String transferId) {
    Map<String, Object> state = TRANSFERS.get(transferId);
    return state == null ? Response.status(404).entity(Map.of("message", "unknown transfer")).build()
        : Response.ok(state).build();
  }

  // --- bulk transactions (SDK /bulkTransactions with the hub's bulk quotes and bulk transfers) ---

  private static final Map<String, Map<String, Object>> BULKS = new ConcurrentHashMap<>();

  /** like the SDK: answers 202, looks every payee up, then reports WAITING_FOR_PARTY_ACCEPTANCE to the backend */
  @POST
  @Path("/bulkTransactions")
  public Response startBulk(@PathParam("fspId") String fspId, String body) throws Exception {
    JsonNode request = JSON.readTree(body);
    String bulkId = request.path("bulkTransactionId").asText();
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode t : request.path("individualTransfers")) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("homeTransactionId", t.path("homeTransactionId").asText());
      item.put("transferId", UUID.randomUUID().toString());
      item.put("idType", t.path("to").path("partyIdInfo").path("partyIdType").asText());
      item.put("idValue", t.path("to").path("partyIdInfo").path("partyIdentifier").asText());
      item.put("amount", t.path("amount").asText());
      item.put("currency", t.path("currency").asText());
      items.add(item);
    }
    Map<String, Object> bulk = new LinkedHashMap<>();
    bulk.put("payerFsp", fspId);
    bulk.put("from", JSON.convertValue(request.path("from").path("partyIdInfo"), Map.class));
    bulk.put("items", items);
    BULKS.put(bulkId, bulk);
    later(() -> {
      for (Map<String, Object> item : items) {
        Object party = lookup((String) item.get("idType"), (String) item.get("idValue"));
        if (party instanceof JsonNode error) {
          item.put("lastError", lastError(error));
        } else {
          item.put("party", party);
        }
      }
      report(fspId, bulkId, "WAITING_FOR_PARTY_ACCEPTANCE", items.stream().map(item -> {
        Map<String, Object> result = result(item);
        if (item.containsKey("party")) {
          result.put("to", item.get("party"));
        }
        return result;
      }).toList());
    });
    return Response.accepted(Map.of("bulkTransactionId", bulkId, "currentState", "RECEIVED")).build();
  }

  /** acceptParty: one bulk quote per payee DFSP; acceptQuote: one bulk transfer per payee DFSP */
  @PUT
  @Path("/bulkTransactions/{bulkId}")
  public Response continueBulk(@PathParam("fspId") String fspId, @PathParam("bulkId") String bulkId, String body)
      throws Exception {
    Map<String, Object> bulk = BULKS.get(bulkId);
    if (bulk == null) {
      return Response.status(404).entity(Map.of("message", "unknown bulk transaction")).build();
    }
    JsonNode decisions = JSON.readTree(body).path("individualTransfers");
    boolean quotes = decisions.size() > 0 && decisions.get(0).has("acceptQuote");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) bulk.get("items");
    List<Map<String, Object>> accepted = new ArrayList<>();
    for (JsonNode d : decisions) {
      items.stream().filter(i -> i.get("transferId").equals(d.path("transferId").asText())).findFirst()
          .ifPresent(i -> {
            i.put(quotes ? "acceptQuote" : "acceptParty", d.path(quotes ? "acceptQuote" : "acceptParty").asBoolean());
            if (i.get(quotes ? "acceptQuote" : "acceptParty").equals(true)) {
              accepted.add(i);
            }
          });
    }
    later(() -> {
      if (quotes) {
        bulkTransfers(fspId, bulkId, accepted);
      } else {
        bulkQuotes(fspId, bulkId, bulk, accepted);
      }
    });
    return Response.accepted(Map.of("bulkTransactionId", bulkId)).build();
  }

  @SuppressWarnings("unchecked")
  private static void bulkQuotes(String fspId, String bulkId, Map<String, Object> bulk,
      List<Map<String, Object>> accepted) {
    Map<String, Object> from = (Map<String, Object>) bulk.get("from");
    for (Map.Entry<String, List<Map<String, Object>>> group : byPayee(accepted).entrySet()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("bulkQuoteId", UUID.randomUUID().toString());
      request.put("from", Map.of("idType", from.get("partyIdType"), "idValue", from.get("partyIdentifier"),
          "fspId", fspId));
      request.put("individualQuotes", group.getValue().stream().map(i -> Map.of(
          "quoteId", i.get("transferId"), "transactionId", i.get("transferId"),
          "to", Map.of("idType", i.get("idType"), "idValue", i.get("idValue"), "fspId", group.getKey()),
          "amountType", "SEND", "amount", i.get("amount"), "currency", i.get("currency"),
          "transactionType", "TRANSFER")).toList());
      Backend response = call("POST", group.getKey() + "/bulkQuotes", request);
      for (Map<String, Object> item : group.getValue()) {
        JsonNode quote = null;
        for (JsonNode q : response.body().path("individualQuoteResults")) {
          if (q.path("quoteId").asText().equals(item.get("transferId"))) {
            quote = q;
          }
        }
        if (!response.ok() || quote == null || quote.has("errorResponse")) {
          JsonNode error = quote != null ? quote.path("errorResponse") : response.body();
          item.put("lastError", lastError(errorInformation(error.path("statusCode").asText("3200"),
              error.path("message").asText("No quote"))));
          continue;
        }
        String currency = quote.path("transferAmountCurrency").asText();
        Map<String, Object> quoteResponse = new LinkedHashMap<>();
        quoteResponse.put("quoteId", item.get("transferId"));
        quoteResponse.put("transferAmount", Map.of("amount", quote.path("transferAmount").asText(), "currency", currency));
        quoteResponse.put("payeeReceiveAmount", Map.of("amount", quote.path("payeeReceiveAmount").asText(),
            "currency", currency));
        quoteResponse.put("payeeFspFee", Map.of("amount", quote.path("payeeFspFeeAmount").asText(), "currency", currency));
        quoteResponse.put("ilpPacket", "fake-ilp-packet");
        quoteResponse.put("condition", "fake-condition");
        quoteResponse.put("expiration", response.body().path("expiration").asText());
        item.put("quoteResponse", quoteResponse);
        item.put("transferAmount", quote.path("transferAmount").asText());
      }
    }
    report(fspId, bulkId, "WAITING_FOR_QUOTE_ACCEPTANCE", accepted.stream().map(item -> {
      Map<String, Object> result = result(item);
      if (item.containsKey("quoteResponse")) {
        result.put("quoteResponse", item.get("quoteResponse"));
      }
      return result;
    }).toList());
  }

  private static void bulkTransfers(String fspId, String bulkId, List<Map<String, Object>> accepted) {
    for (Map.Entry<String, List<Map<String, Object>>> group : byPayee(accepted).entrySet()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("bulkTransferId", UUID.randomUUID().toString());
      request.put("individualTransfers", group.getValue().stream().map(i -> Map.of(
          "transferId", i.get("transferId"), "amount", i.get("transferAmount"), "currency", i.get("currency"))).toList());
      Backend response = call("POST", group.getKey() + "/bulkTransfers", request);
      String completed = Instant.now().toString();
      for (Map<String, Object> item : group.getValue()) {
        JsonNode result = null;
        for (JsonNode r : response.body().path("individualTransferResults")) {
          if (r.path("transferId").asText().equals(item.get("transferId"))) {
            result = r;
          }
        }
        if (!response.ok() || result == null || result.has("errorResponse")) {
          JsonNode error = result != null ? result.path("errorResponse") : response.body();
          item.put("lastError", lastError(errorInformation(error.path("statusCode").asText("5000"),
              error.path("message").asText("Transfer failed"))));
          continue;
        }
        BigDecimal amount = new BigDecimal((String) item.get("transferAmount"));
        FakeHubResource.POSITIONS.merge(fspId, amount, BigDecimal::add);
        FakeHubResource.POSITIONS.merge(group.getKey(), amount.negate(), BigDecimal::add);
        item.put("fulfil", Map.of("transferId", item.get("transferId"), "fulfilment", "fake-fulfilment",
            "completedTimestamp", completed, "transferState", "COMMITTED"));
      }
    }
    report(fspId, bulkId, "COMPLETED", accepted.stream().map(item -> {
      Map<String, Object> result = result(item);
      if (item.containsKey("fulfil")) {
        result.put("fulfil", item.get("fulfil"));
      }
      return result;
    }).toList());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<Map<String, Object>>> byPayee(List<Map<String, Object>> items) {
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (Map<String, Object> item : items) {
      Map<String, Object> idInfo = (Map<String, Object>) ((Map<String, Object>) item.get("party")).get("partyIdInfo");
      groups.computeIfAbsent((String) idInfo.get("fspId"), k -> new ArrayList<>()).add(item);
    }
    return groups;
  }

  private static Map<String, Object> result(Map<String, Object> item) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("homeTransactionId", item.get("homeTransactionId"));
    result.put("transferId", item.get("transferId"));
    if (item.containsKey("lastError")) {
      result.put("lastError", item.get("lastError"));
    }
    return result;
  }

  /** the SDK's report to the payer's backend */
  private static void report(String fspId, String bulkId, String state, List<Map<String, Object>> results) {
    call("PUT", fspId + "/bulkTransactions/" + bulkId, Map.of("bulkTransactionId", bulkId,
        "bulkHomeTransactionID", bulkId, "currentState", state, "individualTransferResults", results));
  }

  private static Map<String, Object> lastError(JsonNode errorInformation) {
    return Map.of("httpStatusCode", 500, "mojaloopError",
        Map.of("errorInformation", JSON.convertValue(errorInformation, Map.class)));
  }

  /** after the 202 answer, as the SDK's event handlers do */
  private static void later(Runnable work) {
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(50);
        work.run();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
  }

  // --- the hub behind the SDK ---

  /** the party as the payee's backend describes it, or the FSPIOP errorInformation */
  private static Object lookup(String type, String id) {
    String payee = ORACLE.get(type + "/" + id);
    if (payee == null) {
      return errorInformation("3204", "Party not found");
    }
    Backend response = call("GET", payee + "/parties/" + type + "/" + id, null);
    if (!response.ok()) {
      return errorInformation(response.body().path("statusCode").asText(), response.body().path("message").asText());
    }
    Map<String, Object> party = new LinkedHashMap<>();
    party.put("partyIdInfo", Map.of("partyIdType", type, "partyIdentifier", id, "fspId", payee));
    if (response.body().has("displayName")) {
      party.put("name", response.body().path("displayName").asText());
    }
    return party;
  }

  private static Response quote(Map<String, Object> state) {
    String quoteId = UUID.randomUUID().toString();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("quoteId", quoteId);
    request.put("transactionId", state.get("transferId"));
    request.put("from", withFsp(state.get("from"), (String) state.get("payerFsp")));
    request.put("to", withFsp(state.get("to"), (String) state.get("payeeFsp")));
    request.put("amountType", state.get("amountType"));
    request.put("amount", state.get("amount"));
    request.put("currency", state.get("currency"));
    request.put("transactionType", "TRANSFER");
    Backend response = call("POST", state.get("payeeFsp") + "/quoterequests", request);
    if (!response.ok()) {
      return failed(state, errorInformation(response.body().path("statusCode").asText(),
          response.body().path("message").asText()));
    }
    JsonNode quote = response.body();
    String currency = quote.path("transferAmountCurrency").asText();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transferAmount", Map.of("amount", quote.path("transferAmount").asText(), "currency", currency));
    body.put("payeeReceiveAmount", Map.of("amount", quote.path("payeeReceiveAmount").asText(), "currency", currency));
    body.put("payeeFspFee", Map.of("amount", quote.path("payeeFspFeeAmount").asText(), "currency", currency));
    body.put("expiration", quote.path("expiration").asText());
    body.put("ilpPacket", "fake-ilp-packet");
    body.put("condition", "fake-condition");
    state.put("quoteId", quoteId);
    state.put("quoteBackendResponse", JSON.convertValue(quote, Map.class));
    state.put("quoteResponse", Map.of("body", body));
    state.put("currentState", "WAITING_FOR_QUOTE_ACCEPTANCE");
    return Response.ok(state).build();
  }

  @SuppressWarnings("unchecked")
  private static Response transfer(Map<String, Object> state) {
    Map<String, Object> quote = (Map<String, Object>) state.get("quoteBackendResponse");
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("transferId", state.get("transferId"));
    request.put("quote", quote);
    request.put("from", state.get("from"));
    request.put("to", state.get("to"));
    request.put("amountType", state.get("amountType"));
    request.put("amount", quote.get("transferAmount"));
    request.put("currency", quote.get("transferAmountCurrency"));
    String payee = (String) state.get("payeeFsp");
    String transferId = (String) state.get("transferId");
    Backend response = call("POST", payee + "/transfers", request);
    if (!response.ok()) {
      return failed(state, errorInformation(response.body().path("statusCode").asText(),
          response.body().path("message").asText()));
    }
    BigDecimal amount = new BigDecimal((String) quote.get("transferAmount"));
    FakeHubResource.POSITIONS.merge((String) state.get("payerFsp"), amount, BigDecimal::add);
    FakeHubResource.POSITIONS.merge(payee, amount.negate(), BigDecimal::add);
    String completed = Instant.now().toString();
    if ("RESERVED".equals(response.body().path("transferState").asText())) {
      // the hub's PATCH to the payee, relayed by its SDK
      call("PUT", payee + "/transfers/" + transferId, Map.of("transferId", transferId, "currentState", "COMPLETED",
          "finalNotification", Map.of("transferState", "COMMITTED", "completedTimestamp", completed)));
    }
    state.put("fulfil", Map.of("body", Map.of("fulfilment", "fake-fulfilment", "transferState", "COMMITTED",
        "completedTimestamp", completed)));
    state.put("currentState", "COMPLETED");
    @SuppressWarnings("unchecked")
    Map<String, Object> to = (Map<String, Object>) state.get("to");
    if (TIMEOUT_AFTER_COMMIT.contains(String.valueOf(to.get("idValue")))) {
      return Response.status(504).entity(Map.of("message", "Timeout waiting for the fulfil")).build();
    }
    return Response.ok(state).build();
  }

  private static Response aborted(Map<String, Object> state) {
    state.put("currentState", "ABORTED");
    return Response.ok(state).build();
  }

  /** the SDK's error answer: 500 with the FSPIOP error in transferState.lastError */
  private static Response failed(Map<String, Object> state, JsonNode errorInformation) {
    state.put("currentState", "ERROR_OCCURRED");
    state.put("lastError", Map.of("httpStatusCode", 500, "mojaloopError", Map.of("errorInformation", errorInformation)));
    return Response.status(500).entity(Map.of("message", errorInformation.path("errorDescription").asText(),
        "statusCode", errorInformation.path("errorCode").asText(), "transferState", state)).build();
  }

  private static JsonNode errorInformation(String code, String description) {
    return JSON.createObjectNode().put("errorCode", code).put("errorDescription", description);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> withFsp(Object party, String fspId) {
    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) party);
    copy.put("fspId", fspId);
    return copy;
  }

  private static Backend call(String method, String path, Object body) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(BACKEND + path))
          .header("Content-Type", "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
          .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      String text = response.body();
      return new Backend(response.statusCode(),
          text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    } catch (Exception e) {
      throw new IllegalStateException(method + " " + path + " failed", e);
    }
  }

}
