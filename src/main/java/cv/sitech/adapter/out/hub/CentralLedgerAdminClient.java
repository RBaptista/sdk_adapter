package cv.sitech.adapter.out.hub;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import cv.sitech.application.port.out.HubAdminGateway;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CentralLedgerAdminClient implements HubAdminGateway {

  private static final Logger LOG = Logger.getLogger(CentralLedgerAdminClient.class);

  private final HubHttp http;
  private final SimulatorConfig config;

  public CentralLedgerAdminClient(HubHttp http, SimulatorConfig config) {
    this.http = http;
    this.config = config;
  }

  /** callback templates the hub fills in; every DFSP points at the inbound API of its own SDK */
  static Map<String, String> endpoints(String base) {
    Map<String, String> endpoints = new LinkedHashMap<>();
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_GET", base + "/parties/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_PUT", base + "/parties/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_PUT_ERROR",
        base + "/parties/{{partyIdType}}/{{partyIdentifier}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_PUT", base + "/participants/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_PUT_ERROR",
        base + "/participants/{{partyIdType}}/{{partyIdentifier}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT", base + "/participants/{{requestId}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT_ERROR", base + "/participants/{{requestId}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_QUOTES", base);
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_POST", base + "/transfers");
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_PUT", base + "/transfers/{{transferId}}");
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_ERROR", base + "/transfers/{{transferId}}/error");
    // bulk quotes (quoting-service) and bulk transfers (bulk-api-adapter)
    endpoints.put("FSPIOP_CALLBACK_URL_BULK_QUOTES", base);
    endpoints.put("FSPIOP_CALLBACK_URL_BULK_TRANSFER_POST", base + "/bulkTransfers");
    endpoints.put("FSPIOP_CALLBACK_URL_BULK_TRANSFER_PUT", base + "/bulkTransfers/{{id}}");
    endpoints.put("FSPIOP_CALLBACK_URL_BULK_TRANSFER_ERROR", base + "/bulkTransfers/{{id}}/error");
    return endpoints;
  }

  @Override
  public void onboard(Dfsp dfsp) {
    String admin = config.hub().adminUrl();
    String name = dfsp.getFspId();
    String currency = dfsp.getCurrency().name();
    ignoringExisting(() -> http.json("POST", admin + "/participants", Map.of("name", name, "currency", currency)));
    ignoringExisting(() -> http.json("POST", admin + "/participants/" + name + "/initialPositionAndLimits",
        Map.of("currency", currency,
            "limit", Map.of("type", "NET_DEBIT_CAP", "value", dfsp.getNetDebitCap()),
            "initialPosition", dfsp.getInitialPosition())));
    endpoints(trimSlash(dfsp.getSdkInboundUrl())).forEach((type, value) -> http.json("POST",
        admin + "/participants/" + name + "/endpoints", Map.of("type", type, "value", value)));
    http.json("PUT", admin + "/participants/" + name, Map.of("isActive", true));
  }

  @Override
  public void recordFundsIn(String fspId, BigDecimal amount, String currency) {
    String admin = config.hub().adminUrl();
    JsonNode accounts = http.json("GET", admin + "/participants/" + fspId + "/accounts", null);
    String settlementAccountId = null;
    BigDecimal balance = BigDecimal.ZERO;
    if (accounts != null) {
      for (JsonNode account : accounts) {
        if ("SETTLEMENT".equals(account.path("ledgerAccountType").asText())
            && currency.equals(account.path("currency").asText())) {
          settlementAccountId = account.path("id").asText();
          // central-ledger reports the value as a number or a numeric string
          String value = account.path("value").asText("0");
          balance = value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
        }
      }
    }
    if (settlementAccountId == null) {
      throw new HubCallException(404, "No " + currency + " settlement account for " + fspId + " in the hub");
    }
    // initial funds are recorded once, so onboarding again (e.g. to re-point endpoints) is safe
    if (balance.signum() != 0) {
      LOG.infof("Settlement account of %s already holds %s %s, not recording funds again", fspId, balance, currency);
      return;
    }
    http.json("POST", admin + "/participants/" + fspId + "/accounts/" + settlementAccountId, Map.of(
        "transferId", UUID.randomUUID().toString(),
        "externalReference", "dfsp-simulator",
        "action", "recordFundsIn",
        "reason", "initial funds",
        "amount", Map.of("amount", amount.stripTrailingZeros().toPlainString(), "currency", currency)));
  }

  @Override
  public void deregisterParty(String fspId, IdTypeEnum idType, String idValue) {
    try {
      http.fspiop("DELETE", participantsUrl(idType, idValue), "participants", fspId, null, null);
    } catch (HubCallException e) {
      LOG.warnf("Could not remove %s/%s from the ALS: %s", idType, idValue, e.getMessage());
    }
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private String participantsUrl(IdTypeEnum idType, String idValue) {
    return config.hub().alsUrl() + "/participants/" + idType.name() + "/"
        + URLEncoder.encode(idValue, StandardCharsets.UTF_8);
  }

  // central-ledger answers 400 with "already" in the message when the resource exists, and 500
  // "Participant Limit or Initial Position already set" for a repeated initialPositionAndLimits
  private static void ignoringExisting(Runnable call) {
    try {
      call.run();
    } catch (HubCallException e) {
      String message = e.getMessage().toLowerCase();
      boolean exists = (e.getStatus() == 400 || e.getStatus() == 409) && message.contains("already")
          || e.getStatus() == 500 && message.contains("already set");
      if (!exists) {
        throw e;
      }
    }
  }

}
