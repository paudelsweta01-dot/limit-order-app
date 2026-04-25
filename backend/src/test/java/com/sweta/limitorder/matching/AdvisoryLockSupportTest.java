package com.sweta.limitorder.matching;

import com.sweta.limitorder.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3.2 acceptance: throws if no transaction is active; happy-path
 * passes a smoke test.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class AdvisoryLockSupportTest {

    @Autowired
    private AdvisoryLockSupport locks;

    @Test
    void throwsWhenNoTransactionIsActive() {
        assertThatThrownBy(() -> locks.lockForSymbol("AAPL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    @Test
    @Transactional
    void acquiresTheLockInsideAnActiveTransaction() {
        // No exception means the SQL ran successfully against the real DB.
        // The lock is released automatically when the @Transactional method
        // commits/rolls back.
        locks.lockForSymbol("AAPL");
        locks.lockForSymbol("MSFT");
        locks.lockForSymbol("AAPL"); // re-acquire is a no-op (already-held)
        assertThat(true).isTrue(); // sentinel — the test asserts by NOT throwing
    }
}
