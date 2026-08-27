package com.dca.terminal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.dca.terminal.security.AuthDtos.CsrfResponse;
import static com.dca.terminal.security.AuthDtos.LoginRequest;
import static com.dca.terminal.security.AuthDtos.SessionResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final boolean securityEnabled;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          @Value("${dca.security.enabled:true}") boolean securityEnabled) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityEnabled = securityEnabled;
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication) {
        if (!securityEnabled || authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return new SessionResponse(false, null);
        }
        return new SessionResponse(true, authentication.getName());
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return token == null ? new CsrfResponse(null, "X-XSRF-TOKEN", "_csrf")
                : new CsrfResponse(token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (!securityEnabled) return new SessionResponse(true, request.username());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
            contextRepository.saveContext(context, httpRequest, httpResponse);
            return new SessionResponse(true, authentication.getName());
        } catch (BadCredentialsException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
