package com.sweta.limitorder.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final BookWsHandler bookHandler;
    private final OrdersWsHandler ordersHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(bookHandler, "/ws/book/*")
                .addInterceptors(jwtHandshakeInterceptor)
                // FE may run on a different origin in dev (Angular ng serve on :4200);
                // nginx serves both same-origin in compose so this is dev-only ergonomics.
                .setAllowedOriginPatterns("*");

        registry
                .addHandler(ordersHandler, "/ws/orders/mine")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
