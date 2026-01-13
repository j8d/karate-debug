package com.j8d.karate.intellij.licensing;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Response from /api/license/validate endpoint.
 */
public class ValidateResponse {
    public boolean valid;
    @Nullable public String token;
    @Nullable public String status;       // "active", "trialing", "expired", "past_due", "canceled"
    @Nullable public String expiresAt;
    @Nullable public Integer daysRemaining;
    @Nullable public List<String> features;
}

