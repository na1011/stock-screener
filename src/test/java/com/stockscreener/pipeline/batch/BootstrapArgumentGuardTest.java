package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("값 없는 --skip-price를 막는다 — 그것은 건너뛰기가 아니라 false다")
    void rejectsValuelessSkipPrice() {
        assertThatThrownBy(() -> BootstrapRunner.rejectMalformedBooleans(
                new DefaultApplicationArguments(
                        "--job=initial-load", "--universe=US_LISTED", "--skip-price",
                        "--reload-existing=true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skip-price");
    }

    @Test
    @DisplayName("값 없는 --skip-price가 실제로 false로 읽힌다 — 가드가 지키는 사실")
    void valuelessBooleanReadsAsFalse() {
        ApplicationArguments args = new DefaultApplicationArguments("--skip-price");
        // Spring은 값 없는 --foo를 **빈 목록**으로 담는다. null이 아니다.
        assertThat(args.containsOption("skip-price")).isTrue();
        assertThat(args.getOptionValues("skip-price")).isEmpty();
    }

    @Test
    @DisplayName("값 없는 --insert-earlier-vintages를 막는다 — 거짓으로 읽혀 삽입 없이 교정·무효화만 돈다")
    void rejectsValuelessInsertEarlierVintages() {
        assertThatThrownBy(() -> BootstrapRunner.rejectMalformedBooleans(
                new DefaultApplicationArguments(
                        "--job=financial-refill", "--insert-earlier-vintages", "--financial-years=11")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insert-earlier-vintages");
    }

    @Test
    @DisplayName("true·false가 아닌 불리언 값을 막는다 — parseBoolean은 1·yes를 조용히 거짓으로 읽는다")
    void rejectsBooleanValuesOtherThanTrueOrFalse() {
        assertThatThrownBy(() -> BootstrapRunner.rejectMalformedBooleans(
                new DefaultApplicationArguments(
                        "--job=financial-refill", "--insert-earlier-vintages=1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insert-earlier-vintages");

        assertThatThrownBy(() -> BootstrapRunner.rejectMalformedBooleans(
                new DefaultApplicationArguments(
                        "--job=universe-restore", "--universe=US_TOP1000", "--apply=yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apply");
    }

    @Test
    @DisplayName("--population은 financial-refill에서만 · 값은 candidates 하나 · --universe와 함께 못 쓴다 — 조용히 무시되면 후보가 적재됐다고 믿는다")
    void rejectsMisplacedPopulation() {
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=company-profile", "--population=candidates"), "company-profile"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("population");
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=financial-refill", "--population=members"), "financial-refill"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidates");
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=financial-refill", "--population=candidates", "--universe=US_LISTED"), "financial-refill"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("universe");
        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=financial-refill", "--population=candidates", "--financial-years=11",
                "--insert-earlier-vintages=true"), "financial-refill"))
                .doesNotThrowAnyException();
        assertThatCode(() -> BootstrapRunner.rejectUnknownOptions(new DefaultApplicationArguments(
                "--job=financial-refill", "--population=candidates"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("러너가 읽는 인자는 전부 아는 인자다 — 목록에 안 넣으면 안내된 명령이 기동에서 거절된다(sweep-limit · max-fetches 2026-10-31)")
    void everyOptionTheRunnerReadsIsKnown() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/stockscreener/pipeline/batch/BootstrapRunner.java"));
        java.util.Set<String> read = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\(args, \"([a-z-]+)\"|(?:getOptionValues|containsOption)\\(\"([a-z-]+)\"\\)|getOptionNames\\(\\)\\.contains\\(\"([a-z-]+)\"\\)")
                .matcher(source);
        while (m.find()) {
            read.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        }
        assertThat(read).as("소스에서 인자 이름을 하나도 못 읽었다면 이 시험의 정규식이 낡은 것이다").contains("tickers", "max-fetches");
        assertThat(BootstrapRunner.KNOWN_OPTIONS).containsAll(read);
    }

    @Test
    @DisplayName("--sweep-limit은 earnings-release에서 받고 다른 잡에서는 거절한다 — 첫 백필 명령이 모르는 인자로 기동에서 죽었다(2026-10-30)")
    void sweepLimitBelongsToEarningsRelease() {
        assertThatCode(() -> BootstrapRunner.rejectUnknownOptions(new DefaultApplicationArguments(
                "--job=earnings-release", "--sweep-limit=2000"))).doesNotThrowAnyException();
        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=earnings-release", "--sweep-limit=2000"), "earnings-release")).doesNotThrowAnyException();
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(new DefaultApplicationArguments(
                "--job=filing-listing", "--sweep-limit=2000"), "filing-listing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sweep-limit");
    }

    @Test
    @DisplayName("값이 붙은 불리언은 통과한다")
    void acceptsBooleansWithValues() {
        assertThatCode(() -> BootstrapRunner.rejectMalformedBooleans(
                new DefaultApplicationArguments(
                        "--job=initial-load", "--skip-price=true", "--skip-financial=false",
                        "--reload-existing=true", "--all-quarters=true",
                        "--insert-earlier-vintages=TRUE")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("불리언은 목록에 등록된 이름만 읽힌다 — 빠진 인자는 실행 전 검사를 조용히 비켜 간다")
    void flagRefusesAnOptionMissingFromTheBooleanList() {
        ApplicationArguments args = new DefaultApplicationArguments("--job=financial-refill");

        assertThat(BootstrapRunner.BOOLEAN_OPTIONS).contains("insert-earlier-vintages");
        assertThatThrownBy(() -> BootstrapRunner.flag(args, "not-registered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-registered");
        assertThat(BootstrapRunner.flag(args, "insert-earlier-vintages")).isFalse();
    }

    @Test
    @DisplayName("값이 빈 인자를 막는다 — 빈 --tickers는 「티커 없음」이 되어 대상이 활성 유니버스 전량으로 넓어진다")
    void rejectsEmptyOptionValues() {
        // 셸 변수가 비면 이 모양이 된다. csv()가 List.of()를 내고 잡이 기본 대상으로 간다.
        ApplicationArguments args = new DefaultApplicationArguments(
                "--job=financial-refill", "--tickers=", "--financial-years=11", "--insert-earlier-vintages=true");
        assertThat(args.getOptionValues("tickers")).containsExactly("");

        assertThatThrownBy(() -> BootstrapRunner.rejectUnknownOptions(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tickers");

        assertThatThrownBy(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments("--job=backfill", "--universe=", "--as-of=2026-09-16")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("universe");
    }

    @Test
    @DisplayName("적재 잡에서도 붙지 않은 낱말을 막는다 — 공백으로 펼친 티커 목록은 첫 티커만 남고 성공으로 끝난다")
    void rejectsStrayWordsOnLoadJobs() {
        assertThatThrownBy(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments(
                        "--job=financial-refill", "--tickers=AAPL", "MSFT", "NVDA",
                        "--financial-years=11", "--insert-earlier-vintages=true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MSFT");

        // 문서화된 줄바꿈의 백슬래시는 여전히 통과한다.
        assertThatCode(() -> BootstrapRunner.rejectUnknownOptions(
                new DefaultApplicationArguments("--job=backfill", "\\", "--tickers=AAPL,MSFT")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 인자를 두 번 주면 막는다 — 러너가 첫 값만 읽어 두 번째 티커 목록이 조용히 빠진다")
    void rejectsRepeatedOptions() {
        ApplicationArguments args = new DefaultApplicationArguments(
                "--job=backfill", "--tickers=AAPL,MSFT", "--tickers=NVDA", "--as-of=2026-09-16");
        // Spring은 둘 다 담는다 — 버리는 것은 러너의 value()다.
        assertThat(args.getOptionValues("tickers")).containsExactly("AAPL,MSFT", "NVDA");

        assertThatThrownBy(() -> BootstrapRunner.rejectUnknownOptions(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tickers");
    }

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
    @DisplayName("원장·재구축·업종 잡은 --universe를 받는다 — 비활성 유니버스를 손으로 고르는 경로다")
    void acceptsUniverseOnTheLedgerAndRebuildJobs() {
        // ADR-0090·ADR-0092. 이들이 UNIVERSE_JOBS에 없으면 미국 적재 명령이 첫 줄에서 거절된다.
        //
        // MUST: 새 잡을 열 때 여기에 이름을 더한다. 이 테스트는 집합을 열거하지 않고 잡을
        // 하나씩 단언하므로, 안 더하면 **테스트는 전부 초록인 채** 명령만 런타임에 죽는다.
        for (String job : new String[] {"corporate-action", "adjustment-rebuild", "company-profile"}) {
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
        //
        // 여기 있던 backfill은 2026-08-31에 뺐다. 그때까지 backfill은 활성 유니버스만 봤고
        // 그래서 --universe가 뜻이 없었는데, 「켜야 지표가 생기고 지표가 없으면 켜면 안 되는」
        // 순환을 끊으려고 그 잡에 갈래를 냈다(IndicatorBackfillJob 클래스 javadoc).
        // 그러므로 이것은 버그 수정이 아니라 **계약 변경**이다 — 이 시험이 잠그고 있던 것을
        // 사람이 골라서 풀었다. 대신 바로 위 시험이 「UNIVERSE_JOBS에 든 잡은 통과하고
        // 나머지는 여전히 막힌다」를 지키므로 가드 자체는 그대로 값이 있다.
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

    @Test
    @DisplayName("읽기 전용 잡 = 보호 표 쓰기 0 — 판정 잡은 원장을 쓰므로 빠진다(ADR-0175)")
    void readOnlyJobsSkipTheOrphanSweeper() {
        // 이 집합은 쓰기 잠금을 안 쥐고 스윕도 안 돌린다. 쓰는 잡이 여기 들면 가드가 그 쓰기를 거절한다.
        assertThat(BootstrapRunner.isReadOnlyJob("bar-gap-review")).isTrue();
        assertThat(BootstrapRunner.isReadOnlyJob(CorporateActionJudgeJob.REVIEW_JOB_NAME)).isTrue();
        // 판정은 corporate_action과 실행 행을 쓴다 — 적재가 쥔 동안에는 거절돼야 한다.
        assertThat(BootstrapRunner.isReadOnlyJob(CorporateActionJudgeJob.JOB_NAME)).isFalse();

        // 반대쪽이 이 시험의 값이다 — 적재 잡까지 빠지면 고아가 영영 안 닫힌다.
        for (String loadJob : new String[] {"initial-load", "corporate-action", "market-calendar",
                "adjustment-rebuild", "backfill", "universe"}) {
            assertThat(BootstrapRunner.isReadOnlyJob(loadJob))
                    .as("%s는 적재라 스윕을 돌려야 한다", loadJob)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("--ca-*는 읽기 전용이어도 원장 잡 밖에서 거절된다 — 두 집합은 다른 질문에 답한다")
    void ledgerOptionsStayClosedOnTheGapReview() {
        ApplicationArguments args = new DefaultApplicationArguments(
                "--job=bar-gap-review", "--ca-id=97");

        assertThatThrownBy(() ->
                BootstrapRunner.rejectLedgerOptionsOutsideLedgerJobs(args, "bar-gap-review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ca-id");
    }

    @Test
    @DisplayName("--universe는 캘린더 잡에서 거절된다 — 대상이 종목이 아니라 거래소다")
    void rejectsUniverseOnTheCalendarJob() {
        ApplicationArguments args = new DefaultApplicationArguments(
                "--job=market-calendar", "--universe=US_LISTED");

        assertThatThrownBy(() ->
                BootstrapRunner.rejectMisplacedLoadOptions(args, "market-calendar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("universe");
    }

    @Test
    @DisplayName("--insert-earlier-vintages는 financial-refill에서만 통과한다 — 다른 잡에서는 조용히 무시된다")
    void acceptsInsertEarlierVintagesOnlyOnFinancialRefill() {
        // 그 인자를 준 사람은 이른 판이 들어갔다고 믿는다. backfill에 붙여 두면 아무 일도 안 하고
        // 성공으로 끝나 그 믿음이 확인 없이 남는다.
        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=financial-refill", "--insert-earlier-vintages=true"),
                "financial-refill"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=backfill", "--insert-earlier-vintages=true"),
                "backfill"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insert-earlier-vintages");
    }

    @Test
    @DisplayName("--from은 indicator-reconcile에서 거절된다 — 파생층은 언제나 창 전체라 구간이 조용히 무시된다")
    void rejectsFromOnReconcile() {
        // 구간을 좁힌 줄 아는 사람은 그 구간만 다시 썼다고 믿는다. 실제로는 창 전체를 돈다.
        assertThatThrownBy(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=indicator-reconcile", "--from=2026-01-02"),
                BootstrapRunner.RECONCILE_JOB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--from");
        // 티커·기준일은 뜻이 있다 — 막으면 사본 드라이런과 관문의 명령이 안 선다.
        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=indicator-reconcile", "--tickers=005930",
                        "--as-of=2026-10-02"),
                BootstrapRunner.RECONCILE_JOB))
                .doesNotThrowAnyException();
        // 손 도구 backfill은 여전히 --from을 받는다.
        assertThatCode(() -> BootstrapRunner.rejectMisplacedLoadOptions(
                new DefaultApplicationArguments("--job=backfill", "--from=2026-01-02"), "backfill"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("indicator-reconcile의 기본 기준일은 오늘이 아니라 정기 화해의 규약(dataAsOf)이다")
    void reconcileDefaultsToTheScheduledRule() {
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        LocalDate scheduledRule = LocalDate.of(2026, 10, 1);
        // 인자가 없으면 정기 화해와 같은 값 — 오늘(일요일)을 쓰면 봉이 몇 종목에만 있는 날을 연다.
        assertThat(BootstrapRunner.reconcileAsOf(
                new DefaultApplicationArguments("--job=indicator-reconcile"), sunday, scheduledRule))
                .isEqualTo(scheduledRule);
        // 주면 준 값이다 — 사본 드라이런이 그 날짜를 고른다.
        assertThat(BootstrapRunner.reconcileAsOf(
                new DefaultApplicationArguments("--job=indicator-reconcile", "--as-of=2026-09-30"),
                LocalDate.of(2026, 9, 30), scheduledRule))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
