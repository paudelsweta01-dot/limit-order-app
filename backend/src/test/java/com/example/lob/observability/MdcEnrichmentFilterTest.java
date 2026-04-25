package com.example.lob.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class MdcEnrichmentFilterTest {

    private final MdcEnrichmentFilter filter = new MdcEnrichmentFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void populatesRequestIdInsideChainAndClearsAfter() throws Exception {
        HttpServletRequest req = new MockHttpServletRequest();
        HttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        AtomicReference<String> seenInsideChain = new AtomicReference<>();
        doAnswer(invocation -> {
            seenInsideChain.set(MDC.get(MdcEnrichmentFilter.MDC_REQUEST_ID));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seenInsideChain.get()).isNotNull();
        assertThat(UUID.fromString(seenInsideChain.get())).isNotNull();
        assertThat(MDC.get(MdcEnrichmentFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        HttpServletRequest req = new MockHttpServletRequest();
        HttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("downstream boom")).when(chain).doFilter(req, res);

        assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream boom");

        assertThat(MDC.get(MdcEnrichmentFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(MdcEnrichmentFilter.MDC_USER_ID)).isNull();
    }
}
