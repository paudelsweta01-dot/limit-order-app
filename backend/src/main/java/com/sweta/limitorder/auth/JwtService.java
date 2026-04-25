package com.sweta.limitorder.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT signer/verifier for the auth layer.
 *
 * <p>HS256, configurable TTL, secret driven by {@code app.jwt.signing-secret}.
 * The secret must be at least 32 bytes (256 bits) — the HS256 spec requires
 * it, and {@link Keys#hmacShaKeyFor} throws if it isn't.
 *
 * <p>Tokens carry the userId in the {@code sub} claim and the user's display
 * name in a custom {@code name} claim. The matching engine never reads the
 * token; it relies on the {@link JwtAuthFilter} populating the security
 * context.
 */
@Service
public class JwtService {

    static final String NAME_CLAIM = "name";

    private final SecretKey signingKey;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.signing-secret}") String secret,
                      @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.signing-secret must be at least 32 bytes (256 bits) for HS256; got "
                            + secretBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String sign(UUID userId, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(NAME_CLAIM, name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies the token's signature and expiry, returning the extracted claims.
     * Throws {@link io.jsonwebtoken.JwtException} (or a more specific subclass)
     * on any failure — the {@link JwtAuthFilter} catches that and leaves the
     * security context unauthenticated.
     */
    public AuthenticatedUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        UUID userId = UUID.fromString(claims.getSubject());
        String name = claims.get(NAME_CLAIM, String.class);
        return new AuthenticatedUser(userId, name);
    }
}
