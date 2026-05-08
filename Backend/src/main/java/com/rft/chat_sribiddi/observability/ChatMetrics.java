package com.rft.chat_sribiddi.observability;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
public class ChatMetrics {

  private static final String UNKNOWN = "unknown";

  private final MeterRegistry meterRegistry;
  private final AtomicLong activeStreams = new AtomicLong();

  public ChatMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder("chat.active.streams", activeStreams, AtomicLong::get)
        .description("Currently open chat streaming responses")
        .register(meterRegistry);
  }

  public void recordRequest(Provider provider, String modelId, Plan plan, String status) {
    Counter.builder("chat.requests.total")
        .description("Chat provider requests by provider, model, plan and status")
        .tag("provider", tag(provider))
        .tag("model", tag(modelId))
        .tag("plan", tag(plan))
        .tag("status", tag(status))
        .register(meterRegistry)
        .increment();
  }

  public void recordTokens(Provider provider, String modelId, String type, Long tokens) {
    if (tokens == null || tokens <= 0) {
      return;
    }
    Counter.builder("chat.tokens.total")
        .description("Chat tokens reported by providers")
        .tag("provider", tag(provider))
        .tag("model", tag(modelId))
        .tag("type", tag(type))
        .register(meterRegistry)
        .increment(tokens.doubleValue());
  }

  public StreamSample startStream(Provider provider, String modelId) {
    activeStreams.incrementAndGet();
    return new StreamSample(provider, modelId, Timer.start(meterRegistry));
  }

  public void recordStreamEnd(StreamSample sample) {
    if (sample == null || !sample.markEnded()) {
      return;
    }
    activeStreams.decrementAndGet();
    sample
        .sample()
        .stop(
            Timer.builder("chat.stream.duration")
                .description("Chat stream duration")
                .tag("provider", tag(sample.provider()))
                .tag("model", tag(sample.modelId()))
                .register(meterRegistry));
  }

  private static String tag(Provider provider) {
    return provider == null ? UNKNOWN : provider.name().toLowerCase(Locale.ROOT);
  }

  private static String tag(Plan plan) {
    return plan == null ? UNKNOWN : plan.name().toLowerCase(Locale.ROOT);
  }

  private static String tag(String value) {
    return value == null || value.isBlank() ? UNKNOWN : value.toLowerCase(Locale.ROOT);
  }

  @RequiredArgsConstructor
  public static final class StreamSample {
    private final Provider provider;
    private final String modelId;
    private final Timer.Sample sample;
    private final AtomicBoolean ended = new AtomicBoolean();

    Provider provider() {
      return provider;
    }

    String modelId() {
      return modelId;
    }

    Timer.Sample sample() {
      return sample;
    }

    boolean markEnded() {
      return ended.compareAndSet(false, true);
    }
  }
}
