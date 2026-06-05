package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

  private static final String USER_ID = "user-123";

  @Mock private UserRepository userRepository;
  @Mock private UsageEventRepository usageEventRepository;

  private UsageController controller;
  private MockHttpServletRequest request;
  private User user;

  @BeforeEach
  void setUp() {
    RequestUserResolver resolver =
        new RequestUserResolver(new MockEnvironment().withProperty("spring.profiles.active", "dev"));
    controller =
        new UsageController(
            userRepository,
            usageEventRepository,
            resolver,
            Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC));
    request = new MockHttpServletRequest();
    request.addHeader(RequestUserResolver.USER_ID_HEADER, USER_ID);
    user = new User();
    user.setId(42L);
    user.setProviderId(USER_ID);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));
  }

  @Test
  void usage_aggregatesByModelAndDay() {
    UsageEventEntity first = event("gpt-5.5", 10L, 20L, null, 300L, "2026-06-04T10:00:00Z");
    UsageEventEntity second = event("gpt-5.5", 5L, 5L, 10L, 100L, "2026-06-04T11:00:00Z");
    when(usageEventRepository.findForDashboard(
            user.getId(), Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-06T00:00:00Z")))
        .thenReturn(List.of(first, second));

    var response = controller.usage(null, request, "2026-06-01", "2026-06-05", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().totalCalls()).isEqualTo(2);
    assertThat(response.getBody().totalTokens()).isEqualTo(40);
    assertThat(response.getBody().totalCostMicroCents()).isEqualTo(400);
    assertThat(response.getBody().items()).hasSize(1);
    assertThat(response.getBody().items().getFirst().calls()).isEqualTo(2);
    assertThat(response.getBody().daily()).hasSize(1);
    assertThat(response.getBody().daily().getFirst().totalTokens()).isEqualTo(40);
  }

  @Test
  void usage_modelFilter_usesFilteredRepositoryQuery() {
    when(usageEventRepository.findForDashboardByModel(
            user.getId(), Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-06T00:00:00Z"), "gpt"))
        .thenReturn(List.of(event("gpt-5.5", 1L, 2L, 3L, 4L, "2026-06-04T10:00:00Z")));

    var response = controller.usage(null, request, "2026-06-01", "2026-06-05", " gpt ");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().items()).hasSize(1);
    assertThat(response.getBody().items().getFirst().modelId()).isEqualTo("gpt-5.5");
  }

  private static UsageEventEntity event(
      String modelId,
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      Long costMicroCents,
      String createdAt) {
    UsageEventEntity event = new UsageEventEntity();
    event.setProvider(Provider.OPENAI);
    event.setModelId(modelId);
    event.setPromptTokens(promptTokens);
    event.setCompletionTokens(completionTokens);
    event.setTotalTokens(totalTokens);
    event.setCostMicroCents(costMicroCents);
    event.setCreatedAt(Instant.parse(createdAt));
    return event;
  }
}
