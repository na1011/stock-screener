package com.stockscreener.screening.preset;

import com.stockscreener.pipeline.lock.CatalogWriteGate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 기동 시 프리셋을 동기화한다.
 *
 * <p>IMPORTANT — {@code @Order(10)}으로 {@code IndicatorCatalogInitializer}(0) 다음에 돈다.
 * {@code ApplicationRunner}의 순서는 지정하지 않으면 미정의이고, 프리셋의 조건이
 * {@code screen_condition.indicator_code → indicator(code)} FK를 갖는다. 순서가 뒤집히면
 * 깨끗한 DB에서 <b>기동 자체가 FK 위반으로 실패</b>한다 — 지표 카탈로그가 아직 비어 있기 때문이다.
 *
 * <p>실패하면 기동을 막는 것이 맞다. 프리셋 없이 뜨면 화면에는 "전략 없음"만 보이고,
 * 그것을 데이터 문제로 오해하기 쉽다.
 */
@Component
@Order(10)
public class PresetCatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PresetCatalogInitializer.class);

    private final PresetSynchronizer synchronizer;
    private final Environment environment;

    public PresetCatalogInitializer(PresetSynchronizer synchronizer, Environment environment) {
        this.synchronizer = synchronizer;
        this.environment = environment;
    }

    /** MUST: 카탈로그 차이 거절은 기동을 세운다({@link CatalogWriteGate#refuseBoot}) — 던지면 bootRun이 rc 0으로 끝난다. */
    @Override
    public void run(ApplicationArguments args) {
        int count;
        try {
            count = synchronizer.synchronize();
        } catch (CatalogWriteGate.CatalogDriftException e) {
            CatalogWriteGate.refuseBoot(environment, "프리셋", e);
            return;
        }
        log.info("프리셋을 동기화했다. 활성 프리셋 {}개", count);
    }
}
