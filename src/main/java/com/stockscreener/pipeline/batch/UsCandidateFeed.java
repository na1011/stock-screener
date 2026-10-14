package com.stockscreener.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 미국 순위 후보를 정기 경로로 먹이는가(ADR-0178) — 평일 14:16 후보 공시 소비 · 14:00 후보 분할 다리 · 토요일 후보 풀 화해와 새 행 인입.
 *
 * <p>이 셋은 후보 종목의 재무 행과 기업행위 원장을 쓰고, 그것이 순위 재료(주식수 · {@code JdbcStoredShareCounts})라 명부를 바꾼다.
 * 그래서 작은 DB 드라이런(D4)을 지나기 전에는 꺼 둔 채 main에 쌓는다 — 꺼져 있으면 정기 경로의 동작은 이 설정이 생기기 전과 같다.
 * 켜는 길은 기본값을 뒤집은 jar다. 켜는 커밋에서 {@code JobFreshnessPolicy}에 {@code candidate-consumption} 예산을 함께 더한다
 * (꺼진 동안 넣으면 신선도 점검이 「한 번도 안 돈 잡」으로 운다). 손 실행({@code --job=candidate-consumption})은 이 설정을 안 본다 —
 * 명시적 실행이다.
 *
 * <p>재적재 관문 설정({@code stockscreener.gate.us})과 다르다 — 그것은 값을 계산하는 규칙을 바꾸는 갈래를 한 날 함께 켜는 스위치이고,
 * 이것은 지금 규칙을 어느 종목에 돌리는가다. 켠 뒤 전 종목 재적재가 필요 없다.
 */
@Component
public class UsCandidateFeed {

    private static final Logger log = LoggerFactory.getLogger(UsCandidateFeed.class);

    private final boolean enabled;

    public UsCandidateFeed(@Value("${stockscreener.us-candidates.enabled}") boolean enabled) {
        this.enabled = enabled;
        log.info("미국 순위 후보 정기 공급 {}", enabled ? "켬" : "꺼짐(ADR-0178 — 드라이런 전)");
    }

    public boolean enabled() {
        return enabled;
    }
}
