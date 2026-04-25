package com.sweta.limitorder.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4.2 — pure unit tests; no Spring context.
 */
class JwtServiceTest {

    private static final String SECRET   = "unit-test-secret-32-bytes-long-aaaaa"; // ≥ 32 bytes
    private static final long   TTL_MIN  = 720;
    private static final SecretKey KEY   = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtService service = new JwtService(SECRET, TTL_MIN);

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtService("too-short", TTL_MIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void signAndVerifyRoundtripPreservesClaims() {
        UUID userId = UUID.randomUUID();
        String name = "Alice";

        String token = service.sign(userId, name);
        AuthenticatedUser verified = service.verify(token);

        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.name()).isEqualTo(name);
    }

    @Test
    void verifyRejectsExpiredToken() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.NAME_CLAIM, "Alice")
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verify(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void verifyRejectsTokenSignedWithDifferentKey() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "different-secret-also-32-bytes-long-bb".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.NAME_CLAIM, "Alice")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void verifyRejectsTamperedToken() {
        String token = service.sign(UUID.randomUUID(), "Alice");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRejectsMalformedToken() {
        assertThatThrownBy(() -> service.verify("not.a.jwt"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.verify(""))
                .isInstanceOf(IllegalArgumentException.class); // jjwt throws IAE on blank
    }
}
