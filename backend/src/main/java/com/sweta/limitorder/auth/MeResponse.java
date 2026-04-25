package com.sweta.limitorder.auth;

import java.util.UUID;

public record MeResponse(UUID userId, String name) {
}
