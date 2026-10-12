package cv.sitech.adapter.out.hub;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import cv.sitech.application.port.out.HubSettlementGateway;
import cv.sitech.config.SimulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CentralSettlementClient implements HubSettlementGateway {

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private final HubHttp http;
  private final SimulatorConfig config;

  public CentralSettlementClient(HubHttp http, SimulatorConfig config) {
    this.http = http;
    this.config = config;
  }

  @Override
  public List<HubPosition> positions(String fspId) {
    JsonNode body = http.json("GET", config.hub().adminUrl() + "/participants/" + fspId + "/positions", null);
    List<HubPosition> positions = new ArrayList<>();
    if (body != null) {
      for (JsonNode p : body.isArray() ? body : List.of(body)) {
        positions.add(new HubPosition(p.path("currency").asText(), p.path("value").decimalValue()));
      }
    }
    return positions;
  }

  @Override
  public List<HubSettlement> settlementsSince(Instant from) {
    JsonNode body;
    try {
      body = http.json("GET", config.hub().settlementUrl() + "/settlements?fromDateTime="
          + URLEncoder.encode(TIMESTAMP.format(from), StandardCharsets.UTF_8), null);
    } catch (HubCallException e) {
      // central-settlement answers 4xx when nothing matches
      if (e.getStatus() / 100 == 4) {
        return List.of();
      }
      throw e;
    }
    if (body == null || !body.isArray() || body.isEmpty()) {
      return List.of();
    }
    Map<String, String> participantByAccount = participantsByAccount();
    List<HubSettlement> settlements = new ArrayList<>();
    for (JsonNode s : body) {
      List<HubWindow> windows = new ArrayList<>();
      for (JsonNode w : s.path("settlementWindows")) {
        windows.add(new HubWindow(w.path("id").asText(), instant(text(w, "createdDate")),
            instant(text(w, "changedDate"))));
      }
      List<HubNet> nets = new ArrayList<>();
      for (JsonNode participant : s.path("participants")) {
        for (JsonNode account : participant.path("accounts")) {
          JsonNode net = account.path("netSettlementAmount");
          String fspId = participantByAccount.get(account.path("id").asText());
          if (fspId != null && !net.isMissingNode()) {
            nets.add(new HubNet(fspId, net.path("currency").asText(), new BigDecimal(net.path("amount").asText())));
          }
        }
      }
      settlements.add(new HubSettlement(s.path("id").asText(), s.path("state").asText(), windows, nets));
    }
    return settlements;
  }

  // settlement participants reference participant-currency (account) ids, not names
  private Map<String, String> participantsByAccount() {
    JsonNode participants = http.json("GET", config.hub().adminUrl() + "/participants", null);
    Map<String, String> byAccount = new HashMap<>();
    if (participants != null) {
      for (JsonNode p : participants) {
        for (JsonNode account : p.path("accounts")) {
          byAccount.put(account.path("id").asText(), p.path("name").asText());
        }
      }
    }
    return byAccount;
  }

  private static Instant instant(String timestamp) {
    return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }

}
