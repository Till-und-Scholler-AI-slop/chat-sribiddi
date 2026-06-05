package com.rft.chat_sribiddi.chat.service;

import static org.mockito.Mockito.verify;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.UsageEventService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.ChatUsage;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatUsageRecorderTest {

  @Mock private UsageEventService usageEventService;
  @Mock private ChatMetrics chatMetrics;

  private ChatUsageRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new ChatUsageRecorder(usageEventService, chatMetrics);
  }

  @Test
  void recordUsage_successfulCallRecordsUsage() {
    TokenUsage tokenUsage = new TokenUsage(10L, 5L, 15L);

    recorder.recordUsage(
        new ChatUsage(
            "user-123",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            tokenUsage,
            42L,
            CredentialSource.USER_KEY,
            true));

    verify(chatMetrics).recordRequest(RequestDTO.Provider.OPENAI, "gpt-5.2", "success");
    verify(chatMetrics).recordTokens(RequestDTO.Provider.OPENAI, "gpt-5.2", "prompt", 10L);
    verify(chatMetrics).recordTokens(RequestDTO.Provider.OPENAI, "gpt-5.2", "completion", 5L);
    verify(usageEventService)
        .record(
            "user-123",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            10L,
            5L,
            15L,
            42L,
            CredentialSource.USER_KEY);
  }

  @Test
  void recordUsage_failedCallRecordsUsage() {
    TokenUsage tokenUsage = new TokenUsage(10L, 5L, 15L);

    recorder.recordUsage(
        new ChatUsage(
            "user-123",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            tokenUsage,
            null,
            CredentialSource.USER_KEY,
            false));

    verify(chatMetrics).recordRequest(RequestDTO.Provider.OPENAI, "gpt-5.2", "error");
    verify(usageEventService)
        .record(
            "user-123",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            10L,
            5L,
            15L,
            null,
            CredentialSource.USER_KEY);
  }
}
