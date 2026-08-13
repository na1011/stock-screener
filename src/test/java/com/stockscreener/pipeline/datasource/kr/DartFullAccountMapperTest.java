package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업활동현금흐름을 계정명으로 찾을 때의 판별을 고정한다.
 *
 * <p>여기서 재는 것은 <b>총계와 소계를 가르는가</b>다. {@code 영업활동에서 창출된 현금}은
 * 이자·법인세 차감 전이라 총계보다 20% 안팎 크고, 둘을 나란히 내는 회사가 실제로 있다
 * (삼성전기 2022). 잘못 고르면 값이 그럴듯하게 틀리는데 H-05는 5년 합이라 오차가 누적된다.
 *
 * <p>아래 이름은 전부 <b>실측</b>이다 — 350종목 적재에서 표준계정 ID로 못 찾은 행들의 실제
 * {@code account_nm}이다.
 */
class DartFullAccountMapperTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "영업활동순현금흐름 합계",          // 삼성전기 2022 (표준코드 미사용)
            "영업활동으로 인한 순현금흐름",      // 현대글로비스 2021 (표준코드 미사용)
            "I. 영업활동으로인한현금흐름",       // 코오롱인더 2023 (ID가 …InOperations)
            "영업활동으로부터의 순현금유입",     // 이마트 2021·2022 — '현금흐름'이라 말하지 않는다
            "영업활동현금흐름",
            "영업활동으로부터의 현금흐름",
            "Ⅰ.영업활동 현금흐름",
    })
    @DisplayName("총계로 읽어야 하는 이름을 알아본다")
    void recognizesOperatingTotals(String accountName) {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(accountName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "영업활동에서 창출된 현금",          // 삼성전기 2022가 총계와 나란히 갖고 있다
            "영업에서 창출된 현금흐름",
            "투자활동순현금흐름 합계",
            "재무활동순현금흐름 합계",
            "이자의 수취",
            "기말 현금및현금성자산",
            "기초 현금및현금성자산",
    })
    @DisplayName("소계와 다른 구간을 총계로 읽지 않는다")
    void rejectsSubtotalsAndOtherSections(String accountName) {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(accountName)).isFalse();
    }

    @Test
    @DisplayName("null 계정명에 값을 만들지 않는다")
    void rejectsNull() {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(null)).isFalse();
    }
}
