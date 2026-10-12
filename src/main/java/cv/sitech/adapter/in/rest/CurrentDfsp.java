package cv.sitech.adapter.in.rest;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.ForbiddenException;

/** The DFSP the logged-in Keycloak user belongs to, from the token's {@code fsp_id} claim. */
@RequestScoped
public class CurrentDfsp {

  public static final String CLAIM = "fsp_id";

  private final JsonWebToken token;

  public CurrentDfsp(JsonWebToken token) {
    this.token = token;
  }

  public String fspId() {
    Object claim = token.getClaim(CLAIM);
    if (claim == null || claim.toString().isBlank()) {
      throw new ForbiddenException("Token has no " + CLAIM + " claim");
    }
    return claim.toString();
  }

}
