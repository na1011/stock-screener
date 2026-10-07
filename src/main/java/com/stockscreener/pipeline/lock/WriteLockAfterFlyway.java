package com.stockscreener.pipeline.lock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Flyway 직후의 자리 — {@link WriteLockOrdering}이 이것을 {@code flywayInitializer} 뒤, {@code entityManagerFactory} 앞에 놓는다.
 *
 * <p>기동 때 {@code write_lock}이 없었으면(V66 전 DB) 게이트가 여기서 다시 쥔다. 표가 있었으면 할 일이 없다.
 */
@Component(WriteLockAfterFlyway.BEAN)
class WriteLockAfterFlyway implements InitializingBean {

    static final String BEAN = "writeLockAfterFlyway";

    private final WriteLockGate gate;

    WriteLockAfterFlyway(WriteLockGate gate) {
        this.gate = gate;
    }

    @Override
    public void afterPropertiesSet() {
        gate.afterFlyway();
    }
}
