package com.sweta.limitorder.api.symbols;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.auth.LoginRequest;
import com.sweta.limitorder.auth.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class SymbolControllerIntegrationTest {

    private static final ParameterizedTypeReference<List<SymbolResponse>> SYMBOL_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate rest;

    private String token;

    @BeforeEach
    void setUp() {
        token = Objects.requireNonNull(rest.postForObject(
                "/api/auth/login",
                new LoginRequest("u1", "alice123"),
                LoginResponse.class)).token();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolsWithoutAuthReturns401() {
        ResponseEntity<Map> r = rest.getForEntity("/api/symbols", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getSymbolsReturnsAllFiveSeededSymbols() {
        ResponseEntity<List<SymbolResponse>> r = rest.exchange(
                "/api/symbols", HttpMethod.GET, bearer(token), SYMBOL_LIST);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(5);

        // Sorted alphabetically by symbol
        assertThat(r.getBody())
                .extracting(SymbolResponse::symbol)
                .containsExactly("AAPL", "AMZN", "GOOGL", "MSFT", "TSLA");

        // Reference prices match spec §5.1
        SymbolResponse aapl = r.getBody().stream()
                .filter(s -> s.symbol().equals("AAPL"))
                .findFirst().orElseThrow();
        assertThat(aapl.name()).isEqualTo("Apple Inc.");
        assertThat(aapl.refPrice()).isEqualByComparingTo(new BigDecimal("180.00"));
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
