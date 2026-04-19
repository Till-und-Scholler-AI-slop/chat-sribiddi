package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;

public interface EntitlementService {

  BillingMode assertCanUseModel(String userId, Provider provider, String modelId);

  void assertWithinDailyCap(String userId);

  void incrementCounter(String userId);
}
