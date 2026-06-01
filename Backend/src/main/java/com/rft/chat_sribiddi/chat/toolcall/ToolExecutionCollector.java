package com.rft.chat_sribiddi.chat.toolcall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public class ToolExecutionCollector {

  private static final JsonMapper MAPPER = new JsonMapper();

  private final List<Record> records = Collections.synchronizedList(new ArrayList<>());
  private volatile Consumer<Event> eventSink = _ -> {};

  public void setEventSink(Consumer<Event> eventSink) {
    this.eventSink = eventSink == null ? _ -> {} : eventSink;
  }

  public String record(String toolName, Map<String, Object> input, Supplier<String> invocation) {
    return record(toolName, null, input, invocation);
  }

  public String record(
      String toolName, String displayType, Map<String, Object> input, Supplier<String> invocation) {
    String clientId = UUID.randomUUID().toString();
    String inputPayload = serialize(input);
    Instant startedAt = Instant.now();
    long startedNanos = System.nanoTime();
    emit(
        new Event(
            clientId,
            toolName,
            ToolCallStatus.RUNNING,
            inputPayload,
            null,
            startedAt,
            null,
            null,
            displayType));
    try {
      String output = invocation.get();
      Record record =
          new Record(
              clientId,
              toolName,
              ToolCallStatus.SUCCESS,
              inputPayload,
              output,
              null,
              startedAt,
              elapsedMillis(startedNanos),
              displayType);
      records.add(record);
      emit(Event.from(record));
      return output;
    } catch (RuntimeException ex) {
      Record record =
          new Record(
              clientId,
              toolName,
              ToolCallStatus.FAILED,
              inputPayload,
              null,
              ex.getMessage(),
              startedAt,
              elapsedMillis(startedNanos),
              displayType);
      records.add(record);
      emit(Event.from(record));
      throw ex;
    }
  }

  public void recordSynthetic(
      String toolName, String displayType, Map<String, Object> input, String outputPayload) {
    Instant startedAt = Instant.now();
    Record record =
        new Record(
            UUID.randomUUID().toString(),
            toolName,
            ToolCallStatus.SUCCESS,
            serialize(input),
            outputPayload,
            null,
            startedAt,
            0L,
            displayType);
    records.add(record);
    emit(Event.from(record));
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

  private void emit(Event event) {
    eventSink.accept(event);
  }

  public record Record(
      String clientId,
      String toolName,
      ToolCallStatus status,
      String inputPayload,
      String outputPayload,
      String errorMessage,
      Instant startedAt,
      Long durationMs,
      String displayType) {}

  public record Event(
      String clientId,
      String toolName,
      ToolCallStatus status,
      String inputPayload,
      String outputPayload,
      Instant startedAt,
      Long durationMs,
      String errorMessage,
      String displayType) {

    static Event from(Record record) {
      return new Event(
          record.clientId(),
          record.toolName(),
          record.status(),
          record.inputPayload(),
          record.outputPayload(),
          record.startedAt(),
          record.durationMs(),
          record.errorMessage(),
          record.displayType());
    }
  }
}
