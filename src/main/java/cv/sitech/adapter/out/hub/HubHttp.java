package cv.sitech.adapter.out.hub;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.config.SimulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;

/** Minimal JSON-over-HTTP client for the hub services the SDK doesn't cover. */
@ApplicationScoped
public class HubHttp {

  private static final Logger LOG = Logger.getLogger(HubHttp.class);

  /** IMF-fixdate (RFC 7231); unlike RFC_1123_DATE_TIME it zero-pads the day, which the hub's schema requires */
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

  // HTTP/1.1: the default HTTP/2 sends an h2c upgrade on plain http, and the ALS then accepts a callback (200)
  // but never forwards it
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper objectMapper;
  private final SimulatorConfig config;

  public HubHttp(ObjectMapper objectMapper, SimulatorConfig config) {
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public static final String SOURCE = "FSPIOP-Source";
  public static final String DESTINATION = "FSPIOP-Destination";

  /**
   * an FSPIOP request: versioned content type, Date, FSPIOP-Source and optional FSPIOP-Destination.
   * Only used for what the SDK can't do (removing an ALS registration); everything else goes through it.
   */
  public JsonNode fspiop(String method, String url, String resource, String source, String destination,
      Object body) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/vnd.interoperability." + resource + "+json;version=1.1");
    if (!"PUT".equals(method)) {
      // callbacks (PUT) carry no Accept header
      headers.put("Accept", "application/vnd.interoperability." + resource + "+json;version=1");
    }
    headers.put("Date", HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC)));
    headers.put(SOURCE, source);
    if (destination != null) {
      headers.put(DESTINATION, destination);
    }
    return send(method, url, headers, body);
  }

  /** a plain JSON admin request */
  public JsonNode json(String method, String url, Object body) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");
    return send(method, url, headers, body);
  }

  private JsonNode send(String method, String url, Map<String, String> headers, Object body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
    headers.forEach(request::header);
    config.hub().bearerToken().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(write(body)));

    HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new HubCallException(0, method + " " + url + " failed: "
          + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HubCallException(0, method + " " + url + " interrupted");
    }
    LOG.debugf("%s %s -> %d", method, url, response.statusCode());
    if (response.statusCode() / 100 != 2) {
      throw new HubCallException(response.statusCode(),
          method + " " + url + " returned " + response.statusCode() + ": " + response.body());
    }
    try {
      return response.body() == null || response.body().isBlank() ? null : objectMapper.readTree(response.body());
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private byte[] write(Object body) {
    try {
      return objectMapper.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

}
