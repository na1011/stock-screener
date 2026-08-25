package com.stockscreener.pipeline.collector;

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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 *   <li><b>값이 있는 컬럼은 절대 덮지 않는다.</b> 다르면 {@code WARN}으로 남긴다 — 조용히
 *       넘기면 소스의 오류인지 우리 파싱의 오류인지 영영 알 수 없다.
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
         * 기준일이 회계연도 1월 1일이라 보존 창(180거래일) <b>전량</b>이다. 발화 조건은
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
    public record Outcome(long changed, int inserted, int filled, int revised,
                          int deferredRevisions, int mismatched) {
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
        Tally tally = new Tally();
        for (RawFinancialReport report : reports) {
            writeOne(stockId, report, mode, tally);
        }
        if (mode != WriteMode.APPEND_ONLY) {
            // 종목 단위로 한 줄만 남긴다. 행마다 찍으면 350종목 × 16행이라 읽을 수 없다.
            log.info("결손 보정. stockId={} 모드={} 신규={} 정정={} 채움={} 정정보류={} 불일치={} 무변경={}",
                    stockId, mode, tally.inserted, tally.revised, tally.filled,
                    tally.deferredRevisions, tally.mismatched, tally.untouched);
        }
        return new Outcome(tally.changed(), tally.inserted, tally.filled, tally.revised,
                tally.deferredRevisions, tally.mismatched);
    }

    private void writeOne(long stockId, RawFinancialReport report, WriteMode mode, Tally tally) {
        Existing existing = findLatest(stockId, report);

        if (existing == null) {
            // 아직 없는 기간이다. 모드와 무관하게 새로 쓴다 — 편입은 됐는데 재무를 한 번도
            // 받지 못한 종목이 실제로 있어(2026-08-10 기준 7종목) 이 경로로 처음 들어온다.
            insert(stockId, report, 0);
            tally.inserted++;
            return;
        }

        if (report.publishedAt().isBefore(existing.publishedAt())) {
            // 소스가 더 오래된 판을 줬다. 채우지도 않는다 — 그 시점에 없던 값이 현재
            // 개정본 행에 섞이면 행 하나가 두 공시의 혼합이 되어 어느 시점의 값인지
            // 물을 수 없게 된다. APPEND_ONLY에서는 조용했지만 흔적은 남긴다.
            log.debug("소스가 더 이른 공시본을 줬다. 건너뛴다. stockId={} {}/{} 저장={} 소스={}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(),
                    existing.publishedAt(), report.publishedAt());
            tally.untouched++;
            return;
        }

        if (report.publishedAt().isEqual(existing.publishedAt())) {
            // 재수집이다.
            // MUST NOT: 여기서 값을 덮지 않는다. 값이 달라졌다면 그것은 소스의 오류이거나
            // 우리 파싱의 오류이며, 조용히 갱신하면 어느 쪽인지 영영 알 수 없다.
            if (mode == WriteMode.APPEND_ONLY) {
                tally.untouched++;
                return;
            }
            fillMissing(stockId, existing, report, tally);
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

        insert(stockId, report, existing.revisionNo() + 1);
        tally.revised++;
    }

    /**
     * 같은 공시본의 <b>NULL 컬럼만</b> 채운다.
     *
     * <p>MUST NOT: 여기서 {@link IndicatorInvalidationPort}를 부르지 않는다. 이유가 둘이다.
     *
     * <ul>
     *   <li><b>지울 낡은 값이 없다.</b> 금액을 소비하는 지표는 전부 null 전파형이다 —
     *       {@code FinancialMath.sumTrailingFourQuarters}는 한 분기라도 null이면 null을 내고,
     *       계산기는 그때 행을 아예 쓰지 않는다(ADR-0002). 즉 채우는 것은 "틀린 값 → 다른 틀린
     *       값"이 아니라 <b>"행 없음 → 행 생김"</b>이다.
     *   <li><b>부르면 오히려 파괴적이다.</b> {@code StoredIndicatorInvalidationPort}는 지표를
     *       가리지 않고 <b>그 종목의 {@code trade_date >= fromDate} 전체</b>를 지운다. 회계기간
     *       시작일로 전 종목에 부르면 지표 33종의 보존 창이 통째로 날아가고, 백분위는 날짜별로만
     *       계산되므로 재계산으로 되살아나지 않는다.
     * </ul>
     *
     * <p>이 판단은 <b>채우는 컬럼의 소비 지표에 저장된 값이 없을 때만</b> 성립한다.
     * 다음에 이 경로로 다른 컬럼을 채울 때 다시 답해야 한다(ADR-0040).
     *
     * <p><b>P10에서 다시 답했다 — 여전히 성립한다.</b> 이 경로로 새로 차는 컬럼은
     * {@code operating_cash_flow}(전 행 NULL이었다)와 dart-financial.md의 「계정 집합이 회사마다 다르다」 절의
     * 세 종목의
     * {@code retained_earnings}다.
     * <ul>
     *   <li>OCF를 소비하는 지표는 이 phase에서 <b>처음 생긴다.</b> 저장된 값이 있을 수 없다.
     *   <li>이익잉여금을 소비하는 것은 {@code ALTMAN_Z} 하나이고 최근 분기 잔액
     *       ({@code quarters.get(0)})을 읽는다. 세 종목은 보존 창이 덮는 전 분기에서 그 값이
     *       NULL이라 <b>지표 행이 아예 없다</b>(창 시점 기준 실측). 즉 여기서도
     *       "틀린 값 → 다른 틀린 값"이 아니라 "행 없음 → 행 생김"이다.
     * </ul>
     * 다만 <b>행이 생기는 것 자체가 스크리닝 결과를 바꾼다.</b> 무효화는 필요 없어도
     * 백필은 필요하고, 그 변화는 H-05가 만드는 변화와 분리해서 세어야 한다.
     */
    private void fillMissing(long stockId, Existing existing, RawFinancialReport report,
                             Tally tally) {
        List<Amount> toFill = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();

        for (Amount amount : Amount.values()) {
            BigDecimal incoming = amount.of(report);
            if (incoming == null) {
                continue;
            }
            BigDecimal stored = existing.amounts().get(amount);
            if (stored == null) {
                toFill.add(amount);
            } else if (stored.compareTo(incoming) != 0) {
                // scale이 달라도 같은 값이면 여기 오지 않는다(compareTo).
                mismatches.add("%s 저장=%s 소스=%s".formatted(amount.column, stored, incoming));
            }
        }

        if (!mismatches.isEmpty()) {
            tally.mismatched += mismatches.size();
            log.warn("재수집 값이 저장된 값과 다르다. 덮지 않는다. stockId={} {}/{} 공시일={} — {}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(), report.publishedAt(),
                    String.join(" | ", mismatches));
        }

        if (toFill.isEmpty()) {
            tally.untouched++;
            return;
        }

        String setClause = toFill.stream()
                .map(amount -> amount.column + " = ?")
                .collect(Collectors.joining(", "));
        Object[] args = new Object[toFill.size() + 1];
        for (int i = 0; i < toFill.size(); i++) {
            args[i] = toFill.get(i).of(report);
        }
        args[toFill.size()] = existing.id();

        jdbcTemplate.update(
                "UPDATE financial_statement SET " + setClause + " WHERE id = ?", args);
        tally.filled++;
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
                SELECT id, published_at, revision_no, %s FROM financial_statement
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
                            amounts);
                },
                stockId, report.fiscalYear(), report.fiscalPeriod());
        return found.isEmpty() ? null : found.get(0);
    }

    private void insert(long stockId, RawFinancialReport report, int revisionNo) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                     revision_no, is_latest, report_code, currency_code,
                     revenue, operating_income, net_income, total_assets, total_equity,
                     total_liabilities, current_assets, current_liabilities, retained_earnings,
                     operating_cash_flow, shares_outstanding, eps, bps,
                     cost_of_sales, cash_and_equivalents, capex, inventories,
                     created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, now())
                """,
                stockId, report.fiscalYear(), report.fiscalPeriod(),
                report.periodStart(), report.periodEnd(), report.publishedAt(),
                revisionNo, report.reportCode(), report.currencyCode(),
                report.revenue(), report.operatingIncome(), report.netIncome(),
                report.totalAssets(), report.totalEquity(), report.totalLiabilities(),
                report.currentAssets(), report.currentLiabilities(), report.retainedEarnings(),
                report.operatingCashFlow(), report.sharesOutstanding(),
                report.eps(), report.bps(),
                report.costOfSales(), report.cashAndEquivalents(), report.capex(),
                report.inventories());
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
        TOTAL_ASSETS("total_assets", RawFinancialReport::totalAssets),
        TOTAL_EQUITY("total_equity", RawFinancialReport::totalEquity),
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
        INVENTORIES("inventories", RawFinancialReport::inventories);

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
                            Map<Amount, BigDecimal> amounts) {
    }

    /** 한 종목의 처리 결과. 로그 한 줄과 반환값의 근거다. */
    private static final class Tally {
        private int inserted;
        private int revised;
        private int filled;
        private int deferredRevisions;
        private int mismatched;
        private int untouched;

        long changed() {
            return (long) inserted + revised + filled;
        }
    }
}
