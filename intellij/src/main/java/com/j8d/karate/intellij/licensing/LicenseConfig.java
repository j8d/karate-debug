package com.j8d.karate.intellij.licensing;

/**
 * License API configuration.
 * Values match the VS Code extension config.
 */
public final class LicenseConfig {

    private LicenseConfig() {}

    // Production API
    public static final String API_BASE_URL = "https://karate-debug-api.vercel.app/api";
    public static final String GITHUB_CLIENT_ID = "Ov23lilyMXLAitkqwqPL";

    // For development, use:
    // public static final String API_BASE_URL = "http://localhost:3000/api";
    // public static final String GITHUB_CLIENT_ID = "Ov23liVNnHehsgEqo38c";

    // Trial duration
    public static final int TRIAL_DAYS = 30;

    // Machine activation limit
    public static final int MAX_MACHINES = 5;
}

