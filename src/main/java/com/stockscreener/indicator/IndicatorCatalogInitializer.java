package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기동 시 지표 카탈로그를 동기화한다.
 *
 * <p>IMPORTANT — 이 러너가 없으면 {@code indicator} 테이블이 비어 있고,
 * {@code indicator_value.indicator_code}의 FK 때문에 Phase 3의 지표 계산이 아무것도 저장하지
 * 못한다. Phase 1에서 {@link IndicatorCatalogSynchronizer}를 만들어두고 호출 지점을 두지
 * 않았는데, 배치 인프라가 생긴 지금이 그것을 붙일 자연스러운 시점이다.
 *
 * <p>기동 시점에 도는 이유는 카탈로그의 근거가 <b>코드에 있는 계산기 집합</b>이기 때문이다.
 * 배포로만 바뀌므로 배포 직후 한 번 맞추면 충분하고, 배치 스케줄에 걸 이유가 없다.
 *
 * <p>실패하면 기동을 막는다. 카탈로그가 어긋난 채로 뜨면 지표 계산이 조용히 실패하는데,
 * 그것은 새벽 배치에서야 드러난다.
 *
 * <p>IMPORTANT — {@code @Order}로 프리셋 시더보다 먼저 돈다. {@code ApplicationRunner}의
 * 실행 순서는 지정하지 않으면 <b>미정의</b>이고, {@code screen_condition.indicator_code}가
 * {@code indicator}를 FK로 참조하므로 순서가 뒤집히면 깨끗한 DB에서 기동이 FK 위반으로 실패한다.
 */
@Component
@Order(0)
public class IndicatorCatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndicatorCatalogInitializer.class);

    private final IndicatorCatalogSynchronizer synchronizer;

    public IndicatorCatalogInitializer(IndicatorCatalogSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @Override
    public void run(ApplicationArguments args) {
        int activeCount = synchronizer.synchronize();
        log.info("지표 카탈로그를 동기화했다. 활성 지표 {}개", activeCount);
    }
}
