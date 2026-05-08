package com.rft.chat_sribiddi.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ChatMetricsTest {

  @Test
  void recordRequestAndTokens_registersTaggedCounters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChatMetrics metrics = new ChatMetrics(registry);

    metrics.recordRequest(Provider.OPENAI, "gpt-5.2", Plan.PREMIUM, "success");
    metrics.recordTokens(Provider.OPENAI, "gpt-5.2", "prompt", 50L);
    metrics.recordTokens(Provider.OPENAI, "gpt-5.2", "completion", 20L);

    assertThat(
            registry
                .find("chat.requests.total")
                .tag("provider", "openai")
                .tag("model", "gpt-5.2")
                .tag("plan", "premium")
                .tag("status", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("chat.tokens.total")
                .tag("type", "prompt")
                .counter()
                .count())
        .isEqualTo(50.0);
    assertThat(
            registry
                .find("chat.tokens.total")
                .tag("type", "completion")
                .counter()
                .count())
        .isEqualTo(20.0);
  }

  @Test
  void streamSample_tracksActiveStreamsAndDurationOnce() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChatMetrics metrics = new ChatMetrics(registry);

    ChatMetrics.StreamSample sample = metrics.startStream(Provider.GOOGLE, "gemini");

    assertThat(registry.find("chat.active.streams").gauge().value()).isEqualTo(1.0);

    metrics.recordStreamEnd(sample);
    metrics.recordStreamEnd(sample);

    assertThat(registry.find("chat.active.streams").gauge().value()).isEqualTo(0.0);
    assertThat(
            registry
                .find("chat.stream.duration")
                .tag("provider", "google")
                .tag("model", "gemini")
                .timer()
                .count())
        .isEqualTo(1L);
  }
}
