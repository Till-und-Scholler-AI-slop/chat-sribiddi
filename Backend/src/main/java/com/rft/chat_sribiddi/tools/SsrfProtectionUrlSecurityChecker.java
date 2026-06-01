package com.rft.chat_sribiddi.tools;

import java.net.InetAddress;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Default {@link UrlSecurityChecker} that blocks URLs targeting private, loopback, link-local, or
 * otherwise reserved IP ranges. Both IPv4 and IPv6 are checked.
 *
 * <p>DNS is resolved at validation time. The JDK {@link java.net.http.HttpClient} performs a
 * second resolution at request time — a known TOCTOU window for DNS rebinding attacks. The risk is
 * accepted as residual because:
 *
 * <ul>
 *   <li>DNS rebinding requires attacker control of the authoritative DNS server and sub-millisecond
 *       timing.
 *   <li>Private/loopback addresses are blocked on every redirect.
 *   <li>Per-user rate limiting caps blast radius.
 * </ul>
 *
 * <p>For full TOCTOU elimination, swap this bean for one that uses OkHttp's {@code Dns} interface
 * or Apache HttpClient's {@code DnsResolver}.
 */
@Component
public class SsrfProtectionUrlSecurityChecker implements UrlSecurityChecker {

  @Override
  public void validateAndResolve(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new SecurityException("Only http and https protocols are allowed.");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SecurityException("URL must have a host.");
    }
    String lowerHost = host.toLowerCase();
    if (lowerHost.equals("localhost")
        || lowerHost.endsWith(".localhost")
        || lowerHost.equals("ip6-localhost")
        || lowerHost.equals("ip6-loopback")) {
      throw new SecurityException("Localhost URLs are not allowed.");
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      if (addresses.length == 0) {
        throw new SecurityException("Could not resolve host: " + host);
      }
      for (InetAddress addr : addresses) {
        if (isPrivateIp(addr)) {
          throw new SecurityException("URL resolves to a private/reserved IP address.");
        }
      }
    } catch (SecurityException e) {
      throw e;
    } catch (Exception e) {
      throw new SecurityException("Could not resolve host: " + host);
    }
  }

  private boolean isPrivateIp(InetAddress addr) {
    if (addr.isLoopbackAddress()
        || addr.isSiteLocalAddress()
        || addr.isLinkLocalAddress()
        || addr.isAnyLocalAddress()) {
      return true;
    }
    byte[] bytes = addr.getAddress();
    if (bytes.length == 4) {
      int b0 = bytes[0] & 0xFF;
      int b1 = bytes[1] & 0xFF;
      if (b0 == 127 || b0 == 10 || b0 == 0) return true;
      if (b0 == 172 && (b1 & 0xF0) == 16) return true;
      if (b0 == 192 && b1 == 168) return true;
      if (b0 == 169 && b1 == 254) return true;
      if (b0 == 100 && (b1 & 0xC0) == 64) return true; // 100.64.0.0/10 carrier-grade NAT
      if (b0 >= 224) return true; // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved
    } else if (bytes.length == 16) {
      int b0 = bytes[0] & 0xFF;
      if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7 unique local
      if ((b0 & 0xC0) == 0x80) return true; // fe80::/10 link-local (covered by isLinkLocalAddress)
    }
    return false;
  }
}
