package com.stockscreener.pipeline.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스냅이 <b>삼켜야 할 것을 삼키고 삼키면 안 되는 것을 뱉는지</b> 잰다. 통과 사례만 두면
 * 판정력이 없다 — 분모 상한을 키우기만 해도 전부 초록이 되기 때문이다.
 *
 * <p>값은 전부 {@code price_daily} 실측이다(2026-08-12).
 */
class ShareRatioSnapTest {

    @ParameterizedTest(name = "{2}: {0} → {1}")
    @CsvSource({
            // 관측 배수, 기대 스냅값, 사건
            "0.0999999992770, 0.1000000000, 대한전선 2023 10:1 병합",
            "0.3999999977400, 0.4000000000, 휴림로봇 2024 5:2 병합",
            "0.3333333330000, 0.3333333333, 아시아나항공 2021 3:1 감자",
            "5.0000000000000, 5.0000000000, 카카오 2021 1:5 분할",
            "2.0000000000000, 2.0000000000, 1:2 분할",
    })
    @DisplayName("단수주로 흐려진 분할·병합 배수를 원래 유리수로 되돌린다")
    void snapsSharePriceMultiples(String observed, String expected, String event) {
        assertThat(ShareRatioSnap.snap(new BigDecimal(observed)))
                .as(event)
                .contains(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource({
            "0.0940731400, 대한전선 가격 배수의 역수 — 종가는 그날 시장이 움직인 만큼 흐려진다",
            "1.0001706930, 카카오 2021-04-09 — 분할 6일 전의 전환권행사류 변동",
            "0.6880000000, OCI 인적분할 — 86/125라 분모 상한 밖이다",
            "1.9907735833, 씨젠 2021 무상증자 — 편차가 단수주가 아니라 자사주다",
    })
    @DisplayName("단순 유리수가 아닌 배수는 근사값으로 메우지 않고 비운다")
    void refusesValuesThatAreNotSimpleRationals(String observed, String why) {
        assertThat(ShareRatioSnap.snap(new BigDecimal(observed)))
                .as(why)
                .isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 스냅만으로는 인적분할을 못 거른다. 삼바 0.650은 13/20이라 통과한다")
    void snappingAloneDoesNotIdentifyAParValueChange() {
        // 이것을 거르는 것은 이 함수가 아니라 **가격 배수의 역수인가**다. 삼바 2025-11-24는
        // 주식수 0.650인데 가격 1.465이고 그 역수는 0.6826이다 — 5% 어긋나 분할·병합이 아니다.
        // 스냅을 통과했다는 것을 "분할·병합이다"로 읽으면 인적분할에 틀린 계수가 들어간다.
        assertThat(ShareRatioSnap.snap(new BigDecimal("0.6500000000")))
                .contains(new BigDecimal("0.6500000000"));
    }

    @Test
    @DisplayName("1/3은 스케일 10에서 접히고 그 한계가 javadoc에 적혀 있다")
    void oneThirdKeepsAResidualError() {
        Optional<BigDecimal> snapped = ShareRatioSnap.snap(new BigDecimal("0.333333330"));

        assertThat(snapped).contains(new BigDecimal("0.3333333333"));
        // 스냅 전 역수는 3.0000000300, 스냅 뒤는 3.0000000003이다 — 오차가 100배 준다.
        assertThat(BigDecimal.ONE.divide(snapped.orElseThrow(), 10, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("3.0000000003");
    }

    @Test
    @DisplayName("양수가 아닌 배수는 데이터 손상이므로 조용히 비우지 않는다")
    void rejectsNonPositiveInput() {
        assertThatThrownBy(() -> ShareRatioSnap.snap(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
        assertThatThrownBy(() -> ShareRatioSnap.snap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
