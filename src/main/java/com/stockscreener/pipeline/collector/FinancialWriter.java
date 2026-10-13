package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingTrace;
import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 수집한 재무제표를 {@code financial_statement}에 쓴다.
 *
 * <p>IMPORTANT — 정정공시는 <b>덮어쓰지 않고 새 행으로 쌓는다.</b> 하나의 가변 행으로 두면
 * "그 시점에 공개되어 있던 값"이 영구히 파괴되어 어떤 마이그레이션으로도 복구할 수 없다.
 * v1은 그 질의를 구현하지 않고 항상 최신본을 쓰지만, 질의는 미루되 데이터는 미루지 않는다.
 *
 * <p>따라서 이 클래스의 핵심은 upsert가 아니라 <b>개정 판별</b>이다. 같은 회계기간의 보고서가
 * 다시 들어왔을 때 그것이 재수집인지 정정공시인지 구분해야 한다. 기준은
 * {@code published_at}이다 — 공시일이 더 늦으면 정정본이다.
 *
 * <h2>{@link WriteMode#FILL_MISSING} — 결손 컬럼만 채우는 좁은 경로</h2>
 *
 * <p>스키마에 컬럼이 늘어나면 <b>이미 저장된 행은 그 자리가 NULL인 채로 남는다.</b> 재수집을
 * 돌려도 {@link WriteMode#APPEND_ONLY}가 "같은 공시일 = 재수집"으로 판정해 아무것도 하지 않기
 * 때문이다. 실제로 V16이 더한 세 컬럼이 10,882행 전부 NULL로 남아 Altman Z가 한 종목도
 * 계산되지 않았다(ADR-0040).
 *
 * <p>그 상태를 푸는 유일한 경로가 이 모드다. 위 {@code MUST NOT}이 막으려는 것은 <b>값이
 * 달라진 것을 조용히 덮는 일</b>이고, <b>대립할 이전 값 자체가 없는 NULL 채우기는 거기 걸리지
 * 않는다.</b> 그래서 이 모드도 다음 셋을 지킨다.
 *
 * <ul>
 *   <li><b>공시일이 정확히 같을 때만 채운다.</b> 더 이른 판으로 채우면 행 하나가 두 공시의
 *       혼합이 되어, 어느 시점의 값인지 물을 수 없게 된다.
 *   <li><b>값이 다르면 덮되, 옛값과 새값을 로그에 남기고 무효화를 낸다.</b> 공시일이 같으므로
 *       달라진 것은 소스가 아니라 <b>우리 추출</b>이다. 얼려 두면 틀린 값이 남을 뿐이고 어느
 *       쪽 오류인지도 알 수 없다 — 위험한 것은 갱신이 아니라 <b>조용한</b> 갱신이다.
 *   <li><b>비교는 {@link BigDecimal#compareTo}다.</b> 컬럼이 {@code numeric(24,2)}라 저장값은
 *       scale 2로 돌아오는데 소스 파싱값은 scale 0이다. {@code equals}로 비교하면 같은 값이
 *       전부 "달라졌다"로 잡혀 경고가 수만 줄 나온다.
 * </ul>
 *
 * <p><b>그 셋 중 첫째를 지키는 것은 {@link #fillMissing}이 아니라 {@link #writeOne}이다.</b>
 * 채우는 메서드 자체에는 공시일 검사가 없다 — 호출자가 이미 걸렀다는 전제 위에 있다.
 * 새 호출 경로를 만들 때 그 전제를 함께 옮기지 않으면 불변식이 조용히 사라진다.
 *
 * <h2>{@link WriteMode#FILL_WITHOUT_REVISION} — 개정본을 쌓지 않는 더 좁은 경로</h2>
 *
 * <p>결손을 메우러 온 소스가 <b>정정공시를 물고 올 수 있다.</b> 그때 개정본을 쌓으면 지표
 * 무효화가 함께 터지는데, 이 경로의 목적은 결손 보충이지 정정 반영이 아니다. 게다가 새
 * 어댑터가 파싱하지 못한 컬럼은 개정본에서 NULL로 다시 쌓이고 기존 값은 {@code is_latest=false}로
 * 내려가 <b>읽기에서 통째로 사라진다</b> — 메우러 와서 지우고 가는 셈이다. 그래서 이 모드는
 * 정정을 만나면 채우지 않고 센다.
 */
@Component
public class FinancialWriter {

    private static final Logger log = LoggerFactory.getLogger(FinancialWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final IndicatorInvalidationPort invalidationPort;

    public FinancialWriter(JdbcTemplate jdbcTemplate, IndicatorInvalidationPort invalidationPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.invalidationPort = invalidationPort;
    }

    /** 같은 회계기간이 다시 들어왔을 때 무엇까지 할지. */
    public enum WriteMode {
        /** 새 보고서와 정정공시만 쓴다. 일일·초기 적재의 기본이다. */
        APPEND_ONLY,
        /** 위에 더해, 같은 공시일의 재수집이면 <b>NULL인 컬럼만</b> 채운다. */
        FILL_MISSING,
        /**
         * {@link #FILL_MISSING}과 같되 <b>정정공시를 반영하지 않는다.</b> 소스가 더 늦은
         * 공시본을 줘도 개정본을 쌓지 않고 기록만 남긴다.
         *
         * <p>IMPORTANT — 이 모드가 존재하는 이유는 편의가 아니라 <b>도달 불가</b>다.
         * 정정 경로는 {@link IndicatorInvalidationPort#invalidate}를 부르고 그것은
         * {@code trade_date >= periodStart}인 <b>그 종목의 전 지표</b>를 지운다. 사업보고서면
         * 기준일이 회계연도 1월 1일이라 보존 창 <b>전량</b>이다. 발화 조건은
         * "소스의 공시일이 저장값보다 하루라도 늦다" 하나뿐이라, 표본 몇 종목의 공시일이
         * 같더라는 확인으로는 안전을 말할 수 없다 — 표본 밖에서 어긋나면 그 종목만 조용히
         * 그 경로를 탄다. 그래서 <b>가지 자체를 없앤다.</b>
         *
         * <p>MUST NOT: 이 모드로 정기 갱신을 돌리지 않는다. 정정공시가 영영 반영되지 않는다.
         * 그 책임은 {@link #FILL_MISSING}을 쓰는 재무 결손 보정 잡에 그대로 남아 있다.
         */
        FILL_WITHOUT_REVISION
    }

    /**
     * 한 종목의 쓰기 결과.
     *
     * <p>{@code revised}와 {@code deferredRevisions}가 <b>정반대의 사실</b>을 센다 —
     * 전자는 정정공시를 반영해 개정본을 쌓은 수(그때 <b>그 종목의 지표가 지워졌다</b>),
     * 후자는 정정공시라서 채우지 못하고 남긴 수다. 둘 다 {@code changed} 안에 뭉뚱그리면
     * 잡이 "무효화가 일어났는가"를 물을 수 없고, 그 질문에 답하지 못하면 <b>백필이 남았다는
     * 것을 아무도 모른다.</b>
     */
    /**
     * @param retractionStopped 판 이력의 빈칸을 몰라 이 종목의 회수를 멈췄다({@link VintageGuard#uncertain()}) — 실행 기록이
     *                          그 수를 싣는다. 안 실으면 「새 공시를 못 받고 낡은 판도 못 걷은 종목」이 SUCCESS 뒤에 숨는다.
     * @param unfetchedSupplements 받지 못한 보충의 종류({@code FinancialFetch.unfetchedSupplements}) — 쓰기와 무관한 수집의
     *                          사실이지만 잡이 받는 결과가 이것 하나라 수집기가 실어 넘긴다. 잡이 그 종목의 시장을 미완료로 센다
     *                          (ADR-0174). 쓰기 계층은 비워서 낸다.
     * @param filingTrace       수집이 원장 응답에서 본 공시 번호({@code FilingTrace}) — 공시 소비의 근거라 수집기가 실어 넘긴다
     *                          (ADR-0176 2차). 쓰기 계층은 비워서 낸다.
     */
    public record Outcome(long changed, int inserted, int filled, int revised,
                          int deferredRevisions, int corrected, int insertedEarlier, boolean retractionStopped,
                          List<DataSourceException.Kind> unfetchedSupplements, FilingTrace filingTrace) {

        public Outcome {
            unfetchedSupplements = List.copyOf(unfetchedSupplements);
            filingTrace = filingTrace == null ? FilingTrace.NONE : filingTrace;
        }

        public Outcome(long changed, int inserted, int filled, int revised, int deferredRevisions, int corrected,
                       int insertedEarlier, boolean retractionStopped, List<DataSourceException.Kind> unfetchedSupplements) {
            this(changed, inserted, filled, revised, deferredRevisions, corrected, insertedEarlier, retractionStopped,
                    unfetchedSupplements, FilingTrace.NONE);
        }

        public Outcome(long changed, int inserted, int filled, int revised, int deferredRevisions, int corrected,
                       int insertedEarlier, boolean retractionStopped) {
            this(changed, inserted, filled, revised, deferredRevisions, corrected, insertedEarlier, retractionStopped,
                    List.of());
        }

        /** 같은 쓰기 결과에 받지 못한 보충을 싣는다. */
        public Outcome withUnfetchedSupplements(List<DataSourceException.Kind> kinds) {
            return new Outcome(changed, inserted, filled, revised, deferredRevisions, corrected, insertedEarlier,
                    retractionStopped, kinds, filingTrace);
        }

        /** 같은 쓰기 결과에 수집이 본 공시 번호를 싣는다. */
        public Outcome withFilingTrace(FilingTrace trace) {
            return new Outcome(changed, inserted, filled, revised, deferredRevisions, corrected, insertedEarlier,
                    retractionStopped, unfetchedSupplements, trace);
        }
    }

    /**
     * 한 종목의 재무제표를 저장한다. {@link WriteMode#APPEND_ONLY}로 동작한다.
     *
     * @return 실제로 저장한 행 수. 이미 있는 보고서는 세지 않는다.
     */
    public long write(long stockId, List<RawFinancialReport> reports) {
        return write(stockId, reports, WriteMode.APPEND_ONLY);
    }

    /**
     * 한 종목의 재무제표를 저장한다.
     *
     * @return <b>실제로 바뀐</b> 행 수 — 신규 + 정정 + 채움. 아무것도 하지 않은 재수집은
     *         세지 않으므로 {@code APPEND_ONLY}에서의 뜻은 도입 전과 같다.
     */
    public long write(long stockId, List<RawFinancialReport> reports, WriteMode mode) {
        return writeDetailed(stockId, reports, mode).changed();
    }

    /**
     * 위와 같되 내역을 함께 낸다.
     *
     * <p>{@link WriteMode#FILL_WITHOUT_REVISION}으로 도는 잡이 <b>무엇을 채우지 못했는지</b>를
     * 세어야 하기 때문에 있다. 행 수만으로는 "채울 것이 없었다"와 "정정공시라 못 채웠다"가
     * 구분되지 않는다.
     */
    public Outcome writeDetailed(long stockId, List<RawFinancialReport> reports, WriteMode mode) {
        return writeDetailed(stockId, reports, mode, false);
    }

    /**
     * 위와 같되 <b>이 호출이 판 이력을 전부 냈는지</b>를 함께 받는다
     * ({@code FinancialFetch.completeVintageHistory}).
     *
     * <p>참이면 회수 자격이 <b>어댑터가 낸 모든 라벨</b>로 넓어진다. 거짓이면 지금까지처럼
     * <b>유도 라벨만</b>이다. <b>기본값이 거짓인 것이 계약의 핵심이다</b> — 선언하지 않은
     * 소스에서 넓히면 그 소스가 다시 주지 않는 판을 지운다.
     */
    public Outcome writeDetailed(long stockId, List<RawFinancialReport> reports, WriteMode mode,
                                 boolean completeVintageHistory) {
        return writeDetailed(stockId, reports, mode, completeVintageHistory, false);
    }

    /**
     * 위와 같되 <b>저장본보다 이른 판을 새 행으로 넣을지</b>를 함께 받는다.
     *
     * <p>{@link #writeOne}은 소스가 최신본보다 이른 공시본을 주고 그 공시일의 행이 없으면
     * 건너뛴다. 그런데 읽기는 봉마다 {@code published_at <= 봉}인 판으로 접으므로(ADR-0128) 그 판이
     * 없으면 <b>그 공시일부터 다음 저장 판의 공시일까지 그 기(期)가 안 보이거나 옛 판 값을 쓴다</b>.
     * 5년 중앙값({@code ValuationHistory.median})은 봉마다 그때 보이던 판으로 배수를 내므로
     * <b>오늘의 저장값</b>까지 바뀐다. 실측(2026-09-17 · 팩트 캐시 1,214종목 스윕 대 DB):
     * 미국 <b>2,596판·683종목</b>이 그렇게 빠져 있었다.
     *
     * <p><b>켜는 조건은 넷이 다 참일 때다</b> — {@link WriteMode#FILL_MISSING} ·
     * {@code completeVintageHistory}(판 이력을 전부 내는 소스 — DART는 거짓이다) · 이 인자 ·
     * 그 공시일의 행이 없음. 넣는 행은 {@code is_latest=false}이고, 호출이 끝나면 공시일 순서와
     * {@code revision_no}가 어긋난 라벨을 {@code (published_at, revision_no, id)} 순으로 0..n
     * 다시 매긴다 — 회수가 지워 번호가 빈 라벨도 그 자리에서 같이 고쳐진다.
     *
     * <p><b>MUST: 스케줄러 경로는 거짓을 넘긴다.</b> 이른 판 삽입은 판 집합을 바꾸는 적재라 관문의 한 번에
     * 속한다(ADR-0161 · {@code CLAUDE.md} 「재적재·백필」). 손으로 켜는 경로는
     * {@code financial-refill --insert-earlier-vintages=true} 하나이고, 지운 지표는 매 평일 화해가 채운다
     * (ADR-0166).
     */
    public Outcome writeDetailed(long stockId, List<RawFinancialReport> reports, WriteMode mode,
                                 boolean completeVintageHistory, boolean insertEarlierVintages) {
        return writeDetailed(stockId, reports, mode, completeVintageHistory, insertEarlierVintages, VintageGuard.NONE);
    }

    /**
     * 소스가 판 이력의 <b>빈칸</b>을 알려 줄 때 — 판 이력을 전부 내는 소스라도 공시 하나를 못 받았을 수 있다
     * ({@code FinancialFetch}의 세 칸 · SEC 공시 보충 ADR-0173).
     *
     * @param unconfirmed 못 실은 공시의 제출일. 회수가 그 날짜의 행을 안 지운다.
     * @param supplemented 집계 출처가 빠뜨려 보충으로 실은 공시의 제출일. 그 날짜의 판은 이른 판이어도 넣는다 — 원장이
     *                    처음부터 준 판이라, 보충의 순서나 배포 시점 저장 상태에 무관하게 같은 판 집합에 닿게. 켜는 조건은
     *                    이른 판 삽입과 같다({@link WriteMode#FILL_MISSING} · 판 이력 전부). 정기 경로의 이른 판 예외는 이것
     *                    하나다(ADR-0173 §결과).
     * @param uncertain   못 실은 공시가 무엇인지조차 모른다 — 그 종목의 회수를 통째로 멈춘다.
     */
    public record VintageGuard(Set<LocalDate> unconfirmed, Set<LocalDate> supplemented, boolean uncertain) {

        public static final VintageGuard NONE = new VintageGuard(Set.of(), Set.of(), false);

        public VintageGuard {
            unconfirmed = unconfirmed == null ? Set.of() : Set.copyOf(unconfirmed);
            supplemented = supplemented == null ? Set.of() : Set.copyOf(supplemented);
        }
    }

    public Outcome writeDetailed(long stockId, List<RawFinancialReport> reports, WriteMode mode,
                                 boolean completeVintageHistory, boolean insertEarlierVintages,
                                 VintageGuard guard) {
        Tally tally = new Tally();
        if (mode != WriteMode.APPEND_ONLY) {
            if (guard.uncertain()) {
                log.warn("판 이력의 빈칸을 몰라 이 종목의 판 회수를 멈춘다. stockId={}", stockId);
            } else {
                retractStaleVintages(stockId, reports, completeVintageHistory, guard.unconfirmed(), tally);
            }
        }
        boolean earlierAllowed = mode == WriteMode.FILL_MISSING && completeVintageHistory;
        boolean insertEarlier = earlierAllowed && insertEarlierVintages;
        Set<Amount> managed = managedColumns(reports);
        for (RawFinancialReport report : reports) {
            boolean insertThisEarlier = insertEarlier
                    || (earlierAllowed && guard.supplemented().contains(report.publishedAt()));
            writeOne(stockId, report, mode, managed, insertThisEarlier, tally);
        }
        if (insertEarlier || tally.insertedEarlier > 0) {
            tally.renumberedLabels = renumberOutOfOrderLabels(stockId);
            if (tally.earliestInsertedPublication != null) {
                // 이른 판은 그 공시일 전의 봉에는 원리적으로 안 보인다 — 기점이 회계기간 시작일이
                // 아니라 공시일인 이유다. 한 종목에 여럿이면 가장 이른 공시일에서 한 번만 지운다.
                invalidationPort.invalidate(stockId, tally.earliestInsertedPublication,
                        IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);
            }
        }
        if (mode != WriteMode.APPEND_ONLY) {
            // 종목 단위로 한 줄만 남긴다. 행마다 찍으면 350종목 × 16행이라 읽을 수 없다.
            // **이 줄은 평일 판당 부피 축이 아니다**(2026-09-13 실측 213 B/줄) — 일일 14:00
            // 사슬은 APPEND_ONLY라 가드에 걸려 안 찍히고, FILL_MISSING·FILL_WITHOUT_REVISION인
            // 주간 재적재(수·목)에서만 뜬다. 로그 소음을 줄일 때 여기를 후보로 세지 않는다.
            log.info("결손 보정. stockId={} 모드={} 신규={} 정정={} 채움={} 정정보류={} 교정={} 무변경={}"
                            + " 이른판삽입={} 재번호라벨={}",
                    stockId, mode, tally.inserted, tally.revised, tally.filled,
                    tally.deferredRevisions, tally.corrected, tally.untouched,
                    tally.insertedEarlier, tally.renumberedLabels);
        }
        return new Outcome(tally.changed(), tally.inserted, tally.filled, tally.revised,
                tally.deferredRevisions, tally.corrected, tally.insertedEarlier,
                guard.uncertain() && mode != WriteMode.APPEND_ONLY);
    }

    /**
     * 공시일 순서와 {@code revision_no}가 어긋난 라벨을 0..n으로 다시 매긴다.
     *
     * <p><b>두 문장인 이유</b>: {@code uq_fs_revision}이 deferrable이 아니라 한 문장으로 옮기면
     * 중간 상태가 인덱스에 걸린다. 먼저 전부 +1000으로 비키고(실측 최대 {@code revision_no}는 8)
     * 목표 번호로 내린다. 둘째 문장의 정렬에 옛 번호가 들어가므로 비킨 뒤에도 순서가 같다.
     *
     * @return 다시 매긴 라벨 수
     */
    private int renumberOutOfOrderLabels(long stockId) {
        List<DerivedLabel> labels = jdbcTemplate.query("""
                SELECT DISTINCT fiscal_year, fiscal_period FROM (
                    SELECT fiscal_year, fiscal_period, revision_no,
                           row_number() OVER (PARTITION BY fiscal_year, fiscal_period
                                              ORDER BY published_at, revision_no, id) - 1 AS expected
                    FROM financial_statement WHERE stock_id = ?) t
                WHERE revision_no <> expected
                """, (rs, n) -> new DerivedLabel(rs.getInt("fiscal_year"), rs.getString("fiscal_period")),
                stockId);
        for (DerivedLabel label : labels) {
            renumberLabel(stockId, label.fiscalYear(), label.fiscalPeriod());
        }
        return labels.size();
    }

    /** 한 라벨을 공시일 순 0..n으로. 두 문장인 이유는 {@link #renumberOutOfOrderLabels}에 있다. */
    private void renumberLabel(long stockId, int fiscalYear, String fiscalPeriod) {
        jdbcTemplate.update("""
                UPDATE financial_statement SET revision_no = revision_no + 1000
                WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ?
                """, stockId, fiscalYear, fiscalPeriod);
        jdbcTemplate.update("""
                UPDATE financial_statement f SET revision_no = r.expected
                FROM (SELECT id, row_number() OVER (ORDER BY published_at, revision_no, id) - 1 AS expected
                      FROM financial_statement
                      WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ?) r
                WHERE f.id = r.id
                """, stockId, fiscalYear, fiscalPeriod);
    }

    /** 유도 라벨 하나. */
    private record DerivedLabel(int fiscalYear, String fiscalPeriod) {
    }

    /**
     * <b>유도한 라벨은 판을 세트째 바꾼다.</b>
     *
     * <p>저장은 판을 <b>공시일로</b> 식별하고 {@link #writeOne}은 <b>더 이른 공시본을 건너뛴다.</b>
     * 그래서 유도 규칙이 「이 세대는 못 만든다」로 바뀌면 <b>이미 저장된 그 세대의 행을 회수할
     * 길이 없다</b> — 옛 규칙이 만든 틀린 행이 {@code is_latest}인 채 영영 남고, 새 규칙이 낸
     * 더 이른 세대는 「더 이른 공시본」으로 버려진다.
     *
     * <p>실측(2026-09-14 · {@code AIG} 2022): FY가 56,437 → 54,450 → <b>29,996</b>으로 세 번
     * 바뀌는 동안 분기는 2023-11 이후 한 번도 안 바뀌었다. 옛 규칙이 최신끼리 빼 만든
     * <b>−12,609</b>가 {@code is_latest}이고, 새 규칙이 내는 2024-02-14 세대의 11,845는
     * 공시일이 더 일러서 저장에 닿지 못한다.
     *
     * <p><b>MUST: 유도 라벨에만 건다.</b> 소스가 준 판을 세트로 다루면 그 판의 이력이 사라진다 —
     * 적재된 원자료는 되돌리지 않는 것이 규칙이다(ADR-0071).
     *
     * <p><b>MUST NOT: 적재 창을 좁힌 채 돌리지 않는다.</b> 창 밖의 세대는 이번 세트에 안 들어오고,
     * 그러면 이 회수가 <b>멀쩡한 옛 세대를 지운다</b> — ADR-0145가 창을 안 좁히는 이유에 이 축이
     * 하나 더 붙는다.
     */
    private void retractStaleVintages(long stockId, List<RawFinancialReport> reports,
                                      boolean completeVintageHistory, Set<LocalDate> unconfirmed, Tally tally) {
        // 라벨 자격은 「유도 행이 하나라도 있는가」이고, 보존 집합은 그 라벨로 **들어온 판 전부**다.
        // 유도 행만 보존 집합에 넣으면, 같은 (연도,분기)에 소스가 직접 준 판이 함께 지워진다 —
        // 미국에서 실제로 있는 형태다(다음 해 10-K가 전년 4분기를 비교로 낸다. 실측 2026-09-15:
        // CAH 2016·MDLZ 2015가 그 자리이고 리플레이가 derived=false로 답한다).
        // 지금은 같은 트랜잭션이 곧바로 다시 써서 값이 안 사라지지만, **이력과 created_at은
        // 매 실행 지워지고**, 소스가 그 판을 그만 내는 순간 조용히 유실된다.
        // **자격은 소스의 계약이 정한다.** 판 이력을 전부 내는 소스에서는 「여기 없는 공시일」이
        // 곧 「더 이상 그 라벨의 판이 아니다」이므로 **모든 라벨**이 자격을 갖는다. 그렇지 않은
        // 소스에서는 지금까지처럼 **유도 라벨만**이다 — 그 소스가 안 주는 판을 지우면
        // 되돌릴 수 없다(FinancialFetch.completeVintageHistory의 DART 실측).
        //
        // **어댑터가 그 라벨을 하나도 안 내면 한 행도 안 건드린다** — 이것이 넓힌 자격의
        // 유일한 안전장치다. 실측(2026-09-16)으로 `DSGX` 2020/FY · `LW` 2017/Q2
        // **2라벨 3행**이 그 갈래로 보호된다(그 둘은 앵커 결함이라 따로 연다).
        // 같은 이유로 적재 창을 좁혀 돌려도 창 밖 라벨은 안 지워진다.
        Set<DerivedLabel> derivedLabels = new LinkedHashSet<>();
        for (RawFinancialReport report : reports) {
            if (completeVintageHistory || report.derived()) {
                derivedLabels.add(new DerivedLabel(report.fiscalYear(), report.fiscalPeriod()));
            }
        }
        Map<DerivedLabel, List<RawFinancialReport>> byLabel = new LinkedHashMap<>();
        for (RawFinancialReport report : reports) {
            DerivedLabel label = new DerivedLabel(report.fiscalYear(), report.fiscalPeriod());
            if (derivedLabels.contains(label)) {
                byLabel.computeIfAbsent(label, key -> new ArrayList<>()).add(report);
            }
        }
        for (Map.Entry<DerivedLabel, List<RawFinancialReport>> entry : byLabel.entrySet()) {
            DerivedLabel label = entry.getKey();
            // 못 실은 공시의 제출일은 출력에 없어도 지킨다 — 그 판이 「더 이상 그 라벨의 판이 아니다」인지 이번 출력으로는
            // 모른다(VintageGuard.unconfirmed).
            List<LocalDate> keep = Stream.concat(
                            entry.getValue().stream().map(RawFinancialReport::publishedAt), unconfirmed.stream())
                    .distinct().toList();
            Object[] args = new Object[keep.size() + 3];
            args[0] = stockId;
            args[1] = label.fiscalYear();
            args[2] = label.fiscalPeriod();
            for (int i = 0; i < keep.size(); i++) {
                args[i + 3] = keep.get(i);
            }
            String placeholders = keep.stream().map(date -> "?").collect(Collectors.joining(", "));
            int removed = jdbcTemplate.update(
                    "DELETE FROM financial_statement WHERE stock_id = ? AND fiscal_year = ? "
                            + "AND fiscal_period = ? AND published_at NOT IN (" + placeholders + ")",
                    args);
            if (removed == 0) {
                continue;
            }
            // 최신본이 지워졌을 수 있다. 부분 유니크 인덱스가 「정확히 하나」를 요구하므로
            // 전부 내린 뒤 하나만 올린다 — 한 문장으로 바꾸면 중간 상태가 인덱스에 걸린다.
            jdbcTemplate.update(
                    "UPDATE financial_statement SET is_latest = false "
                            + "WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND is_latest",
                    stockId, label.fiscalYear(), label.fiscalPeriod());
            jdbcTemplate.update(
                    "UPDATE financial_statement SET is_latest = true WHERE id = ("
                            + "SELECT id FROM financial_statement "
                            + "WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? "
                            + "ORDER BY published_at DESC, revision_no DESC LIMIT 1)",
                    stockId, label.fiscalYear(), label.fiscalPeriod());
            log.warn("유도 판을 회수한다. stockId={} {}/{} 지운 판={} 남긴 공시일={}",
                    stockId, label.fiscalYear(), label.fiscalPeriod(), removed, keep);
            tally.corrected += removed;
            invalidationPort.invalidate(stockId, entry.getValue().get(0).periodStart(),
                    IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);
        }
    }

    /**
     * <b>이 호출이 다루는 컬럼.</b> 한 행에서라도 값이 난 컬럼만 든다.
     *
     * <p><b>왜 필요한가</b>: {@link #fillMissing}의 값 지우기 갈래는 「유도 행은 우리 계산이니
     * 새 규칙이 못 만들면 옛 값이 남을 이유가 없다」에 기댄다. 그런데 <b>그 컬럼이 애초에
     * 그 유도의 산물이 아닐 수 있다.</b> 국내 주요계정의 유도 4분기는
     * {@code cash_and_equivalents}·{@code inventories}·{@code net_income_controlling}을
     * <b>언제나 {@code null}로 낸다</b>(그 엔드포인트가 그 계정을 안 준다 — 생성자에 상수
     * {@code null}이 박혀 있다). 그 값을 채운 것은 <b>전체 재무제표</b>인데, 지우기가
     * 그것을 함께 지웠다 — 수요일이 채우고 목요일이 지우는 <b>왕복</b>이다.
     *
     * <p><b>실측(2026-09-16 재적재)</b>: KOSPI 52종목까지 도는 동안
     * {@code cash_and_equivalents} <b>226</b> · {@code inventories} <b>217</b> ·
     * {@code net_income_controlling} <b>149</b> · {@code retained_earnings} 2 —
     * <b>594칸</b>이 지워졌다. 같은 실행의 미국 65칸은 <b>정당하다</b>(불가능한 음수 유량을
     * 가드가 막아 낸 {@code null}이고, 그 컬럼들은 다른 행에 값이 있어 관할이다).
     *
     * <p><b>호출 단위로 근사하는 것이 맞다</b> — 컬럼마다 출처를 실으면 레코드가 커지는데,
     * 「이 호출에서 한 번도 값이 안 난 컬럼」은 <b>그 소스가 안 다루는 컬럼</b>과 실질적으로
     * 같고, 틀리는 쪽이 <b>안 지우는 쪽</b>이라 안전하다.
     *
     * <p><b>[2026-09-21] 위 「상수 {@code null}」 서술은 이제 국내 요약본 경로에만 참이다.</b>
     * ADR-0159를 실행하면서 <b>미국 {@code toReport}·{@code buildQ4}가 둘 다
     * {@code net_income_controlling}을 만들고</b>, 국내 전체 재무제표는 {@code FY}·분기와
     * <b>{@code toFourthQuarterBalance}까지</b> {@code total_equity_controlling}을 만든다.
     * <b>[2026-09-22] 국내 전체 재무제표는 {@code net_income_controlling}도 만든다</b> —
     * 직접 태그 → 「연결총액 − 비지배손익」 → 비지배 전무 게이트의 네 갈래다.
     * 그래서 두 컬럼이 이 집합에 <b>들어오기 시작한다.</b>
     *
     * <p><b>그래도 왕복은 안 생긴다 — 유도 경로를 둘 다 채웠기 때문이다.</b> 관할 판정은
     * 컬럼별이고 이 집합은 <b>한 호출</b>의 보고서만 본다:
     * <ul>
     *   <li>{@code financial-refill}(국내 요약본) — 두 컬럼이 전 행 {@code null}이라 관할이
     *       아니고 지우지 않는다.</li>
     *   <li>{@code full-statement-refill}(국내 전체본) — 유도 Q4도 값을 내므로 지울 칸이 없다.</li>
     *   <li>미국 {@code financial-refill} — {@code buildQ4}가 값을 낸다.</li>
     * </ul>
     * <b>MUST: 유도 경로 하나라도 그 컬럼을 상수 {@code null}로 되돌리면 그 순간 왕복이
     * 생긴다</b> — 수요일이 채우고 목요일이 지운다. 무증상이고 실측으로 594칸이 그렇게 사라졌다.
     *
     * <p><b>반대 방향으로도 난다 — 2026-09-22에 실제로 겪었다.</b> 그날
     * {@code DartFullStatementAdapter.toFourthQuarterBalance}에 {@code derived = true}를 넣었는데,
     * 그 메서드는 <b>재무상태표 Q4</b>라 손익을 언제나 {@code null}로 낸다. 같은 호출의
     * {@code FY}·{@code Q1~Q3}가 매출을 내서 이 집합이 그 컬럼을 <b>관할로 판정했고</b>,
     * {@code fillMissing}의 {@code toClear}가 <b>주요계정 어댑터가 유도해 넣은 Q4 손익
     * 975건 · 142종목</b>을 지웠다. <b>관할 판정은 「이 호출에서 값이 난 적 있는가」이지
     * 「이 행이 그 컬럼을 만드는가」가 아니다</b> — 행 단위 출처만 보고 지우기를 열면
     * 그 차이가 그대로 삭제가 된다.
     */
    private static Set<Amount> managedColumns(List<RawFinancialReport> reports) {
        Set<Amount> managed = EnumSet.noneOf(Amount.class);
        for (RawFinancialReport report : reports) {
            for (Amount amount : Amount.values()) {
                if (amount.of(report) != null) {
                    managed.add(amount);
                }
            }
        }
        return managed;
    }

    private void writeOne(long stockId, RawFinancialReport report, WriteMode mode,
                          Set<Amount> managed, boolean insertEarlier, Tally tally) {
        Existing existing = findLatest(stockId, report);

        if (existing == null) {
            // 아직 없는 기간이다. 모드와 무관하게 새로 쓴다 — 편입은 됐는데 재무를 한 번도
            // 받지 못한 종목이 실제로 있어(2026-08-10 기준 7종목) 이 경로로 처음 들어온다.
            insert(stockId, report, 0, true);
            tally.inserted++;
            return;
        }

        if (report.publishedAt().isBefore(existing.publishedAt())) {
            // **그 공시일의 행이 DB에 있으면 그 행을 고친다.** 아래 「혼합」 논거가 막으려는 것은
            // 「그 시점에 없던 값이 **다른 공시일의 행**에 섞이는 것」인데, 같은 공시일의 행을
            // 찾아 고치면 섞이지 않는다. 그리고 그 행은 **지금 실제로 읽힌다** —
            // IndicatorDataReader의 KNOWN_ON 질의에 is_latest도 revision_no 필터도 없고
            // (published_at <= ? 뿐이다) IndicatorContext.visibleAt이 봉마다 다시 접으므로,
            // 비-is_latest 행이 **과거 기준일의 값**이다. 여기를 안 열면 그 행은 쓰인 날의
            // 추출 규칙에 **영구히 얼어 있다** — 어떤 재적재로도 못 고친다.
            //
            // 실측(2026-09-16 · 미국 캐시 1,001종목): 비-is_latest 16,376행 중
            // **값이 다른 것 2,214행·330종목·2,390칸** · **비어 있는데 소스가 주는 것
            // 15,636행·902종목·46,554칸**. 보존 창(256거래일) 안에서 폴드를 이기는 것만 세도
            // **851행·248종목**(교정 139칸 · 채움 3,292칸)이고, 교정 139칸 중 41칸이 50% 넘게
            // 어긋나며 최대는 UHAL 매출 **173배**다.
            //
            // MUST: FILL_MISSING에서만 연다. FILL_WITHOUT_REVISION을 쓰는 것은
            // FullStatementCollector 하나이고 그 어댑터는 **DART 전용**인데(KOSPI·KOSDAQ),
            // DART는 (corp_code, 연도, 보고서코드)마다 판을 하나만 낸다 — 미국의
            // 「companyfacts가 판 집합을 전부 준다」를 그쪽에 이식하는 것이 된다.
            // 실측으로 그 한정 때문에 잃는 표적은 **0행**이다(16,342행 전부 미국·refill 경로).
            // APPEND_ONLY는 일일 갱신이라 판 이력을 다시 쓰면 안 된다.
            Existing vintage = mode == WriteMode.FILL_MISSING
                    ? findVintage(stockId, report) : null;
            if (vintage != null) {
                fillMissing(stockId, vintage, report, managed, tally);
                return;
            }
            // 그 공시일의 행이 아예 없다. 판 이력을 전부 내는 소스에서 손으로 켰을 때만 넣는다
            // (writeDetailed의 5-인자 javadoc). 그 밖에는 건너뛴다 — DART는 판을 하나만 내므로
            // 「이른 판」이 오지 않고, 스케줄러 경로는 백필 상한 때문에 켜지 않는다.
            if (insertEarlier && mode == WriteMode.FILL_MISSING) {
                int provisional = jdbcTemplate.queryForObject("""
                        SELECT coalesce(max(revision_no), -1) + 1 FROM financial_statement
                        WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ?
                        """, Integer.class, stockId, report.fiscalYear(), report.fiscalPeriod());
                // is_latest는 안 건드린다: 더 늦은 공시본이 이미 최신이다.
                insert(stockId, report, provisional, false, storedSharesOfLabel(stockId, report));
                // MUST: 넣은 즉시 그 라벨의 번호를 공시일 순으로 고친다. 호출 끝으로 미루면 같은 호출에서
                // 더 늦은 새 판이 정정 갈래로 오며 `최신 번호 + 1`을 쓰는데, 그 번호가 방금 넣은 임시
                // 번호와 같아 uq_fs_revision 위반으로 그 종목 쓰기가 통째로 롤백된다(시험이 재현한다).
                renumberLabel(stockId, report.fiscalYear(), report.fiscalPeriod());
                tally.insertedEarlier++;
                if (tally.earliestInsertedPublication == null
                        || report.publishedAt().isBefore(tally.earliestInsertedPublication)) {
                    tally.earliestInsertedPublication = report.publishedAt();
                }
                return;
            }
            log.debug("소스가 더 이른 공시본을 줬는데 그 판의 행이 없다. 건너뛴다. "
                            + "stockId={} {}/{} 저장={} 소스={}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(),
                    existing.publishedAt(), report.publishedAt());
            tally.untouched++;
            return;
        }

        if (report.publishedAt().isEqual(existing.publishedAt())) {
            // 재수집이다. 값이 달라졌다면 우리 추출이 바뀐 것이므로 fillMissing이 덮고
            // 그 사실을 남긴다. APPEND_ONLY는 그 판단을 안 하는 모드라 그냥 넘어간다.
            if (mode == WriteMode.APPEND_ONLY) {
                tally.untouched++;
                return;
            }
            fillMissing(stockId, existing, report, managed, tally);
            return;
        }

        // 정정공시다.
        if (mode == WriteMode.FILL_WITHOUT_REVISION) {
            // MUST NOT: 여기서 fillMissing으로 흘려보내지 않는다. 더 늦은 판으로 채우는 것은
            // 더 이른 판으로 채우는 것과 같은 혼합이다 — 행의 다른 컬럼은 옛 공시본의 값인데
            // 한 컬럼만 새 공시본에서 오면, 그 행이 어느 시점의 값인지 물을 수 없게 된다.
            // 채우지 못한 채로 남기고 그 사실을 드러내는 것이 옳다.
            log.warn("정정공시라 채우지 않고 남긴다. stockId={} {}/{} 저장공시일={} 소스공시일={}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(),
                    existing.publishedAt(), report.publishedAt());
            tally.deferredRevisions++;
            return;
        }

        // 기존 최신본을 내리고 새 개정본을 쌓는다.
        log.info("정정공시를 반영한다. stockId={} {}/{} 기존공시일={} 새공시일={}",
                stockId, report.fiscalYear(), report.fiscalPeriod(),
                existing.publishedAt(), report.publishedAt());
        jdbcTemplate.update("""
                UPDATE financial_statement SET is_latest = false
                WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND is_latest
                """, stockId, report.fiscalYear(), report.fiscalPeriod());

        // 이 기간의 수치로 계산된 PER·ROE 등이 전부 틀린 값이 되었다. 지우지 않으면
        // 낡은 값이 남는데, 그것은 그럴듯해 보여서 아무도 눈치채지 못한다.
        // 기준일이 공시일이 아니라 회계기간 시작일인 이유: 틀린 수치가 영향을 준 구간은
        // 정정이 공시된 날이 아니라 그 수치가 쓰이기 시작한 날부터다.
        invalidationPort.invalidate(stockId, report.periodStart(),
                IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);

        insert(stockId, report, existing.revisionNo() + 1, true);
        tally.revised++;
    }

    /**
     * 같은 공시본의 <b>NULL 컬럼만</b> 채운다.
     *
     * <p><b>NULL을 채울 때는 무효화하지 않고, 값을 덮을 때는 무효화한다.</b> 둘은 다른 일이다.
     *
     * <ul>
     *   <li><b>채우기는 「행 없음 → 행 생김」이다.</b> 금액을 소비하는 지표는 전부 null
     *       전파형이라({@code FinancialMath.sumTrailingFourQuarters}가 한 분기라도 null이면
     *       null을 낸다) 채우기 전에는 <b>지울 낡은 값이 아예 없다</b>. 게다가
     *       {@code StoredIndicatorInvalidationPort}는 지표를 가리지 않고 그 종목의
     *       {@code trade_date >= fromDate}를 <b>전부</b> 지우므로, 전 종목에 부르면 보존 창이
     *       통째로 날아가고 백분위는 날짜별 계산이라 재계산으로 되살아나지 않는다.
     *   <li><b>덮어쓰기는 「틀린 값 → 다른 값」이다.</b> 그 값으로 계산된 지표가 <b>이미 저장돼
     *       있으므로</b> 위 논거가 성립하지 않는다. 안 지우면 낡은 값이 그대로 남는데 그것은
     *       그럴듯해서 아무도 눈치채지 못한다.
     * </ul>
     *
     * <p><b>MUST: 같은 공시일에 값이 달라지면 덮고, 무엇이 무엇으로 바뀌었는지 남긴다.</b>
     * 예전에는 덮지 않고 경고만 남겼는데, 그 규칙이 지킨 것은 데이터가 아니라 <b>틀린 값</b>이었다 —
     * 매출 태그 우선순위를 고쳤을 때 US_TOP1000의 92종목이 과소계상인 채로 남았고, 그때 화면에는
     * 이미 틀린 지표 값 542개가 나가 있었다. 얼려 두는 것은 「소스 오류인지 우리 파싱 오류인지」를
     * 알려주지도 않는다. 위험한 것은 갱신이 아니라 <b>조용한</b> 갱신이므로 로그와 무효화를 함께 낸다.
     *
     * <p><b>MUST NOT: 이 경로를 소스가 값을 바꿨을 때의 방어선으로 쓰지 않는다.</b> 여기 오는 것은
     * <b>같은 보고서</b>({@code published_at}이 같다)이므로 값이 달라졌다면 우리 추출이 바뀐 것이다.
     * 소스가 실제로 고쳐 낸 것은 공시일이 더 늦어 정정 갈래로 간다.
     */
    private void fillMissing(long stockId, Existing existing, RawFinancialReport report,
                             Set<Amount> managed, Tally tally) {
        List<Amount> toFill = new ArrayList<>();
        List<Amount> toCorrect = new ArrayList<>();
        List<Amount> toClear = new ArrayList<>();
        List<String> corrections = new ArrayList<>();

        for (Amount amount : Amount.values()) {
            BigDecimal incoming = amount.of(report);
            if (incoming == null) {
                // MUST: **우리가 만든 행에서만** 값을 지운다. 소스가 준 행에서 컬럼이 빠진 것은
                // 「그 판이 그 컬럼을 안 실었다」이지 「그 값이 없다」가 아니라, 지우면 정정
                // 하나가 그 기간의 손익을 통째로 날린다(SecEdgarFacts의 carryForward가 막는 자리).
                //
                // 유도 행은 다르다. 그것은 우리 계산이고, 새 규칙이 「못 만든다」고 판정했으면
                // 옛 계산이 남아 있을 이유가 없다. 이 갈래가 없으면 **유도 규칙을 고쳐도 옛
                // 값을 영영 못 지운다** — 실측(2026-09-14)으로 미국 4분기 음수 매출 45행·
                // 매출원가 41행이 재적재 뒤에도 한 행도 안 줄었다.
                // [2026-09-22] **행 단위 출처만으로는 못 닫는 자리가 있다.**
                // ADR-0159로 net_income이 연결 총액이 되면서 어댑터가 「이 기간의 연결 총액은
                // 못 만든다」로 null을 내기 시작했는데, 그 판이 **소스 행**(derived=false)이라
                // 위 규칙이 안 지우고 **옛 지배주주 값**이 남았다 — 실측 5,366칸 · 260종목.
                //
                // assertedAbsent는 **어댑터가 컬럼마다 낸 판정**이다. SecEdgarFacts는
                // carryForward로 판을 가로질러 값을 이어받은 **뒤에** 유도하므로, 그 결과의
                // null은 「이 판이 안 실었다」가 아니라 「모든 판을 합쳐도 재료가 없다」이다.
                //
                // MUST: managed 가드는 그대로 태운다. 그 컬럼이 이 호출에서 한 번도 값이 안
                // 났으면 **그 소스가 안 다루는 컬럼**이고, 지우면 다른 소스가 채운 값을
                // 왕복으로 날린다(managedColumns의 594칸 실측).
                boolean assertedByAdapter = report.assertedAbsent().contains(amount.column);
                if ((report.derived() || assertedByAdapter)
                        && amount.overwritable() && managed.contains(amount)
                        && existing.amounts().get(amount) != null) {
                    toClear.add(amount);
                    corrections.add("%s %s -> (없음)".formatted(
                            amount.column, existing.amounts().get(amount)));
                }
                continue;
            }
            BigDecimal stored = existing.amounts().get(amount);
            if (stored == null) {
                toFill.add(amount);
            } else if (stored.compareTo(incoming) != 0 && amount.overwritable()) {
                // scale이 달라도 같은 값이면 여기 오지 않는다(compareTo).
                toCorrect.add(amount);
                corrections.add("%s %s -> %s".formatted(amount.column, stored, incoming));
            }
        }

        // **기간과 보고서코드도 같은 공시의 산물이다.** 그 셋은 INSERT 한 자리에만 있어
        // UPDATE 경로가 없었고, 그래서 **우리가 기간 규칙을 고쳐도 저장된 행은 안 바뀌었다** —
        // 실측(2026-09-16): 같은 공시일인데 어댑터와 기간이 다른 행이 **미국 26(is_latest 18)**
        // 이고, 국내는 비12월 결산 종목이 **3개월** 어긋난 채 얼어 있다.
        // 그 결손을 안 열면 국내 회계기간을 고쳐도 **재적재가 0행을 바꾼다.**
        //
        // MUST NOT: `currency_code`는 여기 안 넣는다. 같은 공시일에 통화가 다른 행은
        // **실측 0**이고, 그 컬럼은 ADR-0094의 읽기 게이트를 흔든다 — 근거 없이 건드릴 자리가 아니다.
        boolean periodDiffers = !existing.periodStart().equals(report.periodStart())
                || !existing.periodEnd().equals(report.periodEnd());
        boolean reportCodeDiffers =
                !java.util.Objects.equals(existing.reportCode(), report.reportCode());

        if (toFill.isEmpty() && toCorrect.isEmpty() && toClear.isEmpty()
                && !periodDiffers && !reportCodeDiffers) {
            tally.untouched++;
            return;
        }

        List<Amount> columns = new ArrayList<>(toFill);
        columns.addAll(toCorrect);
        columns.addAll(toClear);
        List<String> setParts = columns.stream()
                .map(amount -> amount.column + " = ?")
                .collect(Collectors.toCollection(ArrayList::new));
        List<Object> values = new ArrayList<>();
        for (Amount amount : columns) {
            values.add(amount.of(report));
        }
        if (periodDiffers) {
            setParts.add("period_start = ?");
            values.add(report.periodStart());
            setParts.add("period_end = ?");
            values.add(report.periodEnd());
        }
        if (reportCodeDiffers) {
            setParts.add("report_code = ?");
            values.add(report.reportCode());
        }
        values.add(existing.id());

        jdbcTemplate.update(
                "UPDATE financial_statement SET " + String.join(", ", setParts) + " WHERE id = ?",
                values.toArray());

        if (periodDiffers) {
            // **기간이 바뀌면 값이 그대로여도 결과가 바뀐다** — `latestPerPeriod`가 `period_end`로
            // 접고 TTM 창이 기간으로 정해진다. 무효화 하한은 **옛 시작일과 새 시작일 중 이른 쪽**이다:
            // 창이 앞으로 늘어난 경우 새 시작일만 쓰면 덜 지워진다.
            LocalDate from = existing.periodStart().isBefore(report.periodStart())
                    ? existing.periodStart() : report.periodStart();
            log.warn("기간을 고쳐 쓴다. stockId={} {}/{} 공시일={} {}~{} -> {}~{}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt(),
                    existing.periodStart(), existing.periodEnd(),
                    report.periodStart(), report.periodEnd());
            tally.corrected++;
            invalidationPort.invalidate(stockId, from,
                    IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);
        }

        if (!toFill.isEmpty()) {
            tally.filled++;
        }
        if (!toCorrect.isEmpty() || !toClear.isEmpty()) {
            // 무엇이 무엇으로 바뀌었는지를 반드시 남긴다. 이 경로의 위험은 갱신 자체가 아니라
            // 조용함이고, 그래서 규칙이 "덮되 남긴다"다.
            log.warn("우리 추출이 바뀌어 저장값을 덮는다. stockId={} {}/{} 공시일={} — {}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt(),
                    String.join(" | ", corrections));
            tally.corrected += toCorrect.size() + toClear.size();
            // 여기는 "행 없음 → 행 생김"이 아니라 "틀린 값 → 다른 값"이다. 그 값으로 계산된
            // 지표가 이미 저장돼 있으므로 지우지 않으면 낡은 값이 그대로 남는다.
            invalidationPort.invalidate(stockId, report.periodStart(),
                    IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);
        }
    }

    /**
     * 같은 회계기간의 현재 최신본을 찾는다.
     *
     * <p>{@code uq_fs_latest} 부분 유니크 인덱스가 최신본이 정확히 하나임을 보장하므로
     * 여러 행이 돌아올 수 없다.
     *
     * <p>금액 컬럼을 함께 읽는 이유는 {@link #fillMissing}이 "무엇이 비어 있는가"를 알아야
     * 하기 때문이다. 컬럼 목록을 {@link Amount}에서 만들어 SELECT와 SET이 갈라지지 않게 한다.
     */
    private Existing findLatest(long stockId, RawFinancialReport report) {
        String columns = Arrays.stream(Amount.values())
                .map(amount -> amount.column)
                .collect(Collectors.joining(", "));
        List<Existing> found = jdbcTemplate.query("""
                SELECT id, published_at, revision_no, period_start, period_end,
                       report_code, %s FROM financial_statement
                WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND is_latest
                """.formatted(columns),
                (rs, rowNum) -> {
                    Map<Amount, BigDecimal> amounts = new EnumMap<>(Amount.class);
                    for (Amount amount : Amount.values()) {
                        amounts.put(amount, rs.getBigDecimal(amount.column));
                    }
                    return new Existing(
                            rs.getLong("id"),
                            rs.getObject("published_at", LocalDate.class),
                            rs.getInt("revision_no"),
                            rs.getObject("period_start", LocalDate.class),
                            rs.getObject("period_end", LocalDate.class),
                            rs.getString("report_code"),
                            amounts);
                },
                stockId, report.fiscalYear(), report.fiscalPeriod());
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 같은 회계기간의 <b>그 공시일 판</b>을 찾는다. {@link #findLatest}와 달리
     * {@code is_latest}를 안 본다.
     *
     * <p><b>정렬을 박는 이유</b>: {@code (stock_id, fiscal_year, fiscal_period, published_at)}이
     * 둘 이상인 행은 실측으로 <b>0건</b>이지만(2026-09-16 · 216,863행 전수)
     * <b>그것을 강제하는 유니크 인덱스가 없다.</b> {@code uq_fs_revision}은 {@code revision_no}를
     * 키에 두므로 같은 공시일에 판번호만 다른 행을 막지 않는다. 언젠가 둘이 되면 정렬이
     * 없는 쪽은 <b>실행마다 다른 행을 고친다.</b>
     */
    private Existing findVintage(long stockId, RawFinancialReport report) {
        String columns = Arrays.stream(Amount.values())
                .map(amount -> amount.column)
                .collect(Collectors.joining(", "));
        List<Existing> found = jdbcTemplate.query(Q_FIND_VINTAGE.formatted(columns),
                (rs, rowNum) -> {
                    Map<Amount, BigDecimal> amounts = new EnumMap<>(Amount.class);
                    for (Amount amount : Amount.values()) {
                        amounts.put(amount, rs.getBigDecimal(amount.column));
                    }
                    return new Existing(
                            rs.getLong("id"),
                            rs.getObject("published_at", LocalDate.class),
                            rs.getInt("revision_no"),
                            rs.getObject("period_start", LocalDate.class),
                            rs.getObject("period_end", LocalDate.class),
                            rs.getString("report_code"),
                            amounts);
                },
                stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt());
        return found.isEmpty() ? null : found.get(0);
    }

    private static final String Q_FIND_VINTAGE = """
            SELECT id, published_at, revision_no, period_start, period_end,
                   report_code, %s FROM financial_statement
            WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND published_at = ?
            ORDER BY revision_no DESC
            """;

    private void insert(long stockId, RawFinancialReport report, int revisionNo, boolean isLatest) {
        insert(stockId, report, revisionNo, isLatest, report.sharesOutstanding());
    }

    /**
     * 이른 판에 넣을 주식수 — 같은 라벨에 저장된 값이 하나로 모이면 그것, 아니면 소스 값.
     *
     * <p>주식수는 기간을 낸 보고서가 아니라 <b>라벨 앵커의 표지</b>에서 오므로({@link Amount#overwritable()})
     * 소스는 같은 라벨의 모든 판에 같은 값을 준다. 저장된 값이 그것과 다르면 저장값이 손 수리(ADR-0126)이거나
     * 옛 추출이 고른 축척이고, 기존 행은 {@code overwritable()}이 그 값을 지킨다. <b>새 행 삽입은 그 보호를 거치지
     * 않으므로 여기서 같은 규칙을 건다</b> — 안 걸면 손으로 고친 축척이 이른 판으로 되살아난다
     * (실측 2026-09-17 사본 재생: 삽입 2,565판 중 저장값과 다른 것이 `RPAY` 2023 Q4 하나 — 90,048,638,000 대 90,048,638).
     *
     * <p>저장된 값이 둘 이상으로 갈리면(손 수리가 일부 판에만 닿은 라벨 — 같은 날 US 12라벨) 어느 쪽도 정본이라
     * 말할 수 없어 소스 값을 쓰고 경고로 남긴다. 그날 사본 재생에서 이 갈래에 걸린 삽입은 0이다.
     */
    private BigDecimal storedSharesOfLabel(long stockId, RawFinancialReport report) {
        List<BigDecimal> stored = jdbcTemplate.queryForList("""
                SELECT DISTINCT shares_outstanding FROM financial_statement
                WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND shares_outstanding IS NOT NULL
                """, BigDecimal.class, stockId, report.fiscalYear(), report.fiscalPeriod());
        BigDecimal incoming = report.sharesOutstanding();
        if (stored.size() == 1) {
            BigDecimal kept = stored.get(0);
            if (incoming == null || incoming.compareTo(kept) != 0) {
                log.info("이른 판의 주식수를 저장값으로 잇는다. stockId={} {}/{} 공시일={} 소스={} 저장={}",
                        stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt(), incoming, kept);
            }
            return kept;
        }
        if (stored.size() > 1) {
            log.warn("이른 판의 주식수를 이을 저장값이 갈린다 — 소스 값을 쓴다. stockId={} {}/{} 공시일={} 소스={} 저장={}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt(), incoming, stored);
        }
        return incoming;
    }

    private void insert(long stockId, RawFinancialReport report, int revisionNo, boolean isLatest,
                        BigDecimal sharesOutstanding) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                     revision_no, is_latest, report_code, currency_code,
                     revenue, operating_income, net_income, total_assets, total_equity,
                     total_equity_controlling,
                     total_liabilities, current_assets, current_liabilities, retained_earnings,
                     operating_cash_flow, shares_outstanding, eps, bps,
                     cost_of_sales, cash_and_equivalents, capex, inventories,
                     net_income_controlling,
                     interest_and_dividend_income, noninterest_income, interest_expense,
                     noninterest_expense, benefits_losses_and_expenses,
                     gross_profit, operating_expenses, costs_and_expenses,
                     income_tax_expense, profit_before_tax, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                stockId, report.fiscalYear(), report.fiscalPeriod(),
                report.periodStart(), report.periodEnd(), report.publishedAt(),
                revisionNo, isLatest, report.reportCode(), report.currencyCode(),
                report.revenue(), report.operatingIncome(), report.netIncome(),
                report.totalAssets(), report.totalEquity(), report.totalEquityControlling(),
                report.totalLiabilities(),
                report.currentAssets(), report.currentLiabilities(), report.retainedEarnings(),
                report.operatingCashFlow(), sharesOutstanding,
                report.eps(), report.bps(),
                report.costOfSales(), report.cashAndEquivalents(), report.capex(),
                report.inventories(), report.netIncomeControlling(),
                report.interestAndDividendIncome(), report.noninterestIncome(),
                report.interestExpense(), report.noninterestExpense(),
                report.benefitsLossesAndExpenses(),
                report.grossProfit(), report.operatingExpenses(), report.costsAndExpenses(),
                report.incomeTaxExpense(), report.profitBeforeTax());
    }

    /**
     * 채움 대상이 되는 금액 컬럼과 그 소스 필드.
     *
     * <p>MUST: 컬럼을 더하면 여기에도 더한다. {@link #findLatest}의 SELECT와
     * {@link #fillMissing}의 SET이 <b>둘 다 이 목록에서 만들어지므로</b> 한쪽만 늘어나
     * 어긋나는 일은 생기지 않는다 — 빠뜨리면 그 컬럼이 영원히 안 채워질 뿐이다.
     *
     * <p>{@code report_code}·{@code currency_code}는 없다. 결손 보정의 대상은 <b>금액</b>이고,
     * 그 둘은 행의 정체를 말하는 메타데이터라 뒤늦게 바뀌면 다른 행이라는 뜻이 된다.
     */
    private enum Amount {
        REVENUE("revenue", RawFinancialReport::revenue),
        OPERATING_INCOME("operating_income", RawFinancialReport::operatingIncome),
        NET_INCOME("net_income", RawFinancialReport::netIncome),
        NET_INCOME_CONTROLLING("net_income_controlling",
                RawFinancialReport::netIncomeControlling),
        TOTAL_ASSETS("total_assets", RawFinancialReport::totalAssets),
        TOTAL_EQUITY("total_equity", RawFinancialReport::totalEquity),
        TOTAL_EQUITY_CONTROLLING("total_equity_controlling",
                RawFinancialReport::totalEquityControlling),
        TOTAL_LIABILITIES("total_liabilities", RawFinancialReport::totalLiabilities),
        CURRENT_ASSETS("current_assets", RawFinancialReport::currentAssets),
        CURRENT_LIABILITIES("current_liabilities", RawFinancialReport::currentLiabilities),
        RETAINED_EARNINGS("retained_earnings", RawFinancialReport::retainedEarnings),
        OPERATING_CASH_FLOW("operating_cash_flow", RawFinancialReport::operatingCashFlow),
        SHARES_OUTSTANDING("shares_outstanding", RawFinancialReport::sharesOutstanding),
        EPS("eps", RawFinancialReport::eps),
        BPS("bps", RawFinancialReport::bps),
        COST_OF_SALES("cost_of_sales", RawFinancialReport::costOfSales),
        CASH_AND_EQUIVALENTS("cash_and_equivalents", RawFinancialReport::cashAndEquivalents),
        CAPEX("capex", RawFinancialReport::capex),
        INVENTORIES("inventories", RawFinancialReport::inventories),
        // 영업이익 유도의 재료 여덟(V40). 그 자체가 지표는 아니다.
        INTEREST_AND_DIVIDEND_INCOME("interest_and_dividend_income",
                RawFinancialReport::interestAndDividendIncome),
        NONINTEREST_INCOME("noninterest_income", RawFinancialReport::noninterestIncome),
        INTEREST_EXPENSE("interest_expense", RawFinancialReport::interestExpense),
        NONINTEREST_EXPENSE("noninterest_expense", RawFinancialReport::noninterestExpense),
        BENEFITS_LOSSES_AND_EXPENSES("benefits_losses_and_expenses",
                RawFinancialReport::benefitsLossesAndExpenses),
        GROSS_PROFIT("gross_profit", RawFinancialReport::grossProfit),
        OPERATING_EXPENSES("operating_expenses", RawFinancialReport::operatingExpenses),
        COSTS_AND_EXPENSES("costs_and_expenses", RawFinancialReport::costsAndExpenses),
        INCOME_TAX_EXPENSE("income_tax_expense", RawFinancialReport::incomeTaxExpense),
        PROFIT_BEFORE_TAX("profit_before_tax", RawFinancialReport::profitBeforeTax);

        /**
         * 값이 달라졌을 때 <b>덮어도 되는가.</b>
         *
         * <p>덮기의 근거는 <i>"같은 보고서인데 값이 달라졌다면 우리 추출이 바뀐 것"</i>인데,
         * <b>{@code shares_outstanding}에는 그 전제가 성립하지 않는다.</b> 그 값만은 기간을 낸
         * 보고서가 아니라 <b>라벨 앵커의 표지</b>에서 온다({@code SecEdgarFacts.valuesOf}의
         * {@code anchoredInstant} 갈래) — 같은 기간의 판이 여럿이어도 전부 같은 표지를 보므로,
         * 차이는 우리 추출이 아니라 <b>소스의 두 보고서가 축척을 다르게 낸 것</b>이다.
         *
         * <p>실측(2026-09-07): 이 가드가 없던 사이 `DDS`의 주식수가 8행에서
         * <b>15,655,000 → 15,655</b>로 덮였다. 딜라드의 10-K가 희석주식수를 천 단위로,
         * 같은 회사 10-Q는 단위로 낸다(ADR-0126의 축척 오태깅). 시가총액 = 주식수 × 종가라
         * 그 종목의 시총이 <b>1,000분의 1</b>이 되고 유니버스 순위가 밀린다.
         *
         * <p>{@code SecEdgarFacts}가 같은 이유로 이미 두 곳에서 이 컬럼을 갈라 다룬다 —
         * 「그것만 있는 판은 보고서가 아니다」 판정과 앵커 선택이다.
         */
        boolean overwritable() {
            return this != SHARES_OUTSTANDING;
        }

        private final String column;
        private final Function<RawFinancialReport, BigDecimal> accessor;

        Amount(String column, Function<RawFinancialReport, BigDecimal> accessor) {
            this.column = column;
            this.accessor = accessor;
        }

        BigDecimal of(RawFinancialReport report) {
            return accessor.apply(report);
        }
    }

    private record Existing(long id, LocalDate publishedAt, int revisionNo,
                            LocalDate periodStart, LocalDate periodEnd, String reportCode,
                            Map<Amount, BigDecimal> amounts) {
    }

    /** 한 종목의 처리 결과. 로그 한 줄과 반환값의 근거다. */
    private static final class Tally {
        private int inserted;
        private int revised;
        private int filled;
        private int deferredRevisions;
        private int corrected;
        private int untouched;
        private int insertedEarlier;
        private int renumberedLabels;
        private LocalDate earliestInsertedPublication;

        long changed() {
            return (long) inserted + revised + filled + corrected + insertedEarlier;
        }
    }
}
