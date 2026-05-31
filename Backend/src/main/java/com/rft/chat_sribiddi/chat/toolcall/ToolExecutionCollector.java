package com.rft.chat_sribiddi.chat.toolcall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public class ToolExecutionCollector {

  private static final JsonMapper MAPPER = new JsonMapper();

  private final List<Record> records = Collections.synchronizedList(new ArrayList<>());

  public String record(String toolName, Map<String, Object> input, Supplier<String> invocation) {
    Instant startedAt = Instant.now();
    long startedNanos = System.nanoTime();
    try {
      String output = invocation.get();
      records.add(
          new Record(
              toolName, serialize(input), output, startedAt, elapsedMillis(startedNanos)));
      return output;
    } catch (RuntimeException ex) {
      records.add(
          new Record(
              toolName,
              serialize(input),
              "Tool execution failed: " + ex.getMessage(),
              startedAt,
              elapsedMillis(startedNanos)));
      throw ex;
    }
  }

  public boolean isEmpty() {
    return records.isEmpty();
  }

  public List<Record> snapshot() {
    synchronized (records) {
      return List.copyOf(records);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }

  private static String serialize(Map<String, Object> input) {
    try {
      return MAPPER.writeValueAsString(input == null ? Map.of() : input);
    } catch (JacksonException ex) {
      return "{}";
    }
  }

  public record Record(
      String toolName,
      String inputPayload,
      String outputPayload,
      Instant startedAt,
      Long durationMs) {}
}
