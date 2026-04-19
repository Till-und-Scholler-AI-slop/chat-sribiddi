package com.rft.chat_sribiddi.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Server-side (MANAGED) API keys used by {@link BillingModeResolver} when a request is resolved to
 * {@link com.rft.chat_sribiddi.auth.BillingMode#MANAGED}. Values are sourced from
 * {@code application.yaml} under {@code app.billing.managed-keys.*}, which in turn read from the
 * conventional environment variables (e.g. {@code OPENAI_API_KEY}). A blank/missing value means
 * MANAGED mode is disabled for that provider.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.billing.managed-keys")
public class ManagedApiKeyProperties {

  private String openai = "";
  private String google = "";
  private String claude = "";
  private String openrouter = "";
}
