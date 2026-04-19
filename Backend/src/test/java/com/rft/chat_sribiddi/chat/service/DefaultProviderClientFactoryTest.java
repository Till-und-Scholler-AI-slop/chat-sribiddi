package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultProviderClientFactoryTest {

  private DefaultProviderClientFactory factory;

  @BeforeEach
  void setUp() {
    OpenAiProviderProperties props = new OpenAiProviderProperties();
    factory =
        new DefaultProviderClientFactory(
            props, new GoogleProviderProperties(), new OpenRouterProviderProperties());
  }

  @Test
  void getClient_google_returnsInitializedClientHolder() {
    ChatService.ClientHolder holder = factory.getClient(Provider.GOOGLE, "fake-api-key");

    assertThat(holder).isNotNull();
    assertThat(holder.client()).isNotNull();
    assertThat(holder.closeable()).isNull();
  }

  @Test
  void getClient_google_cachedHolder_returnsSameInstanceForSameKey() {
    ChatService.ClientHolder first = factory.getClient(Provider.GOOGLE, "fake-api-key");
    first.close(); // must not destroy the cached ClientHolder

    ChatService.ClientHolder second = factory.getClient(Provider.GOOGLE, "fake-api-key");

    assertThat(second).isSameAs(first);
    assertThat(second.client()).isSameAs(first.client());
  }

  @Test
  void getClient_google_differentKeys_returnSeparateHoldersWithDistinctClients() {
    ChatService.ClientHolder holder1 = factory.getClient(Provider.GOOGLE, "key-alpha");
    ChatService.ClientHolder holder2 = factory.getClient(Provider.GOOGLE, "key-beta");

    assertThat(holder1).isNotSameAs(holder2);
    assertThat(holder1.client()).isNotSameAs(holder2.client());
  }

  @Test
  void buildOptions_openRouter_usesOpenAiCompatibleOptions() {
    var options = factory.buildOptions(Provider.OPENROUTER, "openai/gpt-4o-mini", "or-key");

    assertThat(options).isInstanceOf(OpenAiChatOptions.class);
    assertThat(((OpenAiChatOptions) options).getModel()).isEqualTo("openai/gpt-4o-mini");
  }

  @Test
  void buildGoogleHttpOptions_usesConfiguredTimeoutMillis() {
    OpenAiProviderProperties openAiProperties = new OpenAiProviderProperties();
    GoogleProviderProperties googleProperties = new GoogleProviderProperties();
    googleProperties.setTimeout(Duration.ofSeconds(123));
    DefaultProviderClientFactory customFactory =
        new DefaultProviderClientFactory(
            openAiProperties, googleProperties, new OpenRouterProviderProperties());

    var options = customFactory.buildGoogleHttpOptions();

    assertThat(options.timeout()).contains(123_000);
  }

  @Test
  void getClient_sameKey_differentBillingModes_returnSeparateHolders() {
    ChatService.ClientHolder byok =
        factory.getClient(Provider.GOOGLE, "shared-key", BillingMode.BYOK);
    ChatService.ClientHolder managed =
        factory.getClient(Provider.GOOGLE, "shared-key", BillingMode.MANAGED);

    assertThat(managed).isNotSameAs(byok);
    assertThat(managed.client()).isNotSameAs(byok.client());

    // Subsequent calls with the same mode+key must still hit the cached entry.
    ChatService.ClientHolder byokAgain =
        factory.getClient(Provider.GOOGLE, "shared-key", BillingMode.BYOK);
    assertThat(byokAgain).isSameAs(byok);
  }

  @Test
  void getClient_twoArgOverload_defaultsToByokCachePartition() {
    // The legacy 2-arg call site must land in the same cache entry as an explicit BYOK call
    // to guarantee the existing BYOK path is behaviourally unchanged.
    ChatService.ClientHolder legacy = factory.getClient(Provider.GOOGLE, "byok-key");
    ChatService.ClientHolder explicit =
        factory.getClient(Provider.GOOGLE, "byok-key", BillingMode.BYOK);

    assertThat(explicit).isSameAs(legacy);
  }

  @Test
  void getClient_nullBillingMode_throws() {
    assertThatThrownBy(() -> factory.getClient(Provider.OPENAI, "key", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
