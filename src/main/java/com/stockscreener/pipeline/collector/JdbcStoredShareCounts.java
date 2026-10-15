package com.stockscreener.pipeline.collector;

import com.stockscreener.indicator.ShareBasis;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.UsMarket;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link StoredShareCounts}를 {@code financial_statement}에서 읽는다.
 *
 * <p>구현이 이 패키지에 있는 이유는 <b>DB를 만지는 클래스가 여기 모여 있기</b> 때문이다
 * ({@link JdbcStoredPriceSeries}와 같은 자리). 포트만 저쪽에 두면 어댑터가
 * {@code JdbcTemplate}을 몰라도 되고, 어댑터 테스트가 DB 없이 성립한다.
 *
 * <h2>시점성 — 세 축이 함께 움직여야 한다 (ADR-0135)</h2>
 * <p>{@code asOf}를 받기 전에는 이 질의가 <b>세 자리에서 미래를 봤다.</b> ①{@code is_latest}가
 * 읽는 시점의 최신 개정본이고 ②기업행위 배수에 <b>상한이 없어</b> {@code asOf} 이후 사건까지
 * 곱했으며 ③그 배수의 <b>하한 앵커가 기말</b>이었다. 셋을 따로 고치면 안 된다 —
 * ①만 고치면 그 시점 판을 골라 놓고 미래 분할을 곱하고, ②만 고치면 미래 판에 옛 배수를 건다.
 *
 * <p><b>ADR-0132·0133이 이 클래스를 명시적으로 범위 밖에 뒀던 자리다</b>
 * (<i>"유니버스 순위가 움직이는 것으로 읽지 않는다 — {@code sharesOf}를 안 탄다"</i>).
 * 그 서술은 이 결정으로 <b>거짓이 됐고 두 문서를 함께 고쳤다.</b>
 */
@Component
public class JdbcStoredShareCounts implements StoredShareCounts {

    /**
     * <b>MUST: 이 서브쿼리를 {@code IndicatorDataReader.CURRENCY_GATE}와 같게 유지한다.</b>
     * 그쪽은 {@code NOT EXISTS}이고 여기는 판정을 컬럼으로 내므로 부호가 반대일 뿐, 술어는
     * 글자까지 같아야 한다 — {@code is_latest}를 보지 않고 그 종목의 <b>어느 행이든</b>
     * 통화가 시장 통화와 다르면 참이다.
     *
     * <p>갈리면 <b>유니버스는 담는데 지표는 안 나는 종목</b>이 생긴다. 실측으로 최신 기의
     * 행만 보는 술어를 쓰면 미국에서 134종목이 그 상태가 된다(2026-08-31).
     * {@code UsShareCountCurrencyGateTest}가 이 자리를 잠근다.
     *
     * <p><b>MUST NOT: 통화 리터럴(`'USD'`)로 바꾸지 않는다.</b> 판정 축은 「USD인가」가 아니라
     * 「그 종목이 상장된 시장의 통화와 같은가」다(ADR-0094 §규칙).
     *
     * <p><b>MUST NOT: 이 술어에 {@code asOf}를 걸지 않는다.</b> 통화 불일치는 「그 종목의 재무를
     * 우리가 쓸 수 있는가」에 대한 판정이지 시점 값이 아니다. 시점으로 좁히면 과거 as-of에서
     * 불일치 행이 아직 안 들어온 종목이 <b>통과했다가</b> 오늘 막히는데, 그것은 시점 재현이
     * 아니라 <b>수집 시점의 우연</b>이다.
     */
    private static final String CURRENCY_MISMATCHED = """
            EXISTS (
                SELECT 1
                FROM financial_statement mismatched
                JOIN stock gated ON gated.id = mismatched.stock_id
                JOIN market gate_market ON gate_market.code = gated.market_code
                WHERE mismatched.stock_id = latest.stock_id
                  AND mismatched.currency_code <> gate_market.currency_code)
            """;

    /**
     * 고른 판의 <b>공시일 이후 {@code asOf}까지</b> 반영된 분할·병합만큼 주식수를 옮긴다.
     *
     * <p>주식수는 그 판의 기준일이고 종가는 {@code asOf}라, 그 사이에 분할이 있으면
     * <b>시총이 배수만큼 틀린다.</b> 결측이 아니라 <b>그럴듯한 숫자</b>로 나온다 —
     * 실측(2026-08-31)으로 편입 1,000 중 <b>5종목</b>이 그 상태였고 그중 {@code BYND}는
     * 1:30 병합 전 주식수라 시총이 <b>7.15B로 30배</b> 잡혀 상위 1,000에 <b>잘못 들어왔다</b>
     * (실제 약 0.24B).
     *
     * <p><b>MUST: 하한 앵커가 {@code published_at}이다 — 기말이 아니다</b>(ADR-0133 승계).
     * {@code dei:EntityCommonStockSharesOutstanding}은 <b>보고서 표지일</b> 기준이라 기말 이후
     * 공시 전에 일어난 기업행위가 그 수에 <b>이미 반영돼 있다.</b> 앵커가 기말이면 그 구간을
     * <b>한 번 더</b> 곱한다. 실측(2026-09-09 · 선택 규칙에 맞춰 셈)으로 편입 중 <b>두 종목</b>이
     * 그 상태였다 — {@code MLI} 2.0배 · {@code SNEX} 1.5배.
     *
     * <p><b>MUST NOT: 이 발화를 「원장에 {@code (기말, 공시일]} 사건이 있는 판」으로 세지 않는다.</b>
     * 그렇게 세면 수백 판이 나오는데 <b>대부분 안 틀렸다</b> — 같은 공시가 싣는 전년 비교열은
     * {@code DISTINCT ON}이 안 고르므로 값에 안 닿는다(ADR-0133 §범위).
     *
     * <p><b>MUST: 상한이 있다.</b> 없으면 {@code asOf} 이후의 분할까지 곱해 <b>과거 기준일의
     * 시총이 미래를 본다.</b> 상수 이름이 {@code SHIFT_TO_TODAY}였던 것이 그 상태였다.
     *
     * <p><b>MUST: {@code APPLIED}만 쓴다.</b> 그것이 사람·소스가 확정한 계수라는 뜻이고,
     * {@code OBSERVED}는 <i>"있었는데 아직 못 고쳤다"</i>라 비율이 계수가 아니다(ADR-0049).
     *
     * <p><b>원장이 유니버스보다 먼저다.</b> 기업 행위 수집은 보존 창 편입만 채우므로, 편입 밖 후보는
     * 후보 분할 다리(ADR-0178 · {@code CandidateSplitJob} — 시장 전체 1~3콜)가 공시일 뒤 분할을 채운다(설정
     * {@code stockscreener.us-candidates.enabled}가 켜졌을 때). 예전 문장(「유니버스 밖 전 종목의 원장을 채우는 것이
     * 그 대가다」)은 비용 근거였고, 꺼져 있던 동안 후보 194종목이 병합 전 주식수로 순위에 들어갔다(2026-10-15).
     * <b>MUST NOT: 그것을 이 질의에서 풀려고 하지 않는다</b> — 판독기는 원장만 읽는다.
     *
     * <p>{@code exp(sum(ln(...)))}인 이유는 PostgreSQL에 곱 집계가 없어서다. 비율은
     * {@code ck_ca_ratio_sign}이 양수로 못 박으므로 {@code ln}의 정의역을 벗어나지 않는다.
     */
    private static final String SHIFT_TO_ASOF = """
            coalesce((SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca
                       WHERE ca.stock_id = latest.stock_id
                         AND ca.status = 'APPLIED'
                         AND ca.effective_date > latest.published_at
                         AND ca.effective_date <= ?), 1)
            """;

    /**
     * {@code DISTINCT ON}으로 기(期)를 고른다.
     *
     * <p><b>MUST: 정렬 첫 키는 {@code period_end DESC}다.</b> 순위에 맞는 것은 <b>「그 시점에
     * 공표된 것 중 가장 최근 기」</b>이지 「가장 늦게 공시된 것」이 아니다. 첫 키를
     * {@code published_at}으로 옮기면 <b>뒤늦게 공시된 옛 기가 이긴다</b> — 실측으로 6종목이
     * 그렇게 움직이고 {@code NCRA}는 시총이 <b>7.16배</b>가 된다(2026-06-30의 2.1M주 대신
     * 2026-03-31의 15.3M주를 집는다). ADR-0099 §규칙의 <i>"기를 {@code period_end}로 고르고
     * 개정본을 {@code revision_no}로 고른다"</i>가 그대로 지켜지는 자리다.
     *
     * <p><b>{@code is_latest}를 안 본다.</b> 그것은 <b>읽는 시점의</b> 최신 개정본이라 시간축이
     * 없다. {@code published_at <= asOf}로 자르면 그 시점에 보이던 개정본이 자동으로 골라지므로
     * {@code is_latest}는 더할 것이 없고, 걸면 <b>미래의 정정본만 남은 기가 통째로 사라진다.</b>
     * 정렬 둘째 키가 {@code published_at DESC}인 것이 같은 기 안에서 그 시점의 최신 판을 고른다.
     *
     * <p><b>{@code published_at}에 NULL 갈래를 두지 않는다.</b> 그 컬럼은 스키마가 {@code NOT NULL}로
     * 막으므로(V1) {@code coalesce}나 {@code IS NOT NULL} 가드는 <b>떨어질 수 없는 펜스</b>가 된다
     * (ADR-0074가 금지한 형태).
     *
     * <p>{@code shares_outstanding > 0}을 거는 이유는 0이 「작은 회사」와 구별되지 않기
     * 때문이다({@code MarketCapUniverseAdapter.toCandidate}가 같은 판단을 한다).
     */
    /**
     * <b>MUST: 기저 판정을 바깥 SELECT에서 건다</b>(ADR-0162). 안쪽 {@code WHERE}에
     * {@code share_basis_status}를 넣으면 {@code DISTINCT ON}이 <b>옛 판으로 물러나는데</b>,
     * 기저가 어긋난 것은 종목의 성질이라 그 판도 같은 기저다 — 값을 안 내려는 판정이
     * 「같은 오염을 다른 날짜로 내기」가 된다.
     *
     * <p><b>MUST: {@code share_basis_multiple}을 기업행위 배수보다 먼저 곱한다.</b> 앞은 그 판이
     * 실은 수를 시세 기저로 옮기는 것이고 뒤는 공시일에서 {@code asOf}까지 옮기는 것이라
     * 구간이 겹치지 않는다(실측 2026-09-23: 겹치는 종목 0).
     *
     * <p>빠진 종목은 {@code UsMarketCapUniverseAdapter}가 {@code excluded.noShares}로 세어
     * 후보에서 뺀다 — 시총을 못 내는 것과 같은 자리이고, 그래서 순위에 안 들어간다.
     */
    private static final String SQL = """
            WITH latest AS (
                SELECT DISTINCT ON (f.stock_id)
                       f.stock_id, f.shares_outstanding, f.period_end, f.published_at,
                       f.share_basis_multiple, f.share_basis_status
                FROM financial_statement f
                JOIN stock s ON s.id = f.stock_id
                WHERE s.market_code = ?
                  AND s.is_active
                  AND f.published_at <= ?
                  AND f.shares_outstanding IS NOT NULL
                  AND f.shares_outstanding > 0
                  AND f.period_end >= ?
                ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC
            )
            SELECT s.ticker,
                   latest.shares_outstanding
                       * coalesce(latest.share_basis_multiple, 1) * %s AS shares_outstanding,
                   s.sic_code, latest.period_end, latest.published_at,
                   %s AS currency_mismatched, s.name
            FROM latest
            JOIN stock s ON s.id = latest.stock_id
            WHERE latest.share_basis_status IS DISTINCT FROM 'UNRESOLVED'
            ORDER BY s.ticker
            """.formatted(SHIFT_TO_ASOF, CURRENCY_MISMATCHED);

    /**
     * 관문이 켜진 미국의 판 후보 — {@link #SQL}의 안쪽과 같은 범위·같은 정렬이되 주식수로 미리 거르지 않는다. 클래스 축 발행사는
     * 지금 경로 주식수가 비어 있고(축 없는 표지가 없다) 값은 판정에서 오기 때문이다. 고르기는 {@link ShareClassLine}을 입힌 뒤
     * 자바에서 한다(지표 판독기와 같은 규칙 한 자리).
     */
    private static final String CLASS_ROWS = """
            SELECT f.stock_id, s.ticker, s.sic_code, s.name, f.report_code, f.shares_outstanding, f.period_end,
                   f.published_at, f.share_basis_multiple, f.share_basis_status
            FROM financial_statement f
            JOIN stock s ON s.id = f.stock_id
            WHERE s.market_code = ?
              AND s.is_active
              AND f.published_at <= ?
              AND f.period_end >= ?
            ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC
            """;

    /** 통화 판정 — {@link #CURRENCY_MISMATCHED}를 글자 그대로 쓴다(별칭 {@code latest}에 종목을 앉힌다). */
    private static final String CLASS_MISMATCHED = """
            SELECT latest.stock_id FROM (SELECT s.id AS stock_id FROM stock s WHERE s.market_code = ? AND s.is_active) latest
            WHERE %s
            """.formatted(CURRENCY_MISMATCHED);

    private final JdbcTemplate jdbcTemplate;
    private final ShareClassStore shareClasses;
    /** 미국 관문 설정 — 켜졌으면 미국 주식수를 클래스 축 판정으로 읽는다(꺼졌으면 판정 표를 부르지 않는다). */
    private final boolean usGate;

    /** 관문이 꺼진 판독기(시험 · 재생 도구). */
    public JdbcStoredShareCounts(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, false);
    }

    @Autowired
    public JdbcStoredShareCounts(JdbcTemplate jdbcTemplate, ShareClassStore shareClasses,
                                 @Value("${stockscreener.gate.us}") boolean usGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.shareClasses = shareClasses;
        this.usGate = usGate;
    }

    private boolean classAxis(String marketCode) {
        return usGate && UsMarket.CODE.equals(marketCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf) {
        if (classAxis(marketCode)) {
            return classAxisCounts(marketCode, oldestPeriodEnd, asOf);
        }
        return jdbcTemplate.query(SQL,
                (rs, rowNum) -> new ShareCount(
                        rs.getString("ticker"),
                        rs.getBigDecimal("shares_outstanding"),
                        rs.getString("sic_code"),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getObject("published_at", LocalDate.class),
                        rs.getBoolean("currency_mismatched"),
                        rs.getString("name")),
                marketCode, asOf, oldestPeriodEnd, asOf);
    }

    /**
     * 관문이 켜진 미국: 종목마다 기(期) 순서로 판을 내려가며 클래스 축 판정을 입혀 첫 값을 고른다 — 숨긴 판을 만나면 멈추고
     * 아무것도 안 낸다(앞 판으로 안 내려간다 · §10b-3). 지금 경로 판이 주식수를 안 실었으면 앞 판으로 내려간다(꺼진 질의가
     * 주식수 있는 판만 고르는 것과 같다). 기저 판정({@code share_basis_status})은 고른 판에서 본다 — 꺼진 질의와 같다.
     */
    private List<ShareCount> classAxisCounts(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf) {
        Map<Long, ShareClassLine> lines = shareClasses.lines(marketCode);
        Set<Long> mismatched = new HashSet<>(jdbcTemplate.queryForList(CLASS_MISMATCHED, Long.class, marketCode));
        Map<Long, List<ShareClassLine.Split>> splits = splitsOf(marketCode);
        Map<Long, ShareCount> chosen = new LinkedHashMap<>();
        Set<Long> decided = new HashSet<>();
        jdbcTemplate.query(CLASS_ROWS, rs -> {
            long stockId = rs.getLong("stock_id");
            if (decided.contains(stockId)) {
                return;
            }
            LocalDate publishedAt = rs.getObject("published_at", LocalDate.class);
            ShareClassLine line = lines.getOrDefault(stockId, ShareClassLine.none());
            ShareClassLine.Read read = line.read(rs.getString("report_code"), publishedAt, rs.getBigDecimal("shares_outstanding"));
            if (read.state() == ShareClassLine.State.HIDDEN) {
                decided.add(stockId);
                return;
            }
            if (read.shares() == null || read.shares().signum() <= 0) {
                return;
            }
            decided.add(stockId);
            if (ShareBasis.of(rs.getString("share_basis_status")) == ShareBasis.UNRESOLVED) {
                return;
            }
            BigDecimal multiple = rs.getBigDecimal("share_basis_multiple");
            BigDecimal shares = read.shares().multiply(multiple == null ? BigDecimal.ONE : multiple)
                    .multiply(ShareClassLine.factor(splits.getOrDefault(stockId, List.of()), publishedAt, asOf));
            chosen.put(stockId, new ShareCount(rs.getString("ticker"), shares, rs.getString("sic_code"),
                    rs.getObject("period_end", LocalDate.class), publishedAt, mismatched.contains(stockId),
                    rs.getString("name")));
        }, marketCode, asOf, oldestPeriodEnd);
        List<ShareCount> out = new ArrayList<>(chosen.values());
        out.sort(java.util.Comparator.comparing(ShareCount::ticker));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ClassHold> classHolds(String marketCode, LocalDate asOf) {
        if (!classAxis(marketCode)) {
            return Map.of();
        }
        Map<Long, ShareClassLine> lines = shareClasses.lines(marketCode);
        Set<Long> mismatched = new HashSet<>(jdbcTemplate.queryForList(CLASS_MISMATCHED, Long.class, marketCode));
        Map<String, ClassHold> out = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, ticker, sic_code, name FROM stock WHERE market_code = ? AND is_active ORDER BY ticker
                """, rs -> {
            ShareClassLine line = lines.get(rs.getLong("id"));
            if (line == null) {
                return;
            }
            List<MembershipHold.Verdict> history = line.history();
            MembershipHold.State state = MembershipHold.stateAt(history, asOf);
            if (state == null || state == MembershipHold.State.SHOWN) {
                return;
            }
            BigDecimal held = null;
            if (state == MembershipHold.State.HELD) {
                MembershipHold.Verdict last = MembershipHold.lastShown(history, asOf);
                if (last != null && last.lineShares() != null) {
                    held = BigDecimal.valueOf(last.lineShares()).multiply(line.factor(last.filed(), asOf));
                }
            }
            out.put(rs.getString("ticker"), new ClassHold(rs.getString("ticker"), state, held, rs.getString("sic_code"),
                    mismatched.contains(rs.getLong("id")), rs.getString("name")));
        }, marketCode);
        return out;
    }

    /** 순위 판독기의 {@link #SHIFT_TO_ASOF}와 같은 술어·경계(APPLIED · 공시일 뒤 · asOf 이하). */
    private Map<Long, List<ShareClassLine.Split>> splitsOf(String marketCode) {
        Map<Long, List<ShareClassLine.Split>> out = new HashMap<>();
        jdbcTemplate.query("""
                SELECT ca.stock_id, ca.effective_date, ca.ratio FROM corporate_action ca JOIN stock s ON s.id = ca.stock_id
                WHERE s.market_code = ? AND s.is_active AND ca.status = 'APPLIED'
                """, rs -> {
            out.computeIfAbsent(rs.getLong("stock_id"), k -> new ArrayList<>()).add(new ShareClassLine.Split(
                    rs.getObject("effective_date", LocalDate.class), rs.getBigDecimal("ratio")));
        }, marketCode);
        return out;
    }

}
