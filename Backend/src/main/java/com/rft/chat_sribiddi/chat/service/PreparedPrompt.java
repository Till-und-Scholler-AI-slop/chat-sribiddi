package com.rft.chat_sribiddi.chat.service;

import org.springframework.ai.chat.client.ChatClient;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;

public record PreparedPrompt(
    ChatClient.ChatClientRequestSpec promptSpec, ToolExecutionCollector toolExecutionCollector) {}
