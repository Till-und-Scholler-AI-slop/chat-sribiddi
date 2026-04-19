package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;

public interface UsageEventService {

  void record(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      Long messageId,
      BillingMode billingMode);
}
