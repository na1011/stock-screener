package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 보존 기간을 넘긴 지표 값을 지운다(ADR-0011).
 *
 * <p>스크리닝은 언제나 최신 일자 한 장만 읽고 지표 시계열 차트는 v1 범위 밖이므로, 오래된
 * 지표 행은 저장 비용만 발생시키고 아무도 읽지 않는다.
 *
 * <p>MUST NOT: 원자료({@code price_daily}·{@code financial_statement})는 지우지 않는다.
 * 지표는 원자료에서 재생성되지만 그 역은 불가능하고, 외부 API가 과거 구간을 계속 제공한다는
 * 보장도 없다. 이 클래스가 {@code indicator_value}만 건드리는 것이 그 비대칭의 이행이다.
 */
@Component
public class IndicatorRetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(IndicatorRetentionPolicy.class);

    /**
     * 보존할 거래일 수(ADR-0011 · 2026-09-08에 180 → 255, ADR-0129).
     *
     * <p>이 값이 계산의 워밍업과 무관한 이유는, 계산의 입력이 {@code price_daily}이지
     * {@code indicator_value}가 아니기 때문이다. 지표를 지워도 다시 계산할 수 있다.
     * 정하는 것은 <b>과거 기준일 조회가 어디까지 열리는가</b>이고, 255는 두 시장 다
     * 1년을 넘기는 값이다(ADR-0129).
     *
     * <p>IMPORTANT — <b>읽기 창이 이 값에 얹혀 있다</b>({@code IndicatorRegistry.readWindowBars()}
     * = 워밍업 최댓값 + 이 값, ADR-0079). 줄이면 보존만 줄어드는 것이 아니라 <b>가장 긴 워밍업
     * 지표가 실행당 그만큼밖에 못 낸다.</b> 이 상수를 「보존만의 손잡이」로 읽지 않는다.
     */
    static final int RETAINED_TRADING_DAYS = 255;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorRetentionPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 앞으로 쓸 지표의 보존 경계. 이 날짜 <b>이하</b>는 애초에 쓰지 않는다.
     *
     * <p>{@link #purge()}가 이미 쌓인 것을 지운다면 이쪽은 쌓이지 않게 한다. 둘 다 필요한
     * 이유는 러너가 <b>읽기 창 전체</b>에 대해 값을 내기 때문이다 — 창은
     * {@code IndicatorRegistry.readWindowBars()}가 정하며(2026-09-08 기준 1,516봉), 이 경계가
     * 없으면 매 실행이 <b>창 − 255</b>거래일치를 보존 밖에 쓰고 다음 정리가 그것을 지운다.
     * <b>그 「창 − 255」가 곧 워밍업 최댓값과 같은 수인 것은 우연이 아니라 산식이다</b>(ADR-0079).
     * 값은 언제나 옳고 화면도 정상이라 드러나는 것은 몇 달 뒤 인덱스 크기뿐이다.
     *
     * <p>경계를 {@code price_daily}에서 세는 것이 {@link #purge()}와 다른 점이다. 지표 날짜는
     * 시세 날짜의 부분집합이므로 이쪽 경계가 언제나 같거나 더 최근이고, 따라서 <b>이 필터를
     * 통과한 값은 정리 대상이 되지 않는다.</b> 반대로 {@code indicator_value}를 기준으로 삼으면
     * 지표가 아직 보존 기간만큼 쌓이지 않은 동안 경계가 비어, 초기 적재가 전 구간을 쓴다.
     *
     * <p><b>MUST: 시장마다 따로 센다.</b> 전 시장 합계로 세면 국내가 휴장이고 미국이 개장한
     * 날이 전역 「거래일」이 되어 경계가 그만큼 뒤로 밀리고, {@link #purge()}는
     * {@code indicator_value} 기준이라 <b>그 어긋남이 자연 소멸하지 않는다</b> —
     * ADR-0045의 premise가 원인을 틀리게 가리키며 상시 발화한다.
     *
     * @param marketCode 셀 시장. 이 시장의 봉만 거래일로 센다
     * @return 경계 거래일. 그 시장의 시세가 보존 기간보다 적게 쌓여 있으면 비어 있고,
     *         그때는 거를 것이 없다
     */
    public Optional<LocalDate> writeCutoff(String marketCode) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT p.trade_date FROM price_daily p
                JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = ?
                ORDER BY p.trade_date DESC
                OFFSET ?
                LIMIT 1
                """, LocalDate.class, marketCode, RETAINED_TRADING_DAYS)
                .stream()
                .findFirst();
    }

    /**
     * 시장마다의 보존 경계. 값이 없는 시장(봉이 보존 기간보다 적게 쌓인 시장)은 키 자체가 없다.
     *
     * <p>배치가 <b>루프 앞에서 한 번</b> 만들어 종목마다 자기 시장의 경계를 꺼내 쓰라고 있는
     * 것이다. 종목마다 물으면 같은 답을 수천 번 받는다.
     */
    public Map<String, Optional<LocalDate>> writeCutoffs() {
        Map<String, Optional<LocalDate>> cutoffs = new LinkedHashMap<>();
        for (String marketCode : marketCodes()) {
            cutoffs.put(marketCode, writeCutoff(marketCode));
        }
        return cutoffs;
    }

    /**
     * 시장 코드 전체. {@code market} 표가 정본이다.
     *
     * <p>{@code CollectedMarkets}를 쓰지 않는 이유는 <b>패키지 방향</b>이다 — 지표 계층이
     * 수집 계층을 알면 안 된다. 수집 대상이 아닌 시장이 섞여도 해롭지 않다: 행이 없으면
     * 경계도 없고 지울 것도 없다.
     */
    private List<String> marketCodes() {
        return jdbcTemplate.queryForList("SELECT code FROM market ORDER BY code", String.class);
    }

    /**
     * 보존 경계보다 오래된 지표 값을 지운다.
     *
     * <p>경계를 달력이 아니라 <b>실제 거래일</b>로 센다. 달력으로 255일 전을 잡으면 휴장일이
     * 포함되어 실제 보존되는 거래일이 그보다 적어지고, 그 차이는 연휴가 긴 해에 커진다.
     *
     * @return 지운 행 수
     */
    public int purge() {
        int total = 0;
        for (String marketCode : marketCodes()) {
            total += purgeMarket(marketCode);
        }
        return total;
    }

    /**
     * 한 시장의 보존 경계 밖 지표를 지운다.
     *
     * <p><b>MUST: 경계도 삭제도 시장으로 좁힌다.</b> 전역 경계로 지우면 달력이 다른 두 시장
     * 중 한쪽이 <b>보존 기간보다 적게 남거나 더 많이 남는다</b> — 어느 쪽도 에러를 내지 않고,
     * 적게 남는 쪽은 읽기 창이 그만큼 짧아져 값이 이유 없이 사라진 것처럼 보인다.
     */
    private int purgeMarket(String marketCode) {
        List<LocalDate> boundary = jdbcTemplate.queryForList("""
                SELECT DISTINCT iv.trade_date FROM indicator_value iv
                JOIN stock s ON s.id = iv.stock_id
                WHERE s.market_code = ?
                ORDER BY iv.trade_date DESC
                OFFSET ?
                LIMIT 1
                """, LocalDate.class, marketCode, RETAINED_TRADING_DAYS);

        if (boundary.isEmpty()) {
            // 보존 기간보다 적게 쌓여 있다. 지울 것이 없다.
            return 0;
        }

        LocalDate cutoff = boundary.get(0);
        int deleted = jdbcTemplate.update("""
                DELETE FROM indicator_value iv
                USING stock s
                WHERE s.id = iv.stock_id AND s.market_code = ? AND iv.trade_date <= ?
                """, marketCode, cutoff);
        log.info("보존 기간을 넘긴 지표를 정리했다. 시장={} 기준일={} 삭제={}행 (보존 {}거래일)",
                marketCode, cutoff, deleted, RETAINED_TRADING_DAYS);
        return deleted;
    }
}
