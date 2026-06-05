package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.QuotaReservation;

public record ResolvedCall(
    String apiKey,
    CredentialSource credentialSource,
    AccessLimits accessLimits,
    QuotaReservation quotaReservation) {}
