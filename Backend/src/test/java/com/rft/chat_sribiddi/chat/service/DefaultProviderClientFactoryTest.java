package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultProviderClientFactoryTest {

  private DefaultProviderClientFactory factory;

  @BeforeEach
  void setUp() {
    OpenAiProviderProperties props = new OpenAiProviderProperties();
    factory = new DefaultProviderClientFactory(props, new OpenRouterProviderProperties());
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
}
