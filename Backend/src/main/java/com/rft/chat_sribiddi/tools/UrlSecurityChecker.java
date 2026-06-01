package com.rft.chat_sribiddi.tools;

import java.net.URI;

/**
 * Validates a URL before it is fetched, mitigating server-side request forgery (SSRF).
 *
 * <p>Implementations should resolve the host's DNS, block private/reserved/loopback addresses,
 * and (optionally) pin the connection to the resolved address to prevent DNS rebinding.
 *
 * <p>Throw {@link SecurityException} for blocked URLs. The {@link UrlFetcherTool} catches it and
 * returns a sanitized error message to the model.
 */
public interface UrlSecurityChecker {

  /**
   * Validate the URL scheme, host, and resolved addresses. Implementations may also pin the
   * connection to a resolved IP to mitigate DNS rebinding (TOCTOU).
   *
   * @param uri the URI to validate
   * @throws SecurityException if the URL is not safe to fetch
   */
  void validateAndResolve(URI uri);
}
