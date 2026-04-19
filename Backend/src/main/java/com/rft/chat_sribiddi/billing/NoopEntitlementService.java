package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import org.springframework.stereotype.Service;

@Service
public class NoopEntitlementService implements EntitlementService {

  @Override
  public BillingMode assertCanUseModel(String userId, Provider provider, String modelId) {
    return BillingMode.BYOK;
  }

  @Override
  public void assertWithinDailyCap(String userId) {
    // no-op until BILL-4 implementation is wired
  }

  @Override
  public void incrementCounter(String userId) {
    // no-op until BILL-4 implementation is wired
  }
}
