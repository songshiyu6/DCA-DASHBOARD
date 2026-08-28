package com.dca.terminal.security;

import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dca.terminal.common.ApiExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.ProblemDetail;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class AuthControllerSessionTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationManager authenticationManager;

    @SpyBean
    private SecurityContextRepository contextRepository;

    @MockBean
    private LoginThrottle loginThrottle;

    @Test
    void rotatesSessionThroughTheStandardAuthenticationStrategyBeforeSavingContext() throws Exception {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "demo", null, java.util.List.of());
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(loginThrottle.allowAttempt(any(), any())).thenReturn(true);
        MockHttpSession existingSession = new MockHttpSession(null, "pre-auth");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(existingSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDtos.LoginRequest("demo", "password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("demo"))
                .andReturn();

        assertNotEquals("pre-auth", result.getRequest().getSession(false).getId());
        verify(contextRepository).saveContext(any(), any(), any());
    }

    @Test
    void logoutExpiresCsrfCookieAndInvalidatesExistingSessionThroughTheFilterChain() throws Exception {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "demo", null, java.util.List.of());
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(loginThrottle.allowAttempt(any(), any())).thenReturn(true);
        MockHttpSession existingSession = new MockHttpSession(null, "pre-auth");

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .session(existingSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDtos.LoginRequest("demo", "password"))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(authenticatedSession);
        assertNotEquals("pre-auth", authenticatedSession.getId());

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .session(authenticatedSession)
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertTrue(authenticatedSession.isInvalid());
        Cookie expiredCookie = logout.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(expiredCookie);
        assertEquals(0, expiredCookie.getMaxAge());
    }

    @Test
    void protectsApplicationMutationWithCsrfAndRejectsAnonymousReads() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(put("/api/v1/settings").with(user("demo")))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfCookieUsesProductionSecureAndSameSiteFlagsWithoutHttpOnly() {
        CsrfTokenRepository repository = new SecurityConfig().csrfTokenRepository(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertFalse(cookie.isHttpOnly());
    }

    @Test
    void loginThrottleRejectsExcessAttemptsThroughTheFilterChain() throws Exception {
        when(loginThrottle.allowAttempt(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDtos.LoginRequest("demo", "password"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many login attempts"))
                .andExpect(jsonPath("$.detail").value(not(containsString("jdbc"))));
    }

    @Test
    void loginFailuresKeepPublicReasonWithoutInternalExceptionText() throws Exception {
        when(loginThrottle.allowAttempt(any(), any())).thenReturn(true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("jdbc password=secret providerKey=private stacktrace"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDtos.LoginRequest("demo", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.detail").value(not(containsString("secret"))))
                .andExpect(jsonPath("$.detail").value(not(containsString("providerKey"))));
    }

    @Test
    void loginThrottleAllowsConfiguredBoundaryResetsOnSuccessAndEvictsExpiredWindows() {
        TestClock clock = new TestClock(Instant.parse("2026-08-28T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock, 2, 60);

        assertTrue(throttle.allowAttempt("demo", "192.0.2.1"));
        assertTrue(throttle.allowAttempt("demo", "192.0.2.1"));
        assertFalse(throttle.allowAttempt("demo", "192.0.2.1"));
        assertEquals(1, throttle.trackedWindows());

        throttle.successfulLogin("demo", "192.0.2.1");
        assertEquals(0, throttle.trackedWindows());
        assertTrue(throttle.allowAttempt("demo", "192.0.2.1"));
        assertEquals(1, throttle.trackedWindows());

        clock.advance(Duration.ofSeconds(60));
        assertTrue(throttle.allowAttempt("demo", "192.0.2.2"));
        assertEquals(1, throttle.trackedWindows());
    }

    @Test
    void problemDetailsDoNotEchoInternalExceptionMessages() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        String internalMessage = "jdbc password=secret providerKey=private stacktrace";
        ConstraintViolation<?> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
        when(violation.toString()).thenReturn(internalMessage);

        ProblemDetail constraint = handler.constraint(new ConstraintViolationException(Set.of(violation)), request);
        ProblemDetail illegal = handler.illegalArgument(new IllegalArgumentException(internalMessage), request);
        ProblemDetail responseStatus = handler.responseStatus(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, internalMessage), request);

        assertEquals("Request validation failed", constraint.getDetail());
        assertEquals("Request contains an invalid value", illegal.getDetail());
        assertEquals("Request rejected", responseStatus.getDetail());
        assertFalse(constraint.toString().contains("secret"));
        assertFalse(illegal.toString().contains("providerKey"));
        assertFalse(responseStatus.toString().contains("stacktrace"));
    }

    private static final class TestClock extends Clock {
        private Instant current;

        private TestClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
