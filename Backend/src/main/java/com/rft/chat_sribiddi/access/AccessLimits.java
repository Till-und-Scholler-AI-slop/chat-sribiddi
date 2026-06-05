package com.rft.chat_sribiddi.access;

import java.util.Set;

public record AccessLimits(
    int maxAttachments,
    long maxAttachmentBytes,
    long maxTotalAttachmentBytes,
    int contextMessageLimit,
    Set<String> allowedTools,
    int parallelStreams) {

  public boolean allowsTool(String toolId) {
    if (toolId == null || toolId.isBlank() || allowedTools == null) {
      return false;
    }
    return allowedTools.contains("*") || allowedTools.contains(toolId);
  }
}
