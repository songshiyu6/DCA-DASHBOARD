package com.dca.terminal.security;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() { }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }

    public record SessionResponse(boolean authenticated, String username) { }

    public record CsrfResponse(String token, String headerName, String parameterName) { }
}
