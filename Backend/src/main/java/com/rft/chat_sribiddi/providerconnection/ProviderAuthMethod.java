package com.rft.chat_sribiddi.providerconnection;

public enum ProviderAuthMethod {
  API_KEY,
  OAUTH_PKCE,
  OAUTH_AUTH_CODE,
  DEVICE_CODE,
  SERVICE_ACCOUNT,
  LOCAL_ENDPOINT,
  OPENAI_COMPATIBLE,
  SUBSCRIPTION_CLI,
  CLI_SESSION,
  CUSTOM_HTTP
}
