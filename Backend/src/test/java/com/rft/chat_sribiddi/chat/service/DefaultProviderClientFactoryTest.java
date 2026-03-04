package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultProviderClientFactoryTest {

  private DefaultProviderClientFactory factory;

  @BeforeEach
  void setUp() {
    OpenAiProviderProperties props = new OpenAiProviderProperties();
    factory = new DefaultProviderClientFactory(props);
  }

  @Test
  void getClient_google_returnsInitializedClientHolderWithNullCloseable() {
    ChatService.ClientHolder holder = factory.getClient(Provider.GOOGLE, "fake-api-key");

    assertThat(holder).isNotNull();
    assertThat(holder.client()).isNotNull();
    assertThat(holder.closeable()).isNull();
  }

  @Test
  void getClient_google_closeIsNoOp_andSubsequentCallSucceeds() throws Exception {
    // Simulate try-with-resources: close() must be a no-op so the cached Google client
    // remains open and reusable across multiple requests.
    ChatService.ClientHolder first = factory.getClient(Provider.GOOGLE, "fake-api-key");
    first.close(); // must not destroy the underlying cached Client

    ChatService.ClientHolder second = factory.getClient(Provider.GOOGLE, "fake-api-key");
    assertThat(second).isNotNull();
    assertThat(second.client()).isNotNull();
    assertThat(second.closeable()).isNull();
  }

  @Test
  void getClient_google_differentKeys_returnSeparateHoldersWithDistinctClients() {
    ChatService.ClientHolder holder1 = factory.getClient(Provider.GOOGLE, "key-alpha");
    ChatService.ClientHolder holder2 = factory.getClient(Provider.GOOGLE, "key-beta");

    assertThat(holder1).isNotSameAs(holder2);
    assertThat(holder1.client()).isNotSameAs(holder2.client());
  }
}
