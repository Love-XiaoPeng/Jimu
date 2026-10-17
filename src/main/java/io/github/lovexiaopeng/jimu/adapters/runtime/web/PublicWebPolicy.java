package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Exact operator domain permission plus a conservative public-unicast address policy. */
public final class PublicWebPolicy {
  private final Set<String> domains;

  public PublicWebPolicy(Set<String> domains) {
    for (String domain : domains) {
      if (!domain.equals(domain.toLowerCase(Locale.ROOT))
          || domain.length() > 253
          || !domain.matches("[a-z](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))
        throw new IllegalArgumentException(
            "Allowlist requires exact lower-case ASCII domain names");
      for (String label : domain.split("\\.")) {
        if (label.length() > 63) throw new IllegalArgumentException("Domain label too long");
      }
    }
    this.domains = Set.copyOf(domains);
  }

  public URI target(String url) {
    URI target = URI.create(url);
    if (!"https".equalsIgnoreCase(target.getScheme())
        || target.getHost() == null
        || !domains.contains(target.getHost().toLowerCase(Locale.ROOT))
        || target.getRawUserInfo() != null
        || target.getRawFragment() != null
        || (target.getPort() != -1 && target.getPort() != 443)
        || url.length() > 8192)
      throw new IllegalArgumentException("Public fetch requires an allowed HTTPS/443 domain");
    return target;
  }

  static InetAddress[] publicAnswers(InetAddress[] answers) {
    if (answers.length == 0) throw new IllegalArgumentException("No public DNS answers");
    for (InetAddress address : answers) {
      if (!publicAddress(address.getAddress()))
        throw new IllegalArgumentException("DNS answer is outside public unicast policy");
    }
    return answers.clone();
  }

  private static boolean publicAddress(byte[] bytes) {
    int a = bytes[0] & 255;
    int b = bytes[1] & 255;
    if (bytes.length == 4) {
      int c = bytes[2] & 255;
      return !(a == 0
          || a == 10
          || a == 127
          || a >= 224
          || (a == 100 && b >= 64 && b <= 127)
          || (a == 169 && b == 254)
          || (a == 172 && b >= 16 && b <= 31)
          || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
          || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
          || (a == 203 && b == 0 && c == 113));
    }
    // Only 2000::/3 global unicast. This also excludes mapped IPv4, NAT64 and local scopes.
    // Exclude IETF special-purpose 2001::/23, documentation and 6to4 transition ranges.
    int c = bytes[2] & 255;
    int d = bytes[3] & 255;
    return bytes.length == 16
        && (a & 0xe0) == 0x20
        && !(a == 0x20 && b == 1 && c <= 1)
        && !(a == 0x20 && b == 1 && c == 0x0d && d == 0xb8)
        && !(a == 0x20 && b == 2)
        && !(a == 0x3f && b == 0xff && (c & 0xf0) == 0);
  }
}
