package com.j8d.karate.intellij.licensing;

import org.jetbrains.annotations.Nullable;

/**
 * Response from /api/auth/github endpoint.
 */
public class AuthResponse {
    @Nullable public String userId;
    @Nullable public String githubUsername;
    @Nullable public String email;
}

