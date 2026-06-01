package com.rft.chat_sribiddi.connectors;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.connectors")
public class ConnectorProperties {

  private Google google = new Google();

  @Getter
  @Setter
  public static class Google {
    private String pickerDeveloperKey;
    private String pickerAppId;
    private boolean gmailEnabled = false;
  }
}
