package com.sweta.limitorder.simulator.api.dto;

import java.util.UUID;

/** Mirrors {@code com.sweta.limitorder.auth.LoginResponse}. */
public record LoginResponse(String token, UUID userId, String name) {}
