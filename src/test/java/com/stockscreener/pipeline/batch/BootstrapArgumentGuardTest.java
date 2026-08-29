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
                        "--reload-existing=true", "--as-of=2026-08-11", "--from=2020-01-02",
                        "--ca-id=97", "--ca-status=APPROVED", "--ca-type=SPLIT", "--ca-ticker=005930",
                        "--ca-limit=200", "--ca-ratio=5", "--ca-effective-date=2026-02-04",
                        "--ca-listing-date=2026-02-20", "--ca-note=메모")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("--ca-*는 원장 잡에서만 통과한다 — 수집 잡이 접두어라 -judge를 빠뜨리면 쿼터를 태운다")
    void rejectsLedgerOptionsOnCollectionJob() {
        assertThatThrownBy(() -> BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(
                new DefaultApplicationArguments("--job=corporate-action", "--ca-id=97",
                        "--ca-status=APPROVED"),
                "corporate-action"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ca-id");

        assertThatCode(() -> BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(
                new DefaultApplicationArguments("--job=corporate-action-judge", "--ca-id=97",
                        "--ca-status=APPROVED", "--ca-type=SPLIT", "--ca-ratio=5",
                        "--ca-effective-date=2026-02-04", "--ca-listing-date=2026-02-20",
                        "--ca-ticker=005930", "--ca-limit=200", "--ca-note=주식수 배수로 확인했다"),
                "corporate-action-judge"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("따옴표가 뒤의 플래그를 품었으면 막는다 — 그 플래그는 적용되지 않는데 잡은 성공으로 끝난다")
    void rejectsNoteThatSwallowedFollowingFlags() {
        assertThatThrownBy(() -> BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(
                new DefaultApplicationArguments("--job=corporate-action-judge", "--ca-id=97",
                        "--ca-note=메모 --ca-status=APPROVED --ca-type=SPLIT --ca-ratio=2"),
                "corporate-action-judge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("따옴표");
    }

    @Test
    @DisplayName("따옴표 없이 쪼개진 메모를 막는다 — 옵션 이름 검사는 사라진 낱말을 못 본다")
    void rejectsNoteSplitByUnquotedSpaces() {
        // gradle이 공백에서 쪼갠 결과가 이 모양이다(실측: `--ca-note=메모 두 낱말`이 그냥
        // 성공했다). 남은 낱말은 non-option 인자가 되므로 getOptionNames()에 안 잡힌다.
        assertThatThrownBy(() -> BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(
                new DefaultApplicationArguments("--job=corporate-action-judge", "--ca-id=97",
                        "--ca-note=메모", "두", "낱말"),
                "corporate-action-judge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("붙지 않은 낱말");
    }

    @Test
    @DisplayName("문서화된 줄바꿈의 백슬래시는 통과한다 — 막으면 정상 명령이 통째로 멈춘다")
    void allowsDocumentedLineContinuation() {
        // `--args='… \`+개행은 작은따옴표 안이라 리터럴 백슬래시가 그대로 넘어온다(od -c 실측).
        assertThatCode(() -> BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(
                new DefaultApplicationArguments("--job=corporate-action-review", "\\",
                        "--ca-status=OBSERVED"),
                "corporate-action-review"))
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

    @Test
    @DisplayName("원장·재구축 잡은 --universe를 받는다 — 비활성 유니버스를 손으로 고르는 경로다")
    void acceptsUniverseOnTheLedgerAndRebuildJobs() {
        // ADR-0090. 이 둘이 UNIVERSE_JOBS에 없으면 미국 적재 명령이 첫 줄에서 거절된다.
        for (String job : new String[] {"corporate-action", "adjustment-rebuild"}) {
            assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                    new DefaultApplicationArguments("--job=" + job, "--universe=US_LISTED"), job))
                    .doesNotThrowAnyException();
        }
        // 그리고 넓힌 뒤에도 나머지 잡에서는 여전히 막혀야 한다. 그것이 이 가드의 값이다.
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=governance-load", "--universe=US_LISTED"),
                "governance-load"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("universe");
    }

    @Test
    @DisplayName("--universe·--skip-*는 뜻이 있는 잡에서만 통과한다 — 아니면 조용히 무시된다")
    void rejectsMisplacedLoadOptions() {
        // 잡 이름을 잘못 적으면 --universe가 무시된 채 전 종목이 대상이 되고 성공으로 끝난다.
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=backfill", "--universe=US_LISTED"),
                "backfill"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("universe");

        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=universe", "--skip-financial=true"),
                "universe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skip-financial");

        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=financial-refill", "--skip-price=true"),
                "financial-refill"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skip-price");

        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=initial-load", "--universe=US_LISTED",
                        "--skip-financial=true"),
                "initial-load"))
                .doesNotThrowAnyException();

        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=initial-load", "--universe=US_LISTED",
                        "--skip-price=true"),
                "initial-load"))
                .doesNotThrowAnyException();

        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=universe", "--universe=US_LISTED"),
                "universe"))
                .doesNotThrowAnyException();
    }
}
