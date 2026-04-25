package com.sweta.limitorder.auth;

import com.sweta.limitorder.persistence.User;
import com.sweta.limitorder.persistence.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String UNIFIED_FAILURE_MESSAGE = "invalid username or password";

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        // Same exception for "user not found" and "wrong password" so an
        // attacker can't enumerate usernames via response timing or content.
        User user = users.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException(UNIFIED_FAILURE_MESSAGE));

        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            log.info("event=LOGIN_FAILED username={}", request.username());
            throw new BadCredentialsException(UNIFIED_FAILURE_MESSAGE);
        }

        String token = jwt.sign(user.getUserId(), user.getDisplayName());
        log.info("event=LOGIN_SUCCESS userId={} username={}",
                user.getUserId(), user.getUsername());
        return new LoginResponse(token, user.getUserId(), user.getDisplayName());
    }
}
