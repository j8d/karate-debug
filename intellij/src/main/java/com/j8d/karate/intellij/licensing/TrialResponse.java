package com.j8d.karate.intellij.licensing;

import org.jetbrains.annotations.Nullable;

/**
 * Response from /api/trial/start endpoint.
 */
public class TrialResponse {
    @Nullable public String status;      // "trialing", "expired", "converted", "none"
    @Nullable public String startedAt;
    @Nullable public String expiresAt;
    @Nullable public Integer daysRemaining;
    @Nullable public Boolean isNew;
    @Nullable public String convertedUserId;
}

