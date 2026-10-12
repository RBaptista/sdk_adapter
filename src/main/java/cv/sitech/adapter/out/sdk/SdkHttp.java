package cv.sitech.adapter.out.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.config.SimulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;

/** Plain JSON client for the SDK outbound API. Non-2xx answers are returned, not thrown: their bodies carry the error. */
@ApplicationScoped
public class SdkHttp {

  private static final Logger LOG = Logger.getLogger(SdkHttp.class);

  public record SdkResponse(int status, JsonNode body) {
    public boolean ok() {
      return status / 100 == 2;
    }
  }

  // HTTP/1.1 like HubHttp: the SDK's Koa server doesn't need h2c upgrades on plain http
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper objectMapper;
  private final SimulatorConfig config;

  public SdkHttp(ObjectMapper objectMapper, SimulatorConfig config) {
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public SdkResponse send(String method, String url, Object body) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(config.sdk().requestTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(write(body)))
        .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new SdkCallException(504, method + " " + url + " got no answer within "
          + config.sdk().requestTimeout().toSeconds() + "s");
    } catch (IOException e) {
      throw new SdkCallException(0, method + " " + url + " failed: "
          + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SdkCallException(0, method + " " + url + " interrupted");
    }
    LOG.debugf("%s %s -> %d", method, url, response.statusCode());
    return new SdkResponse(response.statusCode(), read(response.body()));
  }

  private JsonNode read(String body) {
    if (body == null || body.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException e) {
      return objectMapper.createObjectNode().put("message", body);
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
