package io.github.lovexiaopeng.jimu.adapters.runtime.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local fixture JWT verifier. Tenant and actor are exclusively from an operator-owned subject map.
 */
public final class SignedIdentity implements Authenticator {
  private final byte[] key;
  private final Map<String, Identity> subjects;

  public SignedIdentity(String secret, Map<String, Identity> subjects) {
    key = Base64.getDecoder().decode(secret);
    if (key.length < 32)
      throw new IllegalArgumentException("Signing key must be at least 256 bits");
    this.subjects = Map.copyOf(subjects);
  }

  @Override
  public Identity authenticate(String authorization) {
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 4096) throw new TaskFailure(401, "UNAUTHENTICATED");
    String[] parts = authorization.substring(7).split("\\.");
    if (parts.length != 3) throw new TaskFailure(401, "UNAUTHENTICATED");
    try {
      var mapper = new ObjectMapper();
      var header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
      var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] signature =
          mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!"HS256".equals(header.path("alg").asText())
          || !MessageDigest.isEqual(signature, Base64.getUrlDecoder().decode(parts[2]))
          || !"jimu-fixture".equals(claims.path("iss").asText())
          || !"jimu-api".equals(claims.path("aud").asText())
          || !claims.path("exp").canConvertToLong()
          || claims.path("exp").asLong() <= Instant.now().getEpochSecond())
        throw new TaskFailure(401, "UNAUTHENTICATED");
      Identity identity = subjects.get(claims.path("sub").asText());
      if (identity == null) throw new TaskFailure(401, "UNAUTHENTICATED");
      return identity;
    } catch (java.io.IOException | IllegalArgumentException | GeneralSecurityException e) {
      throw new TaskFailure(401, "UNAUTHENTICATED");
    }
  }
}
