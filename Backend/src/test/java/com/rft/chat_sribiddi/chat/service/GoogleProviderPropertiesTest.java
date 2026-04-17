package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GoogleProviderPropertiesTest {

  @Test
  void setTimeout_rejectsNullValue() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  void setTimeout_rejectsOverflowValue() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(Duration.ofDays(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be between 1ms");
  }
}
