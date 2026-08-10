package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모르는 인자를 실행 전에 막는지 고정한다.
 *
 * <p>지우면 <b>아무것도 깨지지 않고</b> 오타 하나가 쿼터를 태운다. {@code --ticker=…}처럼 s를
 * 빠뜨리면 러너가 그 인자를 무시하고 대상이 "전 종목"으로 넓어지는데, {@code --reload-existing=true}가
 * 함께 있으면 {@code warn-bootstrap-args.sh}도 조용히 통과시킨다. 태운 외부 호출은 되돌릴 수 없다.
 */
class BootstrapArgumentGuardTest {

    @Test
    @DisplayName("--tickers의 오타를 실행 전에 막는다 — 무시하면 대상이 전 종목이 된다")
    void rejectsMisspelledTickersOption() {
        assertThatThrownBy(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments(
                        "--job=initial-load", "--ticker=082640", "--reload-existing=true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticker");
    }

    @Test
    @DisplayName("아는 인자만 있으면 통과한다")
    void acceptsKnownOptions() {
        assertThatCode(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments(
                        "--job=initial-load", "--tickers=082640,121600",
                        "--price-years=7", "--financial-years=3",
                        "--reload-existing=true", "--as-of=2026-08-11", "--from=2020-01-02")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Spring 자신의 인자는 통과한다 — 점을 포함한 이름과 점 없는 플래그 둘 다")
    void acceptsSpringOwnOptions() {
        assertThatCode(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments(
                        "--spring.profiles.active=bootstrap", "--server.port=0",
                        "--debug", "--job=universe")))
                .doesNotThrowAnyException();
    }
}
