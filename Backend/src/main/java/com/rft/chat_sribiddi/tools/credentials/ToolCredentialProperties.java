package com.rft.chat_sribiddi.tools.credentials;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.tools.credentials")
public class ToolCredentialProperties {

  private String openaiImageApiKey;
  private String openaiImageModel = "gpt-image-1";
  private String githubToken;
  private String githubClientId;
  private String githubClientSecret;
  private String githubRedirectUri;
}
