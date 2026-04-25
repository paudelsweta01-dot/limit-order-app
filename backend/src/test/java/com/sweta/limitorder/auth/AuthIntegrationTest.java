package com.sweta.limitorder.auth;

import com.sweta.limitorder.TestcontainersConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 end-to-end auth flow against a real Postgres + the full Spring
 * Security filter chain.
 *
 * <p>Covers: login happy path, login failures (bad password, unknown user,
 * missing fields), and protected-endpoint access (no token, valid token,
 * tampered token, expired token).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Value("${app.jwt.signing-secret}")
    private String jwtSecret;

    // ---------- /api/auth/login ----------

    @Test
    void loginWithValidCredentialsReturnsToken() {
        ResponseEntity<LoginResponse> response = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("u1", "alice123"),
                LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.token()).isNotBlank();
        assertThat(body.token().split("\\.")).hasSize(3); // header.payload.signature
        assertThat(body.userId()).isNotNull();
        assertThat(body.name()).isEqualTo("Alice");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginWithWrongPasswordReturns401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("u1", "wrong-password"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginWithUnknownUsernameReturns401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("nobody", "anything"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginWithBlankFieldsReturns400Validation() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("", ""),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    // ---------- /api/me — JWT-protected ----------

    @Test
    @SuppressWarnings("unchecked")
    void protectedEndpointWithoutTokenReturns401() {
        ResponseEntity<Map> response = rest.getForEntity("/api/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithValidTokenReturnsUserDetails() {
        String token = login("u1", "alice123").token();

        ResponseEntity<MeResponse> response = rest.exchange(
                "/api/me", HttpMethod.GET,
                bearer(token),
                MeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MeResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.name()).isEqualTo("Alice");
        assertThat(body.userId()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void protectedEndpointWithTamperedTokenReturns401() {
        String token = login("u1", "alice123").token();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        ResponseEntity<Map> response = rest.exchange(
                "/api/me", HttpMethod.GET, bearer(tampered), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void protectedEndpointWithExpiredTokenReturns401() {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.NAME_CLAIM, "Ghost")
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        ResponseEntity<Map> response = rest.exchange(
                "/api/me", HttpMethod.GET, bearer(expired), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void differentUsersGetDifferentTokensAndIdentities() {
        LoginResponse alice = login("u1", "alice123");
        LoginResponse bob   = login("u2", "bob123");

        assertThat(alice.token()).isNotEqualTo(bob.token());
        assertThat(alice.userId()).isNotEqualTo(bob.userId());
        assertThat(alice.name()).isEqualTo("Alice");
        assertThat(bob.name()).isEqualTo("Bob");
    }

    // ---------- helpers ----------

    private LoginResponse login(String username, String password) {
        return rest.postForObject(
                "/api/auth/login",
                new LoginRequest(username, password),
                LoginResponse.class);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
