package com.rft.chat_sribiddi.access;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.access")
public class AccessProperties {

  /** Maximum server-managed OpenRouter Free calls per anonymous browser/IP per UTC day. */
  private long guestDailyCap = 20L;

  /** Limits for authenticated BYOK chats. */
  private Limits authenticated = new Limits();

  /** Limits for anonymous OpenRouter Free chats. */
  private Limits guest = new Limits();

  @PostConstruct
  void validate() {
    if (guestDailyCap <= 0) {
      throw new IllegalStateException(
          "app.access.guest-daily-cap must be greater than 0 (was " + guestDailyCap + ")");
    }
  }

  public AccessLimits authenticatedLimits() {
    return authenticated.toAccessLimits();
  }

  public AccessLimits guestLimits() {
    return guest.toAccessLimits();
  }

  @Getter
  @Setter
  public static class Limits {
    private int maxAttachments = 5;
    private long maxAttachmentBytes = 5_242_880L;
    private long maxTotalAttachmentBytes = 20_971_520L;
    private int contextMessageLimit = 20;
    private Set<String> tools = new LinkedHashSet<>(Set.of("*"));
    private int parallelStreams = 1;

    AccessLimits toAccessLimits() {
      return new AccessLimits(
          maxAttachments,
          maxAttachmentBytes,
          maxTotalAttachmentBytes,
          contextMessageLimit,
          tools == null ? Set.of() : new HashSet<>(tools),
          parallelStreams);
    }
  }
}
