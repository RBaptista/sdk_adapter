package cv.sitech.adapter.out.sdk;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import cv.sitech.adapter.out.sdk.SdkHttp.SdkResponse;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.application.port.out.SdkOutboundGateway;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

/** {@link SdkOutboundGateway} over the SDK scheme adapter's outbound API ({@code dfsp.sdkOutboundUrl}). */
@ApplicationScoped
public class SdkOutboundClient implements SdkOutboundGateway {

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private final SdkHttp http;
  private final DfspRepository dfspRepository;

  public SdkOutboundClient(SdkHttp http, DfspRepository dfspRepository) {
    this.http = http;
    this.dfspRepository = dfspRepository;
  }

  @Override
  public PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue) {
    SdkResponse response = http.send("GET", baseUrl(fspId) + "/parties/" + idType.name() + "/" + encode(idValue),
        null);
    if (!response.ok()) {
      throw failure(response);
    }
    JsonNode party = response.body().path("party");
    return partyInfo(party.has("body") ? party.path("body") : party);
  }

  @Override
  public SdkTransfer startTransfer(String fspId, Transfer transfer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("homeTransactionId", transfer.getHomeTransactionId());
    body.put("from", Map.of("idType", transfer.getPayerIdType().name(), "idValue", transfer.getPayerIdValue()));
    body.put("to", Map.of("idType", transfer.getPayeeIdType().name(), "idValue", transfer.getPayeeIdValue()));
    body.put("amountType", "SEND");
    body.put("currency", transfer.getCurrency().name());
    body.put("amount", amount(transfer.getAmount()));
    body.put("transactionType", "TRANSFER");
    if (transfer.getNote() != null) {
      body.put("note", transfer.getNote());
    }
    return transfer(http.send("POST", baseUrl(fspId) + "/transfers", body));
  }

  @Override
  public SdkTransfer acceptParty(String fspId, String transferId, boolean accept) {
    return transfer(http.send("PUT", baseUrl(fspId) + "/transfers/" + transferId, Map.of("acceptParty", accept)));
  }

  @Override
  public SdkTransfer acceptQuote(String fspId, String transferId, boolean accept) {
    return transfer(http.send("PUT", baseUrl(fspId) + "/transfers/" + transferId, Map.of("acceptQuote", accept)));
  }

  @Override
  public SdkTransfer getTransfer(String fspId, String transferId) {
    return transfer(http.send("GET", baseUrl(fspId) + "/transfers/" + transferId, null));
  }

  @Override
  public void startBulkTransaction(String fspId, String bulkTransactionId, IdTypeEnum payerIdType,
      String payerIdValue, List<BulkItem> items, Instant expiration) {
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("onlyValidateParty", false);
    // the app drives both acceptances, like AUTO_ACCEPT_PARTY/QUOTES=false for single transfers
    options.put("autoAcceptParty", Map.of("enabled", false));
    options.put("autoAcceptQuote", Map.of("enabled", false));
    options.put("skipPartyLookup", false);
    options.put("synchronous", false);
    options.put("bulkExpiration", TIMESTAMP.format(expiration));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bulkTransactionId", bulkTransactionId);
    body.put("bulkHomeTransactionID", bulkTransactionId);
    body.put("options", options);
    body.put("from", Map.of("partyIdInfo", Map.of("partyIdType", payerIdType.name(), "partyIdentifier", payerIdValue,
        "fspId", fspId)));
    body.put("individualTransfers", items.stream().map(item -> {
      Map<String, Object> transfer = new LinkedHashMap<>();
      transfer.put("homeTransactionId", item.homeTransactionId());
      transfer.put("to", Map.of("partyIdInfo", Map.of("partyIdType", item.payeeIdType().name(),
          "partyIdentifier", item.payeeIdValue())));
      transfer.put("amountType", "SEND");
      transfer.put("currency", item.currency().name());
      transfer.put("amount", amount(item.amount()));
      if (item.note() != null) {
        transfer.put("note", item.note());
      }
      return transfer;
    }).toList());
    require(http.send("POST", baseUrl(fspId) + "/bulkTransactions", body));
  }

  @Override
  public void acceptBulkParties(String fspId, String bulkTransactionId, Map<String, Boolean> acceptByTransferId) {
    continueBulk(fspId, bulkTransactionId, "acceptParty", acceptByTransferId);
  }

  @Override
  public void acceptBulkQuotes(String fspId, String bulkTransactionId, Map<String, Boolean> acceptByTransferId) {
    continueBulk(fspId, bulkTransactionId, "acceptQuote", acceptByTransferId);
  }

  private void continueBulk(String fspId, String bulkTransactionId, String decision, Map<String, Boolean> accept) {
    if (accept.isEmpty()) {
      return;
    }
    List<Map<String, Object>> transfers = accept.entrySet().stream()
        .map(e -> Map.<String, Object>of("transferId", e.getKey(), decision, e.getValue())).toList();
    require(http.send("PUT", baseUrl(fspId) + "/bulkTransactions/" + bulkTransactionId,
        Map.of("individualTransfers", transfers)));
  }

  private static void require(SdkResponse response) {
    if (!response.ok()) {
      throw failure(response);
    }
  }

  @Override
  public void registerParty(String fspId, IdTypeEnum idType, String idValue, String currency) {
    SdkResponse response = http.send("POST", baseUrl(fspId) + "/accounts",
        List.of(Map.of("idType", idType.name(), "idValue", idValue, "currency", currency)));
    if (!response.ok()) {
      throw failure(response);
    }
    for (JsonNode account : response.body().path("response")) {
      if (account.has("error")) {
        JsonNode error = account.path("error");
        String code = text(error, "statusCode", "3000");
        // the ALS bulk API answers a repeated registration with a generic 3003; it's fine if the identifier is ours
        if ("3003".equals(code) && registeredTo(fspId, idType, idValue)) {
          continue;
        }
        throw new BusinessException(code,
            "ALS registration of " + idType + "/" + idValue + " failed: " + text(error, "message", ""));
      }
    }
  }

  private boolean registeredTo(String fspId, IdTypeEnum idType, String idValue) {
    try {
      return fspId.equals(lookupParty(fspId, idType, idValue).fspId());
    } catch (RuntimeException e) {
      return false;
    }
  }

  // --- wire conversions ---

  /**
   * A transfer step's answer. An FSPIOP error the SDK reports is a final outcome and returned in
   * {@code error}; a failure without one (unreachable, timeout, internal error) is thrown, since the
   * hub's outcome is unknown.
   */
  private static SdkTransfer transfer(SdkResponse response) {
    JsonNode state = response.body();
    FspiopError error = null;
    if (!response.ok()) {
      error = mojaloopError(state);
      if (error == null) {
        throw new SdkCallException(response.status(), "SDK returned " + response.status() + ": "
            + text(state, "message", state.toString()));
      }
      state = state.path("transferState");
    }
    JsonNode quote = state.path("quoteResponse").path("body");
    JsonNode fulfil = state.path("fulfil").path("body");
    JsonNode party = state.path("getPartiesResponse").path("body").path("party");
    return new SdkTransfer(
        text(state, "transferId", null),
        text(state, "currentState", null),
        party.isMissingNode() ? null : partyInfo(party),
        text(state, "quoteId", null),
        money(quote.path("transferAmount")),
        money(quote.path("payeeReceiveAmount")),
        money(quote.path("payeeFspFee")),
        instant(text(quote, "expiration", null)),
        text(quote, "ilpPacket", null),
        text(quote, "condition", null),
        text(fulfil, "fulfilment", null),
        text(fulfil, "transferState", null),
        instant(text(fulfil, "completedTimestamp", null)),
        error);
  }

  /** the FSPIOP error of an SDK error body, or null when there is none */
  private static FspiopError mojaloopError(JsonNode body) {
    for (JsonNode info : List.of(body.path("errorInformation"),
        body.path("transferState").path("lastError").path("mojaloopError").path("errorInformation"),
        body.path("lastError").path("mojaloopError").path("errorInformation"))) {
      if (info.has("errorCode")) {
        return new FspiopError(info.path("errorCode").asText(), text(info, "errorDescription", ""));
      }
    }
    return null;
  }

  private static RuntimeException failure(SdkResponse response) {
    FspiopError error = mojaloopError(response.body());
    if (error != null) {
      return new BusinessException(error.code(), error.description());
    }
    return new SdkCallException(response.status(), "SDK returned " + response.status() + ": "
        + text(response.body(), "message", response.body().toString()));
  }

  private static PartyInfo partyInfo(JsonNode party) {
    JsonNode id = party.path("partyIdInfo");
    String name = text(party, "name", null);
    if (name == null) {
      JsonNode complex = party.path("personalInfo").path("complexName");
      String full = String.join(" ", List.of(text(complex, "firstName", ""), text(complex, "middleName", ""),
          text(complex, "lastName", ""))).replaceAll("\\s+", " ").trim();
      name = full.isEmpty() ? null : full;
    }
    IdTypeEnum idType;
    try {
      idType = IdTypeEnum.valueOf(text(id, "partyIdType", ""));
    } catch (IllegalArgumentException e) {
      idType = null;
    }
    return new PartyInfo(idType, text(id, "partyIdentifier", null), text(id, "fspId", null), name);
  }

  private String baseUrl(String fspId) {
    String url = QuarkusTransaction.joiningExisting().call(() -> dfspRepository.findById(fspId)
        .orElseThrow(() -> new NotFoundException("Dfsp " + fspId + " not Found")).getSdkOutboundUrl());
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Dfsp " + fspId + " has no SDK outbound URL");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** FSPIOP amounts carry no trailing zeros ({@code 10}, not {@code 10.00}) */
  static String amount(BigDecimal amount) {
    return amount.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal money(JsonNode money) {
    String amount = text(money, "amount", null);
    return amount == null ? null : new BigDecimal(amount);
  }

  private static Instant instant(String timestamp) {
    return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? fallback : value.asText();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

}
