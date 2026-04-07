package com.rft.chat_sribiddi.tools.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Registry for all available chat tools. Tools register themselves via Spring injection.
 * Thread-safe: uses ConcurrentHashMap for concurrent read access.
 */
@Component
public class ToolRegistry {

  private final Map<String, ChatTool> tools = new ConcurrentHashMap<>();

  /** Register a tool. Called by tools that implement {@link ChatTool} and are Spring beans. */
  public void register(ChatTool tool) {
    tools.put(tool.getName(), tool);
  }

  /** Unregister a tool by name. */
  public void unregister(String name) {
    tools.remove(name);
  }

  /** Get a tool by name. */
  public Optional<ChatTool> get(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  /** Get all registered tools. */
  public Collection<ChatTool> getAll() {
    return List.copyOf(tools.values());
  }

  /** Get all registered tool names. */
  public Collection<String> getNames() {
    return List.copyOf(tools.keySet());
  }

  /** Check if a tool is registered. */
  public boolean has(String name) {
    return tools.containsKey(name);
  }
}
