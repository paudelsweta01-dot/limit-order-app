package com.sweta.limitorder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class LobApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthEndpointReturnsUp() {
        Map<String, Object> body = rest.getForObject("/actuator/health", Map.class);
        assertThat(body).containsEntry("status", "UP");
    }
}
