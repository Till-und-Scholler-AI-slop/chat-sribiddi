package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import org.springframework.stereotype.Service;

@Service
public class NoopUsageEventService implements UsageEventService {

  @Override
  public void record(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      Long messageId,
      BillingMode billingMode) {
    // no-op until BILL-4 implementation is wired
  }
}
