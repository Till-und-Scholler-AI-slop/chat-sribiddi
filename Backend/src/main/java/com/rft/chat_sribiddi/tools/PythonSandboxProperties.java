package com.rft.chat_sribiddi.tools;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.tools.python-sandbox")
public class PythonSandboxProperties {

  private String dockerBinary = "docker";
  private String image = "python:3.13-alpine";
  private Duration timeout = Duration.ofSeconds(10);
  private int maxOutputBytes = 10_240;
  private int maxCodeLength = 10_000;
  private String memory = "128m";
  private String cpus = "0.5";
  private int pidsLimit = 64;
  private String tmpfs = "/tmp:rw,noexec,nosuid,size=16m";
}
