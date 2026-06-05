package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.QuotaReservation;

public record ResolvedCall(
    ResolvedCredential credential,
    QuotaReservation quotaReservation) {

  public String apiKey() {
    return credential.apiKey();
  }

  public CredentialSource credentialSource() {
    return credential.credentialSource();
  }

  public AccessLimits accessLimits() {
    return credential.limits();
  }
}
