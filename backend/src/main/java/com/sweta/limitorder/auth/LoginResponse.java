package com.sweta.limitorder.auth;

import java.util.UUID;

public record LoginResponse(String token, UUID userId, String name) {
}
