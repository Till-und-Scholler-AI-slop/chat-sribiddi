package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageEventServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private UsageEventRepository usageEventRepository;
  @Mock private ModelPricingLookup modelPricingLookup;

  @Test
  void record_persistsUsageEventWithEstimatedCost() {
    User user = new User();
    user.setId(42L);
    user.setPlan(Plan.PREMIUM);
    when(userRepository.findByProviderId("provider-user")).thenReturn(Optional.of(user));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o-mini"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.15, 0.60)));
    UsageEventService service =
        new UsageEventService(userRepository, usageEventRepository, modelPricingLookup);

    service.record(
        "provider-user",
        Provider.OPENROUTER,
        "openai/gpt-4o-mini",
        1_000_000L,
        500_000L,
        1_500_000L,
        99L,
        BillingMode.MANAGED);

    ArgumentCaptor<UsageEventEntity> eventCap = ArgumentCaptor.forClass(UsageEventEntity.class);
    verify(usageEventRepository).save(eventCap.capture());
    UsageEventEntity event = eventCap.getValue();
    assertThat(event.getUserId()).isEqualTo(42L);
    assertThat(event.getMessageId()).isEqualTo(99L);
    assertThat(event.getProvider()).isEqualTo(Provider.OPENROUTER);
    assertThat(event.getModelId()).isEqualTo("openai/gpt-4o-mini");
    assertThat(event.getPromptTokens()).isEqualTo(1_000_000L);
    assertThat(event.getCompletionTokens()).isEqualTo(500_000L);
    assertThat(event.getTotalTokens()).isEqualTo(1_500_000L);
    assertThat(event.getBillingMode()).isEqualTo(BillingMode.MANAGED);
    assertThat(event.getTierAtCall()).isEqualTo(Plan.PREMIUM);
    assertThat(event.getCostMicroCents()).isEqualTo(45_000_000L);
  }

  @Test
  void record_skipsWhenUserIsNotPersisted() {
    UsageEventService service =
        new UsageEventService(userRepository, usageEventRepository, modelPricingLookup);
    when(userRepository.findByProviderId("missing")).thenReturn(Optional.empty());

    service.record("missing", Provider.OPENAI, "gpt-5.2", 1L, 2L, 3L, null, BillingMode.BYOK);

    verify(usageEventRepository, never()).save(any());
  }
}
