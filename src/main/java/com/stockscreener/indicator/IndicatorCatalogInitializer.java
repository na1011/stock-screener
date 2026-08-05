package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
 */
@Component
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
