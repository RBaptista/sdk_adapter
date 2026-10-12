package cv.sitech.adapter.in.sdk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.application.port.in.BulkTransactionCallbackUseCase;
import cv.sitech.application.port.in.BulkTransactionUpdate;
import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.in.QuoteRequest;
import cv.sitech.application.port.in.TransferPrepare;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
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
 * The backend ("core connector") API that the SDK scheme adapter of hosted DFSP {@code fspId} calls
 * when the hub addresses that DFSP as payee. Each SDK's {@code BACKEND_ENDPOINT} is
 * {@code <host>:<port>/sdk/<fspId>}. The SDK waits for each answer and turns an error body
 * {@code {statusCode, message}} into the FSPIOP error callback.
 */
@Path("/sdk/{fspId}")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class SdkBackendResource {

  private static final Logger LOG = Logger.getLogger(SdkBackendResource.class);
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private final PayeeUseCase payee;
  private final BulkTransactionCallbackUseCase bulkCallbacks;
  private final ObjectMapper objectMapper;

  public SdkBackendResource(PayeeUseCase payee, BulkTransactionCallbackUseCase bulkCallbacks,
      ObjectMapper objectMapper) {
    this.payee = payee;
    this.bulkCallbacks = bulkCallbacks;
    this.objectMapper = objectMapper;
  }

  @GET
  @Path("/parties/{idType}/{idValue}")
  public Response party(@PathParam("fspId") String fspId, @PathParam("idType") String idType,
      @PathParam("idValue") String idValue) {
    return answer("GET /parties", () -> {
      PartyInfo party = payee.lookupParty(fspId, idType(idType), idValue);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("idType", party.idType().name());
      body.put("idValue", party.idValue());
      body.put("fspId", party.fspId());
      if (party.name() != null) {
        body.put("displayName", party.name());
        String[] names = party.name().trim().split("\\s+", 2);
        body.put("firstName", names[0]);
        if (names.length > 1) {
          body.put("lastName", names[1]);
        }
      }
      body.put("type", "CONSUMER");
      return body;
    });
  }

  @POST
  @Path("/quoterequests")
  public Response quote(@PathParam("fspId") String fspId, String json) {
    return answer("POST /quoterequests", () -> {
      JsonNode body = read(json);
      Transfer quote = payee.quote(fspId, new QuoteRequest(
          text(body, "quoteId"), text(body, "transactionId"), party(body.path("from")), party(body.path("to")),
          text(body, "amountType"), amount(text(body, "amount")), currency(text(body, "currency")),
          text(body, "note")));
      String currency = quote.getCurrency().name();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("quoteId", quote.getQuoteId());
      response.put("transactionId", quote.getTransactionId());
      response.put("transferAmount", amount(quote.getTransferAmount()));
      response.put("transferAmountCurrency", currency);
      response.put("payeeReceiveAmount", amount(quote.getPayeeReceiveAmount()));
      response.put("payeeReceiveAmountCurrency", currency);
      response.put("payeeFspFeeAmount", amount(quote.getPayeeFee()));
      response.put("payeeFspFeeAmountCurrency", currency);
      response.put("expiration", TIMESTAMP.format(quote.getExpiration()));
      return response;
    });
  }

  @POST
  @Path("/transfers")
  public Response transfer(@PathParam("fspId") String fspId, String json) {
    return answer("POST /transfers", () -> {
      JsonNode body = read(json);
      JsonNode quote = body.path("quote");
      Transfer transfer = payee.prepareTransfer(fspId, new TransferPrepare(text(body, "transferId"),
          text(quote, "quoteId"), text(quote, "transactionId"), amount(text(body, "amount")),
          currency(text(body, "currency"))));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("homeTransactionId", "IN-" + transfer.getId());
      response.put("transferState", transfer.getState().name());
      if (transfer.getCompletedAt() != null) {
        response.put("completedTimestamp", TIMESTAMP.format(transfer.getCompletedAt()));
      }
      return response;
    });
  }

  /** the hub's final state of a transfer the payee answered with RESERVED (the SDK relays the hub's PATCH) */
  @PUT
  @Path("/transfers/{transferId}")
  public Response notification(@PathParam("fspId") String fspId, @PathParam("transferId") String transferId,
      String json) {
    return answer("PUT /transfers", () -> {
      JsonNode body = read(json);
      String state = text(body.path("finalNotification"), "transferState");
      if (state == null) {
        String current = text(body, "currentState");
        state = "COMPLETED".equals(current) ? "COMMITTED"
            : "ABORTED".equals(current) || "ERROR_OCCURRED".equals(current) ? "ABORTED" : current;
      }
      payee.transferNotified(fspId, transferId, state);
      return Map.of();
    });
  }

  // --- bulk: payer callbacks ---

  /**
   * The payer SDK's report on a bulk transaction this DFSP started: parties to accept, quotes to accept,
   * or each transfer's outcome. The acceptances go back to the SDK's outbound API, not in this answer.
   */
  @PUT
  @Path("/bulkTransactions/{bulkTransactionId}")
  public Response bulkTransaction(@PathParam("fspId") String fspId,
      @PathParam("bulkTransactionId") String bulkTransactionId, String json) {
    return answer("PUT /bulkTransactions", () -> {
      JsonNode body = read(json);
      List<BulkTransactionUpdate.Item> items = new ArrayList<>();
      for (JsonNode result : body.path("individualTransferResults")) {
        JsonNode quote = result.path("quoteResponse");
        JsonNode fulfil = result.path("fulfil");
        items.add(new BulkTransactionUpdate.Item(text(result, "homeTransactionId"), text(result, "transferId"),
            result.has("to") ? sdkParty(result.path("to")) : null,
            quote.has("quoteId") ? new BulkTransactionUpdate.Quote(text(quote, "quoteId"),
                money(quote.path("transferAmount")), money(quote.path("payeeReceiveAmount")),
                money(quote.path("payeeFspFee")), text(quote, "ilpPacket"), text(quote, "condition"),
                instant(text(quote, "expiration"))) : null,
            text(fulfil, "transferState"), text(fulfil, "fulfilment"), instant(text(fulfil, "completedTimestamp")),
            sdkError(result)));
      }
      bulkCallbacks.bulkTransactionUpdated(fspId, new BulkTransactionUpdate(bulkTransactionId,
          text(body, "currentState"), sdkError(body), items));
      return Map.of();
    });
  }

  // --- bulk: payee ---

  /** one hub bulk quote (one per payer DFSP and bulk): each individual quote is priced like a single quote */
  @POST
  @Path("/bulkQuotes")
  public Response bulkQuote(@PathParam("fspId") String fspId, String json) {
    return answer("POST /bulkQuotes", () -> {
      JsonNode body = read(json);
      PartyInfo payer = party(body.path("from"));
      List<Map<String, Object>> results = new ArrayList<>();
      Instant expiration = null;
      for (JsonNode q : body.path("individualQuotes")) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quoteId", text(q, "quoteId"));
        try {
          Transfer quote = payee.quote(fspId, new QuoteRequest(text(q, "quoteId"), text(q, "transactionId"), payer,
              party(q.path("to")), text(q, "amountType"), amount(text(q, "amount")), currency(text(q, "currency")),
              text(q, "note")));
          String currency = quote.getCurrency().name();
          result.put("transferAmount", amount(quote.getTransferAmount()));
          result.put("transferAmountCurrency", currency);
          result.put("payeeReceiveAmount", amount(quote.getPayeeReceiveAmount()));
          result.put("payeeReceiveAmountCurrency", currency);
          result.put("payeeFspFeeAmount", amount(quote.getPayeeFee()));
          result.put("payeeFspFeeAmountCurrency", currency);
          expiration = expiration == null || quote.getExpiration().isBefore(expiration) ? quote.getExpiration()
              : expiration;
        } catch (BusinessException e) {
          LOG.infof("POST /bulkQuotes: quote %s rejected: %s %s", text(q, "quoteId"), e.getCode(), e.getMessage());
          result.put("errorResponse", Map.of("statusCode", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
          result.put("errorResponse", Map.of("statusCode", "3100", "message", e.getMessage()));
        }
        results.add(result);
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("bulkQuoteId", text(body, "bulkQuoteId"));
      response.put("individualQuoteResults", results);
      if (expiration != null) {
        response.put("expiration", TIMESTAMP.format(expiration));
      }
      return response;
    });
  }

  /**
   * One hub bulk transfer: each transfer is booked against its quote and committed. The SDK's individual
   * quote id is the transfer id, so that is how a transfer finds its quote.
   */
  @POST
  @Path("/bulkTransfers")
  public Response bulkTransfer(@PathParam("fspId") String fspId, String json) {
    return answer("POST /bulkTransfers", () -> {
      JsonNode body = read(json);
      List<Map<String, Object>> results = new ArrayList<>();
      for (JsonNode t : body.path("individualTransfers")) {
        String transferId = text(t, "transferId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transferId", transferId);
        try {
          payee.prepareBulkTransfer(fspId, new TransferPrepare(transferId, transferId, transferId,
              amount(text(t, "amount")), currency(text(t, "currency"))));
        } catch (BusinessException e) {
          LOG.infof("POST /bulkTransfers: transfer %s rejected: %s %s", transferId, e.getCode(), e.getMessage());
          result.put("errorResponse", Map.of("statusCode", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
          result.put("errorResponse", Map.of("statusCode", "3100", "message", e.getMessage()));
        }
        results.add(result);
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("bulkTransferId", text(body, "bulkTransferId"));
      response.put("individualTransferResults", results);
      return response;
    });
  }

  // --- helpers ---

  /** runs a use case and answers in the SDK backend format: the result, or {@code {statusCode, message}} */
  private Response answer(String operation, Supplier<Object> call) {
    try {
      return Response.ok(call.get()).build();
    } catch (BusinessException e) {
      LOG.infof("%s rejected: %s %s", operation, e.getCode(), e.getMessage());
      int status = "3204".equals(e.getCode()) ? 404 : 400;
      return error(status, e.getCode(), e.getMessage());
    } catch (NotFoundException e) {
      return error(404, "3200", e.getMessage());
    } catch (IllegalArgumentException e) {
      return error(400, "3100", e.getMessage());
    } catch (RuntimeException e) {
      LOG.errorf(e, "%s failed", operation);
      return error(500, "2001", "Internal error: " + e.getMessage());
    }
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(Map.of("statusCode", code, "message", message != null ? message : ""))
        .build();
  }

  private JsonNode read(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("Request body is required");
    }
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Malformed JSON: " + e.getOriginalMessage());
    }
  }

  private static PartyInfo party(JsonNode party) {
    String name = text(party, "displayName");
    if (name == null && text(party, "firstName") != null) {
      name = (text(party, "firstName") + " " + (text(party, "lastName") != null ? text(party, "lastName") : ""))
          .trim();
    }
    return new PartyInfo(idType(text(party, "idType")), text(party, "idValue"), text(party, "fspId"), name);
  }

  /** a party in the SDK's FSPIOP form ({@code partyIdInfo}, {@code name} or {@code personalInfo}) */
  private static PartyInfo sdkParty(JsonNode party) {
    JsonNode id = party.path("partyIdInfo");
    String name = text(party, "name");
    if (name == null) {
      JsonNode complex = party.path("personalInfo").path("complexName");
      String full = ((text(complex, "firstName") != null ? text(complex, "firstName") : "") + " "
          + (text(complex, "lastName") != null ? text(complex, "lastName") : "")).trim();
      name = full.isEmpty() ? null : full;
    }
    IdTypeEnum type;
    try {
      type = IdTypeEnum.valueOf(text(id, "partyIdType"));
    } catch (IllegalArgumentException | NullPointerException e) {
      type = null;
    }
    return new PartyInfo(type, text(id, "partyIdentifier"), text(id, "fspId"), name);
  }

  /** the FSPIOP error the SDK attaches to a bulk transaction or one of its transfers, or null */
  private static FspiopError sdkError(JsonNode node) {
    for (JsonNode info : List.of(node.path("lastError").path("mojaloopError").path("errorInformation"),
        node.path("errorInformation"))) {
      if (info.has("errorCode")) {
        return new FspiopError(info.path("errorCode").asText(), text(info, "errorDescription"));
      }
    }
    return null;
  }

  private static BigDecimal money(JsonNode money) {
    String value = text(money, "amount");
    return value == null ? null : new BigDecimal(value);
  }

  private static Instant instant(String timestamp) {
    return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
  }

  private static IdTypeEnum idType(String value) {
    try {
      return IdTypeEnum.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("Unsupported party id type " + value);
    }
  }

  private static CurrencyEnum currency(String value) {
    try {
      return CurrencyEnum.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException("5106", "Unsupported currency " + value);
    }
  }

  private static BigDecimal amount(String value) {
    if (value == null) {
      throw new IllegalArgumentException("amount is required");
    }
    return new BigDecimal(value);
  }

  private static String amount(BigDecimal amount) {
    return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

}
