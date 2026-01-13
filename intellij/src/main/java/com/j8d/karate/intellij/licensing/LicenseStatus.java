package com.j8d.karate.intellij.licensing;

import org.jetbrains.annotations.Nullable;

/**
 * Represents the current license status.
 */
public class LicenseStatus {

    public enum Status {
        ACTIVE,      // Paid subscription active
        TRIALING,    // In trial period
        EXPIRED,     // Trial or subscription expired
        NONE         // No license info (offline on first install)
    }

    private final boolean valid;
    private final Status status;
    private final int daysRemaining;
    @Nullable private final String expiresAt;
    @Nullable private final String githubUsername;

    public LicenseStatus(boolean valid, Status status, int daysRemaining,
                         @Nullable String expiresAt, @Nullable String githubUsername) {
        this.valid = valid;
        this.status = status;
        this.daysRemaining = daysRemaining;
        this.expiresAt = expiresAt;
        this.githubUsername = githubUsername;
    }

    public static LicenseStatus none() {
        return new LicenseStatus(false, Status.NONE, 0, null, null);
    }

    public static LicenseStatus expired() {
        return new LicenseStatus(false, Status.EXPIRED, 0, null, null);
    }

    public static LicenseStatus trialing(int daysRemaining, @Nullable String expiresAt) {
        return new LicenseStatus(daysRemaining > 0, Status.TRIALING, daysRemaining, expiresAt, null);
    }

    public static LicenseStatus active(@Nullable String githubUsername) {
        return new LicenseStatus(true, Status.ACTIVE, 0, null, githubUsername);
    }

    public boolean isValid() {
        return valid;
    }

    public Status getStatus() {
        return status;
    }

    public int getDaysRemaining() {
        return daysRemaining;
    }

    @Nullable
    public String getExpiresAt() {
        return expiresAt;
    }

    @Nullable
    public String getGithubUsername() {
        return githubUsername;
    }

    @Override
    public String toString() {
        return "LicenseStatus{" +
                "valid=" + valid +
                ", status=" + status +
                ", daysRemaining=" + daysRemaining +
                ", expiresAt='" + expiresAt + '\'' +
                ", githubUsername='" + githubUsername + '\'' +
                '}';
    }
}

