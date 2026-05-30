package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.ManagedQuotaReservation;

public record ResolvedCall(
    String apiKey,
    BillingMode billingMode,
    Entitlements entitlements,
    ManagedQuotaReservation quotaReservation) {}
