package com.rft.chat_sribiddi.providers;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.provider-gateway")
public class ProviderGatewayProperties {
  private boolean enabled = false;
  private String baseUrl = "http://127.0.0.1:8095";
}
