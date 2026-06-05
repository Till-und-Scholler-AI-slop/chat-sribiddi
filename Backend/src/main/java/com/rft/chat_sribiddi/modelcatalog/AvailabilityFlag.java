package com.rft.chat_sribiddi.modelcatalog;

/** Availability flag returned by {@code GET /models/*}. */
public enum AvailabilityFlag {
  READY,
  NEEDS_CONNECTION,
  NEEDS_LOCAL_ENDPOINT,
  EXPERIMENTAL,
  DISABLED,
  /** @deprecated kept only for old clients during the provider-connection rollout. */
  @Deprecated
  ALWAYS,
  /** @deprecated kept only for old clients during the provider-connection rollout. */
  @Deprecated
  BYOK_ONLY
}
