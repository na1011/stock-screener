package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.CollectedMarkets;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 티커를 {@code stock.id}로 해석하고, 없으면 종목 행을 만든다.
 *
 * <p>유니버스에 새로 편입된 종목은 티커만 알려진 상태다. {@code universe_membership}이
 * {@code stock_id}를 FK로 참조하므로 리밸런싱보다 먼저 이 해석이 일어나야 한다.
 *
 * <p>{@link UniverseReconciler}와 분리한 이유는 책임이 다르기 때문이다. 이쪽은 종목 마스터를
 * 다루고 저쪽은 편입 구간을 다룬다. 합치면 마스터 소스의 실패가 유니버스 갱신을 되돌린다.
 */
@Component
public class StockRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StockRegistrar.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSourceRegistry registry;

    public StockRegistrar(JdbcTemplate jdbcTemplate, DataSourceRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    /**
     * 티커 목록을 stock_id 목록으로 해석한다. 없는 종목은 만든다.
     *
     * <p>마스터 소스가 없거나 티커를 모르면 <b>최소 정보로 행을 만든다.</b> 이름을 티커로
     * 대신하고 로그를 남긴다. 종목을 건너뛰지 않는 이유는, 그러면 그 종목이 유니버스에서
     * 조용히 빠져 스크리닝 결과에서 사라지기 때문이다 — 이름이 없는 것보다 나쁘다.
     */
    @Transactional
    public List<Long> resolveOrCreate(String marketCode, List<String> tickers) {
        return tickers.stream()
                .map(ticker -> resolveOne(marketCode, ticker))
                .toList();
    }

    private long resolveOne(String marketCode, String ticker) {
        Long existing = findId(marketCode, ticker);
        if (existing != null) {
            return existing;
        }

        SecurityDescriptor descriptor = describe(marketCode, ticker);
        // 미국 행은 생기는 순간 닻을 박는다(ADR-0180) — 그 기호가 오늘 가리키는 실체가 이 행이다. 국내는 닻이 없다.
        jdbcTemplate.update("""
                INSERT INTO stock (market_code, ticker, name, name_en, sector, listed_on,
                                   listing_exchange, is_active, created_at, updated_at,
                                   alpaca_anchor_symbol, alpaca_anchor_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, now(), now(),
                        CASE WHEN ? = 'US' THEN CAST(? AS varchar) END,
                        CASE WHEN ? = 'US' THEN (now() AT TIME ZONE 'America/New_York')::date END)
                ON CONFLICT (market_code, ticker) DO NOTHING
                """,
                marketCode, ticker, descriptor.name(), descriptor.nameEn(),
                descriptor.sector(), descriptor.listedOn(), descriptor.listingExchange(),
                marketCode, ticker, marketCode);

        Long created = findId(marketCode, ticker);
        if (created == null) {
            throw new IllegalStateException(
                    "종목을 만들지도 찾지도 못했다: " + marketCode + '/' + ticker);
        }
        log.info("신규 종목을 등록했다. {}/{} name={}", marketCode, ticker, descriptor.name());
        return created;
    }

    /**
     * 마스터 소스에서 종목 정보를 가져온다. 실패해도 예외를 던지지 않는다.
     *
     * <p>MUST NOT: 조용히 넘기지 않는다. 최소 정보로 대체하되 반드시 로그를 남긴다 —
     * 이름이 티커와 같은 종목이 쌓이면 마스터 소스가 동작하지 않는다는 신호다.
     */
    private SecurityDescriptor describe(String marketCode, String ticker) {
        try {
            Optional<SecurityDescriptor> found =
                    registry.securityMasterSource(marketCode).findByTicker(marketCode, ticker);
            if (found.isPresent()) {
                return found.get();
            }
            log.warn("마스터 소스가 종목을 모른다. {}/{} — 최소 정보로 등록한다.", marketCode, ticker);
        } catch (DataSourceException e) {
            log.warn("종목 마스터 조회에 실패했다. {}/{} kind={} — 최소 정보로 등록한다. {}",
                    marketCode, ticker, e.kind(), e.getMessage());
        }
        return new SecurityDescriptor(marketCode, ticker, ticker, null, null, null, null, null);
    }

    /**
     * {@link CollectedMarkets#ALL}을 정렬해 배열로. 정렬하는 이유는 <b>질의 문자열을 안정시켜</b>
     * 실행 계획 캐시와 로그 대조가 실행마다 흔들리지 않게 하려는 것이다.
     */
    private static Object[] collectedMarkets() {
        return CollectedMarkets.ALL.stream().sorted().toArray();
    }

    private static String collectedMarketPlaceholders() {
        return String.join(",", java.util.Collections.nCopies(CollectedMarkets.ALL.size(), "?"));
    }

    /**
     * 이미 있는 종목만 티커 → id로 해석한다. <b>행을 만들지 않는다.</b>
     *
     * <p>{@link #resolveOrCreate}와 <b>일부러 다른 계약</b>이다. 저쪽은 「없으면 만든다」라
     * 쓰기이고, 여기는 <b>쓰기 0</b>이라 되돌릴 수 없는 단계 앞에 둘 수 있다(ADR-0135의
     * 복원 대조가 그 자리다). <b>빠진 티커의 수가 곧 「우리가 데이터를 안 가진 과거 편입」의
     * 크기</b>이고, 그것을 재는 것이 이 메서드의 목적이다.
     *
     * @return 티커 → id. 없는 티커는 <b>키 자체가 없다</b> — {@code null}로 채우지 않는다
     */
    public Map<String, Long> existingIdsByTicker(String marketCode, List<String> tickers) {
        if (tickers.isEmpty()) {
            return Map.of();
        }
        // MUST: 한 질의로 묻는다. 티커마다 findId를 부르면 격자 하나에 천 번이 나가고,
        // 복원 대조가 격자 50점이라 오만 번이 된다 — 실측으로 격자점당 37초였다.
        String holes = String.join(",", java.util.Collections.nCopies(tickers.size(), "?"));
        Object[] params = new Object[tickers.size() + 1];
        params[0] = marketCode;
        for (int i = 0; i < tickers.size(); i++) {
            params[i + 1] = tickers.get(i);
        }
        Map<String, Long> found = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ticker, id FROM stock WHERE market_code = ? AND ticker IN (" + holes + ")",
                rs -> {
                    found.put(rs.getString("ticker"), rs.getLong("id"));
                }, params);
        return found;
    }

    private Long findId(String marketCode, String ticker) {
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT id FROM stock WHERE market_code = ? AND ticker = ?",
                Long.class, marketCode, ticker);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 지금 편입 중인 활성 종목.
     *
     * <p>배치가 "전 종목"을 말할 때의 정본이다. {@code DISTINCT}인 이유는 한 종목이 여러 지수에
     * 동시에 편입될 수 있기 때문이며, 중복을 두면 같은 종목을 두 번 처리해 쿼터를 낭비한다.
     *
     * <p>MUST NOT: 이 질의를 잡마다 다시 적지 않는다. 실제로 사본이 둘이었고, 하나만 고치면
     * 두 잡이 서로 다른 "전 종목"을 처리하는데 <b>어느 쪽도 에러를 내지 않는다.</b>
     *
     * <p><b>MUST: {@code universe.is_active}를 조인한다</b>(ADR-0087). 편입 행은 유니버스의
     * 활성 여부와 무관하게 남으므로, 조인하지 않으면 <b>비활성 유니버스에만 편입된 종목이
     * 영구히 "전 종목"에 남는다</b> — 유니버스를 다시 내려도 빠지지 않고, 되돌리려 해도
     * {@code UniverseReconciler}의 대량 제외 가드가 정확히 그때 거부한다.
     *
     * <p>그 상태가 무엇을 하는지는 소스가 없는 시장을 켜 보면 드러난다. 수집은 종목마다
     * {@code NOT_FOUND}로 실패해 <b>부분 실패 알림이 상시화되고 진짜 장애가 묻히며</b>,
     * {@code CorporateActionJob}처럼 <b>루프 앞에서 시장별 소스를 먼저 해석하는 잡</b>은
     * 다른 시장까지 통째로 멈춘다.
     *
     * <p>{@code PercentileCalculator}와 {@code StockReader.selectableUniverses}는 처음부터
     * 이 조인을 갖고 있었다. 여기 없던 것이 비대칭이었다.
     *
     * <p><b>MUST: {@link CollectedMarkets}로도 좁힌다.</b> 유니버스에 편입하는 것과 데이터를
     * 모으는 것은 다르다 — 미국은 편입까지만 열려 있고 수집 어댑터가 하나도 없어서, 대상에
     * 넣으면 종목마다 실패한다. 활성 유니버스 조인만으로는 이것을 막지 못한다: <b>적재하려면
     * 그 유니버스를 켜야 하고, 켜는 순간 편입 종목이 전부 여기 들어온다.</b>
     */
    public List<Long> activeUniverseMemberIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active
                  AND %s
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(eraCovers("s", TODAY_US), collectedMarketPlaceholders()), Long.class, collectedMarkets());
    }

    /**
     * 업종이 아직 비어 있는 편입 종목.
     *
     * <p>{@link #activeUniverseMemberIds()} 옆에 두는 이유는 그 javadoc이 적은 것과 같다 —
     * "편입 중인 활성 종목"의 정의가 잡마다 갈리면 <b>어느 쪽도 에러를 내지 않는다.</b>
     * 여기는 그 정의에 결손 조건 하나만 더한 것이며, 정의 자체는 위와 같은 자리에서 온다.
     *
     * <p><b>결손은 두 컬럼을 함께 본다</b>(ADR-0092). 업종 체계가 시장마다 다르므로
     * {@code industry_code}만 보면 SIC를 채운 미국 종목이 <b>영원히 결손</b>으로 남는다.
     * 지금은 {@code US_LISTED}가 비활성이라 그 종목이 이 질의에 한 건도 안 들어오지만,
     * <b>켜면 매 실행 2,238종목이 대상이 되고 그중 423종목에 실제 콜이 나간다</b>
     * (2026-08-30 실측 — 미국 8,504 중 6,266이 이미 {@code sic_code}를 받았고, 남은 2,238 중
     * 1,815는 CIK에 안 붙어 HTTP 콜이 0이다). <b>그 1,815는 영원히 결손으로 남아 매 실행 다시
     * 대상이 된다</b> — 실패가 아니라 그 종목에 SEC 서류가 없는 것이다.
     * {@code AdminJobLauncher}의 콜 수 주석과 같은 조건에 걸린 자리다.
     */
    public List<Long> activeUniverseMemberIdsWithoutIndustry() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active
                  AND %s
                  AND s.industry_code IS NULL AND s.sic_code IS NULL
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(eraCovers("s", TODAY_US), collectedMarketPlaceholders()), Long.class, collectedMarkets());
    }

    /**
     * 이력이 아직 없는 편입 종목. 초기 적재의 기본 대상이다.
     *
     * <p>{@link #activeUniverseMemberIdsWithoutIndustry()} 옆에 두는 이유가 같다 — 이것도
     * <b>{@link #activeUniverseMemberIds()}의 정의에 결손 조건 하나만 더한 것</b>이며, 정의
     * 자체는 위와 같은 자리에서 온다. {@code InitialLoadJob}이 이 질의를 자기 안에 들고 있었고
     * 거기에는 <b>두 조건이 둘 다 없었다</b> — 실 DB에서 그 사본의 대상이 미국 8,504종목이고
     * 국내가 0건이었다(2026-08-29 실측). 어댑터가 없는 시장이라 종목마다 {@code NOT_FOUND}로
     * 실패하는데, 플래그 없이 {@code --job=initial-load}를 한 번 치는 것만으로 그렇게 된다.
     *
     * <p>{@code NOT EXISTS}로 판정하는 이유는 "한 봉이라도 있으면 적재된 것으로 본다"가
     * 이 시점에 충분하기 때문이다. 부분 적재의 정밀한 판정은 시세 이력의 실제 시작일을
     * 봐야 하는데, 상장일이 적재 기간 이내인 종목과 구분할 수 없어 오히려 틀린다.
     *
     * <p>IMPORTANT — 그 대가로 <b>기간을 늘려도 대상이 0건이 된다.</b> 2년치를 받아둔 상태에서
     * 7년으로 넓히면 봉이 이미 있어 전 종목이 걸러지고, 잡은 "적재할 종목이 없다"며 성공으로
     * 끝난다. 기간을 늘렸는데 아무 일도 일어나지 않고 성공으로 보이는 것이 그 결함의 성질이며,
     * {@code InitialLoadJob}의 {@code reloadExisting}이 존재하는 이유가 그것이다.
     *
     * <p>두 번째 갈래(재무 실패가 미해결인데 재무가 한 행도 없는 종목)는 시세가 아니라
     * <b>재무</b> 결손을 다시 시도하려는 것이다. 호출부가 그 종목의 시세를 건너뛴다.
     *
     * <h2>IMPORTANT — 이 술어는 일일 갱신이 지운다. 감시에 쓰지 마라</h2>
     *
     * <p>리밸런싱이 새 종목을 넣으면 여기 걸리지만, <b>다음 일일 갱신이 15일 창으로 봉을 열 개쯤
     * 남기는 순간 {@code NOT EXISTS}가 영원히 거짓이 된다.</b> 그러면 그 종목은 시세 이력도
     * 재무도 없는 채 유니버스에 남아 <b>지표가 대부분 판정불가</b>가 되고, 프리셋에서 조용히
     * 빠진다 — 배치는 전부 SUCCESS다.
     *
     * <p>창의 폭은 <b>리밸런싱(토 20:00)부터 다음 일일 갱신(국내 월 13:30 · 미국 월 21:00)까지 약 41~49시간</b>이다.
     * 2026-08-30에 그 창 안에서 3종목을 잡았다(425420·033640·361610 — 봉 0·재무 0인 채
     * 이틀을 지났고, {@code company-profile}만 뒤따라 돌아 업종은 차 있었다).
     *
     * <p><b>감시에 쓸 술어는 따로 있다</b> —
     * {@code min(price_daily.trade_date) IS NULL OR min(price_daily.trade_date) > effective_from}
     * ("이력이 편입 이전으로 뻗어 있지 않다"). 일일 갱신이 봉을 남겨도 첫 봉이 편입일 뒤에
     * 있으므로 <b>신호가 지워지지 않는다.</b> 편입 중 전 종목에 대해 재보면 0건이다 —
     * 최근 상장한 일곱(봉 8~261개)도 첫 봉이 편입일보다 앞선다.
     *
     * <p><b>그 술어의 오탐 하나를 알고 쓴다</b>: 상장하자마자 편입된 종목은 첫 봉이 영원히
     * 편입일 뒤라 <b>매 실행 다시 걸린다.</b> 끄려면 "돌렸는데 소스가 더 안 줬다"를 기록해야
     * 하는데, <b>상장일 소스가 없다</b> — {@code stock.listed_on}이 양 시장 전량 NULL이고,
     * DART 기업개황의 {@code est_dt}는 설립일이며(삼성전자 1969 vs 상장 1975), KRX 상장종목
     * 서비스도 주지 않는다(둘 다 {@code docs/api-spec/}에 기록돼 있다). <b>그래서 그것을 끄는
     * 것은 새 저장을 두는 결정이다.</b>
     *
     * <p><b>MUST NOT: 그 감시를 일일 갱신의 수집 분기로 만들지 않는다</b>
     * ({@code data-pipeline.md} — "일일 갱신 배치에 초기 적재 분기를 섞지 않는다").
     * <b>알림은 초기 적재 분기가 아니므로</b> {@code DailyUpdateJob}의 기존 알림 둘 옆에 셋째로
     * 두는 것은 그 금지에 걸리지 않는다. 걸리는 것은 <b>거기서 적재를 부르는 것</b>이다.
     */
    public List<Long> activeUniverseMemberIdsNeedingHistory() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active
                  AND %s
                  AND s.market_code IN (%s)
                  AND (
                    NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = m.stock_id)
                    OR (
                      EXISTS (SELECT 1 FROM batch_execution_failure f
                               WHERE f.stock_id = m.stock_id
                                 AND f.failed_phase = 'FINANCIAL' AND NOT f.resolved)
                      AND NOT EXISTS (SELECT 1 FROM financial_statement fs
                                       WHERE fs.stock_id = m.stock_id)
                    )
                  )
                ORDER BY m.stock_id
                """.formatted(eraCovers("s", TODAY_US), collectedMarketPlaceholders()), Long.class, collectedMarkets());
    }

    /**
     * 티커로 편입 종목을 골라낸다. <b>없는 종목을 만들지 않는다.</b>
     *
     * <p>{@link #resolveOrCreate}와 갈라놓은 이유가 그것이다 — 저쪽은 유니버스 편입이 근거라
     * 모르는 티커를 최소 정보로 등록하는 것이 옳지만, <b>손으로 준 인자에 그 동작을 붙이면
     * 오타 하나가 가짜 {@code stock} 행을 만들고 마스터 소스까지 호출한다.</b>
     *
     * <p>시장 코드를 요구하지 않는다. 국내 티커는 6자리 숫자로 시장 간에 겹치지 않으며,
     * 손으로 고르는 경로에서 시장까지 적게 하면 실수만 는다.
     *
     * <p><b>「지금 편입」이 아니라 「보존 창과 겹치는 편입」이다</b>(ADR-0135). 창이 곧 화면이
     * 답할 수 있는 범위이므로, 그 안의 어느 날짜에든 편입이던 종목은 <b>수집 대상이다</b> —
     * 그러지 않으면 복원한 과거 구성원에게 <b>어떤 잡으로도 데이터를 넣을 수 없다.</b>
     * ADR-0071이 편출 6종목에 대해 <i>"세 잡 어느 것으로도 넣을 수 없다"</i>로 적은 그 자리다.
     *
     * <p><b>MUST: 열린 편입을 창 조건 밖에 둔다.</b> 지표 달력이 없는 시장에서는 창의 양 끝이
     * {@code NULL}이라 비교가 전부 거짓이 되는데, 창 조건 안에 열린 행을 넣으면 <b>그 시장의
     * 티커 조회가 통째로 0건</b>이 된다 — 이 작업이 없애려는 바로 그 「조용한 0건」이다.
     * 지금 형태는 달력이 없으면 <b>옛 동작(지금 편입)으로 정확히 되돌아간다.</b>
     *
     * <p><b>MUST NOT: 같은 이유로 {@link #activeUniverseMemberIds}를 넓히지 않는다.</b> 그쪽은
     * <b>매일 도는</b> 일일 갱신의 대상이라, 넓히면 대상이 1,350 → <b>1,709</b>가 되고
     * (2026-09-10 실측 · +27%) 그 비용을 매일 문다. 이쪽은 <b>손으로 티커를 주는 경로</b>라
     * 한 번의 선택이다.
     *
     * @return 창 안에 편입이 있던 활성 종목과의 <b>교집합</b>. 호출부가 개수 차이로 오타를 판별한다.
     */
    public List<Long> activeUniverseMemberIdsByTicker(List<String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(tickers.size(), "?"));
        Object[] args = java.util.stream.Stream
                .concat(tickers.stream(), java.util.Arrays.stream(collectedMarkets()))
                .toArray();
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.is_active AND s.ticker IN (%s)
                  -- 시대 무관: 보존 창 편입 — 창 안 과거 날짜를 다시 쓰는 일이라 닫힌 행의 시대 안 날짜도 대상이다
                  AND s.market_code IN (%s)
                  AND %s
                ORDER BY m.stock_id
                """.formatted(placeholders, collectedMarketPlaceholders(), IN_RETENTION_WINDOW),
                Long.class, args);
    }

    /**
     * 보존 창과 겹치는 편입 종목 <b>전부</b> — 지금 편입과, 창 안에서 편입이 끝난 과거 구성원(ADR-0135).
     *
     * <p>매 평일 지표 화해와 거버넌스 유도의 기본 모집단이다. 화면은 창 안의 어느 날짜든 답하고
     * 백분위는 그날 편입으로 매기므로, <b>과거 구성원의 값도 지금 코드·지금 원자료로 써져 있어야
     * 한다.</b> 지금 편입만 돌면 그들의 값은 마지막으로 그들을 쓴 실행의 코드에서 멈춘다 —
     * 실행 이력으로 창 전원을 쓴 마지막 실행이 2026-09-23 00:38(실행 665)이었고 그 뒤 들어온
     * 계산기 커밋 셋은 과거 편입(그날 333종목)에 안 닿았다(2026-10-04). 같은 날 순위가 두
     * 계산기의 값으로 섞인다.
     *
     * <p>MUST NOT: {@link #activeUniverseMemberIds()}를 이것으로 바꾸지 않는다. 그쪽은 일일 갱신의 「전 종목」이다
     * (그 javadoc) — 정의를 바꾸면 그것을 부르는 잡 전부의 대상이 말없이 넓어진다. 잡이 이 모집단을 고를 때는
     * 사유를 든다: 지표 화해·거버넌스 유도·재구축은 호출 0인 다시 계산이고, <b>기업행위 수집은 외부 호출을
     * 태운다</b> — 창 안에서 편출된 모체의 분사·분할이 원장에 안 들어오면 과거 기준일에 고친 모체와 안 고친
     * 모체가 섞이기 때문이다(CorporateActionJob · 탐색 브랜치 explore/spin-off/PLAN.md §G).
     *
     * <p>술어는 {@link #activeUniverseMemberIdsByTicker}와 한 조각({@link #IN_RETENTION_WINDOW})이다.
     * 둘이 갈리면 손으로 티커를 준 실행과 정기 화해가 서로 다른 「창 편입」을 돈다.
     */
    public List<Long> windowMemberIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.is_active
                  -- 시대 무관: 보존 창 편입 — 창 안 과거 날짜를 다시 쓰는 일이라 닫힌 행의 시대 안 날짜도 대상이다
                  AND s.market_code IN (%s)
                  AND %s
                ORDER BY m.stock_id
                """.formatted(collectedMarketPlaceholders(), IN_RETENTION_WINDOW),
                Long.class, collectedMarkets());
    }

    /**
     * 보존 창 편입 중 <b>닫힌</b> 행({@code effective_to}가 있다 — 복원 평면과 창 안에서 끝난 관측 편입)이 <b>자기 구간</b>에 봉이
     * 하나도 없는 종목의 티커. 복원이 처음 넣은 종목이 이 꼴이다(ADR-0135 — 「데이터가 없는 복원 구성원」 · 2026-10-16 `V`·`STZ`·`FWONA`
     * 시세 0행). 열린 편입은 {@link #membershipGaps}가 리밸런싱 꼬리에서 보므로 여기서 안 본다.
     *
     * <p>술어는 {@link #windowMemberIds}와 같은 조각({@link #IN_RETENTION_WINDOW} · 활성 종목 · 활성 유니버스)이라 여기서 낸 티커는
     * {@code initial-load --tickers}의 술어({@link #activeUniverseMemberIdsByTicker})에 그대로 든다. 비활성 종목은 그 술어 밖이라 못
     * 받으므로 여기서도 안 낸다 — 그 몫은 관문 실행기({@code ops/apply-window/05-roster.sh})의 창 결손 단언이 활성 여부와 무관하게 센다.
     */
    public List<String> windowClosedMembersWithoutBars() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT s.ticker FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.is_active
                  -- 시대 무관: 보존 창 편입 — 창 안 과거 날짜를 다시 쓰는 일이라 닫힌 행의 시대 안 날짜도 대상이다
                  AND s.market_code IN (%s)
                  AND m.effective_to IS NOT NULL
                  AND %s
                  AND NOT EXISTS (
                      SELECT 1 FROM price_daily p
                      WHERE p.stock_id = m.stock_id AND p.trade_date < m.effective_to
                        AND p.trade_date >= greatest(m.effective_from,
                            (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)))
                ORDER BY s.ticker
                """.formatted(collectedMarketPlaceholders(), IN_RETENTION_WINDOW),
                String.class, collectedMarkets());
    }

    /**
     * 보존 창 편입 중 <b>닫힌</b> 행이 있는 종목 중 거버넌스 <b>원문</b>이 한 행도 없는 것의 티커 — 복원 꼬리가 받는다(관측 평면 계획 v1.1
     * · 반증 밖 6: 복원이 처음 넣은 V · STZ · COKE … 전부 원문 0행 · {@code governance-load}의 기본 모집단은 지금 편입뿐이다).
     *
     * <p>결손의 뜻은 {@link #membershipGaps}의 {@code GOVERNANCE} 축과 같다 — 원문 0행이고 그 시장에 원문이 하나라도 있을 것
     * ({@link MembershipGap.Axis#GOVERNANCE} javadoc: 팩트로 재면 무배당 · 완결 게이트가 섞인다). 술어 조각은
     * {@link #windowClosedMembersWithoutBars}와 같아 여기서 낸 티커는 {@code governance-load --tickers}의 술어
     * ({@link #activeUniverseMemberIdsByTicker})에 그대로 든다.
     */
    public List<String> windowClosedMembersWithoutGovernance() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT s.ticker FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.is_active
                  -- 시대 무관: 보존 창 편입 — 창 안 과거 날짜를 다시 쓰는 일이라 닫힌 행의 시대 안 날짜도 대상이다
                  AND s.market_code IN (%s)
                  AND m.effective_to IS NOT NULL
                  AND %s
                  AND NOT EXISTS (SELECT 1 FROM governance_report_row r WHERE r.stock_id = s.id)
                  AND EXISTS (SELECT 1 FROM governance_report_row r2 JOIN stock s2 ON s2.id = r2.stock_id
                              WHERE s2.market_code = s.market_code)
                ORDER BY s.ticker
                """.formatted(collectedMarketPlaceholders(), IN_RETENTION_WINDOW),
                String.class, collectedMarkets());
    }

    /**
     * 원장에 <b>적용 대기</b>({@code APPROVED} — 공시가 종류·비율을 확정했지만 계수 재구축이 아직 안 돈) 행이 있는 종목 중 보존 창
     * 편입이 아닌 것(ADR-0178). 재구축의 기본 대상({@link #windowMemberIds})에 더한다 — 아니면 그 행은 영영 {@code APPLIED}가 안 되고
     * 순위의 주식수 보정({@code JdbcStoredShareCounts.SHIFT_TO_ASOF} — {@code APPLIED}만 읽는다)에 안 실린다. 그런 행을 쓰는 곳은
     * 미국 순위 후보의 분할 다리({@code CandidateSplitJob})뿐이다 — 2026-10-15 운영 원장의 {@code APPROVED}는 0행이다.
     */
    public List<Long> unappliedApprovalIdsOutsideWindow() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT ca.stock_id FROM corporate_action ca
                JOIN stock s ON s.id = ca.stock_id
                WHERE ca.status = 'APPROVED' AND s.is_active AND s.market_code IN (%s)
                  -- 시대 무관: 적용 대기 원장 — 닫힌 행의 시대 안 사건도 계수에 실려야 과거 기준일 주식수가 맞는다
                  AND NOT EXISTS (SELECT 1 FROM universe_membership m
                                  JOIN universe u ON u.code = m.universe_code AND u.is_active
                                  WHERE m.stock_id = s.id AND %s)
                ORDER BY ca.stock_id
                """.formatted(collectedMarketPlaceholders(), IN_RETENTION_WINDOW), Long.class, collectedMarkets());
    }

    /**
     * 보존 창 편입의 <b>시세 구멍 구간</b> — 지표가 읽는 범위 안에서 소스에 다시 물을 날짜들.
     *
     * <h2>왜 일일 갱신의 15일 창만으로 안 되는가</h2>
     * <p>창은 <b>지금 편입</b>에만 걸리고 15일 앞은 다시 안 묻는다. 그래서 재편입 종목의 편출 동안 봉,
     * 공개 지연(연휴) 중 편출된 종목의 마지막 편입일 봉, 소스가 실체를 바꿔 짧게 준 종목(TEVA — CUSIP
     * 전환)의 편입일 봉이 영영 안 들어온다. 소스는 그 봉을 준다(2026-10-05 탐침). 앞선 재편입은 손 전체
     * 재적재가 가렸다 — 관문 원칙(2026-10-04)으로 그것이 멈추면 리밸런싱마다 쌓인다.
     *
     * <h2>정의</h2>
     * <ul>
     *   <li><b>모집단</b>: {@link #windowMemberIds()}와 같은 술어({@link #IN_RETENTION_WINDOW}).</li>
     *   <li><b>달력</b>: 그 시장 지수의 거래일 ∪ 그 시장 지금 편입의 절반 이상이 봉을 가진 날. 지수만 빠진
     *       날도, 봉 몇 개만 먼저 들어온 날(토요일 꼬리)도 거래일을 흔들지 않는다.</li>
     *   <li><b>필요 범위</b>: [달력에서 대상일의 {@code readWindowBars - 1}번째 앞, 마지막 편입일] —
     *       러너가 대상일에서 그만큼 거꾸로 읽는다({@code IndicatorDataReader.readPriceBars}).</li>
     *   <li><b>첫 봉 앞은 그 앞에 편입일이 있을 때만 센다</b>(TEVA). 아니면 신규 상장과 못 가르고, 소스가
     *       재사용 티커로 <b>다른 증권의 봉</b>을 준다(ACT·FIG·IOT·S·TLN — 2026-10-05 탐침).</li>
     *   <li>지금 편입은 [{@code windowStart}, 대상일]을 뺀다 — 일일 갱신의 창 콜이 받는다.</li>
     * </ul>
     *
     * <h2>확정 재확인 구간({@link PriceGapRun#settle()})</h2>
     * <p>같은 필요 범위에서 봉이 <b>있고</b> 마지막으로 쓴 KST 날짜가 거래일 + 1 이하인 날들이다 — 소스가 잠정값을 주던 때
     * 한 번 쓰이고 확정값이 나온 뒤 아무도 다시 안 받은 봉이다(미국 소스는 일봉 거래량을 다음 날 뉴욕 새벽에 고친다 —
     * {@code docs/api-spec/alpaca-bars.md}). 지금 편입의 창 안 날짜는 구멍과 같이 뺀다. 2026-10-21 운영: 미국 65행 중 26행이
     * 소스와 달랐다(10-15 편출 17행이 6~17% 짧음 · 창 밖 현 편입 BROS 09-24 포함).
     * <ul>
     *   <li><b>스스로 빠진다</b> — 다시 받으면 {@code updated_at}이 오늘이 된다. MUST: 이 술어는 {@link PriceWriter}가 같은 값이어도
     *       {@code updated_at}을 찍는 것에 기댄다. 같은 값 건너뛰기를 넣으면 같은 봉을 매일 다시 묻는다
     *       ({@code PriceGapRunsTest}가 묶는다).</li>
     *   <li>창 기준([창 시작, 마지막 편입일])으로 두지 않는다 — 창 밖으로 밀린 잠정 봉(MAIN 09-24 · RGTI 10-01)을 못 받는다.</li>
     * </ul>
     *
     * <p><b>MUST: 종목 경계를 {@code MATERIALIZED}로 고정한다.</b> 풀어 두면 하한의 상관 부분질의가
     * (종목, 날짜) 행마다 돌아 60초를 넘는다(2026-10-05 사본 실측 — 고정하면 3.5초).
     *
     * @param readWindowBars {@code IndicatorRegistry.readWindowBars()}
     */
    public List<PriceGapRun> priceGapRuns(LocalDate target, LocalDate windowStart,
                                          int readWindowBars) {
        Object[] markets = collectedMarkets();
        List<Object> args = new ArrayList<>();
        args.addAll(List.of(markets));
        args.addAll(List.of(markets));
        args.add(target);
        args.addAll(List.of(markets));
        args.add(target);
        args.add(target);
        args.add(readWindowBars);
        args.add(windowStart); // holes
        args.add(windowStart); // settles
        String in = collectedMarketPlaceholders();
        return jdbcTemplate.query("""
                WITH members AS (
                    SELECT m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
                    FROM universe_membership m
                    JOIN stock s ON s.id = m.stock_id
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE s.is_active AND s.market_code IN (%s)
                      -- 시대 무관: 보존 창 편입 — 창 안 과거 날짜를 다시 쓰는 일이라 닫힌 행의 시대 안 날짜도 대상이다
                      AND %s
                ),
                current_counts AS MATERIALIZED (
                    SELECT market_code, count(DISTINCT stock_id) AS n
                    FROM members WHERE effective_to IS NULL GROUP BY market_code
                ),
                cal AS MATERIALIZED (
                    SELECT market_code, trade_date,
                           row_number() OVER (PARTITION BY market_code ORDER BY trade_date) AS seq
                    FROM (SELECT index_code AS market_code, trade_date FROM market_index_daily
                          WHERE index_code IN (%s) AND trade_date <= ?
                          UNION
                          SELECT s.market_code, p.trade_date
                          FROM price_daily p
                          JOIN stock s ON s.id = p.stock_id
                          JOIN current_counts cm ON cm.market_code = s.market_code
                          WHERE s.market_code IN (%s) AND p.trade_date <= ?
                          GROUP BY s.market_code, p.trade_date
                          HAVING count(*) * 2 >= max(cm.n)) d
                ),
                anchor AS (
                    SELECT market_code, max(seq) AS t_seq FROM cal GROUP BY market_code
                ),
                spans AS MATERIALIZED (
                    SELECT mb.stock_id, mb.market_code, mb.ticker,
                           bool_or(mb.effective_to IS NULL) AS current_member,
                           min(greatest(mb.effective_from, w.lo)) AS member_from,
                           max(coalesce(mb.effective_to, CAST(? AS date) + 1)) AS member_until
                    FROM members mb
                    JOIN (SELECT market_code, min(trade_date) AS lo
                          FROM indicator_trade_date GROUP BY market_code) w
                      ON w.market_code = mb.market_code
                    GROUP BY mb.stock_id, mb.market_code, mb.ticker
                ),
                ranges AS MATERIALIZED (
                    SELECT b.stock_id, b.market_code, b.ticker, b.current_member,
                           CASE WHEN b.first_member_day < b.first_bar THEN b.read_floor
                                ELSE greatest(b.first_bar, b.read_floor) END AS lower_,
                           b.member_until - 1 AS upper_
                    FROM (SELECT sp.*,
                                 (SELECT min(p.trade_date) FROM price_daily p
                                   WHERE p.stock_id = sp.stock_id) AS first_bar,
                                 (SELECT min(c.trade_date) FROM cal c
                                   WHERE c.market_code = sp.market_code
                                     AND c.trade_date >= sp.member_from) AS first_member_day,
                                 (SELECT c.trade_date FROM cal c
                                   WHERE c.market_code = sp.market_code
                                     AND c.seq = greatest(1, a.t_seq - ? + 1)) AS read_floor
                          FROM spans sp JOIN anchor a ON a.market_code = sp.market_code) b
                    WHERE b.first_bar IS NOT NULL AND b.first_member_day IS NOT NULL
                ),
                holes AS (
                    SELECT r.stock_id, r.market_code, r.ticker, r.current_member, c.trade_date, c.seq
                    FROM ranges r
                    JOIN cal c ON c.market_code = r.market_code
                              AND c.trade_date BETWEEN r.lower_ AND r.upper_
                    WHERE NOT (r.current_member AND c.trade_date >= ?)
                      AND NOT EXISTS (SELECT 1 FROM price_daily p
                                      WHERE p.stock_id = r.stock_id AND p.trade_date = c.trade_date)
                ),
                settles AS (
                    SELECT r.stock_id, r.market_code, r.ticker, r.current_member, c.trade_date, c.seq
                    FROM ranges r
                    JOIN cal c ON c.market_code = r.market_code
                              AND c.trade_date BETWEEN r.lower_ AND r.upper_
                    JOIN price_daily p ON p.stock_id = r.stock_id AND p.trade_date = c.trade_date
                    WHERE NOT (r.current_member AND c.trade_date >= ?)
                      -- 크론 시각대(KST)로 센 「거래일 다음 날」 — 첫 수집일이다(국내 13:30 · 미국 21:00 — 미국은 그때 이미 확정값이라 한 번 더 받는 것은 같은 값이다)
                      AND CAST(p.updated_at AT TIME ZONE 'Asia/Seoul' AS date) <= c.trade_date + 1
                ),
                runs AS (
                    SELECT holes.*, false AS settle FROM holes
                    UNION ALL
                    SELECT settles.*, true AS settle FROM settles
                )
                SELECT stock_id, market_code, ticker, current_member, settle,
                       min(trade_date) AS gap_from, max(trade_date) AS gap_to, count(*) AS days
                FROM (SELECT runs.*, seq - row_number() OVER (PARTITION BY stock_id, settle ORDER BY seq) AS grp
                      FROM runs) h
                GROUP BY stock_id, market_code, ticker, current_member, settle, grp
                ORDER BY stock_id, gap_from, settle
                """.formatted(in, IN_RETENTION_WINDOW, in, in),
                (rs, i) -> new PriceGapRun(rs.getLong("stock_id"), rs.getString("market_code"),
                        rs.getString("ticker"), rs.getBoolean("current_member"), rs.getBoolean("settle"),
                        rs.getObject("gap_from", LocalDate.class),
                        rs.getObject("gap_to", LocalDate.class), rs.getInt("days")),
                args.toArray());
    }

    /**
     * 「보존 창과 겹치는 편입」의 술어. {@code m}이 {@code universe_membership}, {@code s}가
     * {@code stock}이다.
     *
     * <p><b>MUST: 열린 편입을 창 조건 밖에 둔다</b> — {@link #activeUniverseMemberIdsByTicker}
     * javadoc의 「조용한 0건」이 그 이유다. 지표 달력이 없는 시장에서는 창의 양 끝이 {@code NULL}이라
     * 비교가 전부 거짓이 되고, 그때 이 술어는 지금 편입으로 정확히 되돌아간다.
     */
    private static final String IN_RETENTION_WINDOW = """
            (m.effective_to IS NULL
             OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d
                                       WHERE d.market_code = s.market_code)
                 AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d
                                        WHERE d.market_code = s.market_code)))""";

    /**
     * <b>유니버스 하나</b>를 골라 그 편입 종목을 낸다. <b>활성 여부를 보지 않는다.</b>
     *
     * <p>MUST NOT: 이 경로를 스케줄러·{@code AdminJobLauncher}에 걸지 않는다. 손으로 고른
     * 유니버스 하나를 적재하는 수단이며, 자동 실행은 활성 유니버스와 이름 붙은 미국 순위 후보
     * ({@link #usRankingCandidateIdsByTicker} · ADR-0178)만 돈다 — 그래야 "활성"이 무엇을 뜻하는지가 한 가지로 남는다. {@code UniverseRebalanceJob}의 {@code oneUniverse}가
     * 같은 이유로 같은 모양이다.
     *
     * <p>비활성인 채로 적재해야 하는 상태가 실재하기 때문에 있다 — 미국은 시세가 붙어도
     * 재무가 없어 {@code US_LISTED}를 켤 수 없는데(켜면 지표 하나 없는 8,504종목이 프리셋
     * 결과에 실린다), 켜지 않으면 위 질의 셋이 그 종목을 하나도 내지 않는다.
     *
     * <p><b>IMPORTANT — 이 경로에서는 {@link CollectedMarkets}가 유일한 게이트다.</b>
     * ADR-0087이 두 겹으로 만든 이유의 반대편이므로, 그 목록에 없는 시장의 유니버스를 주면
     * 결과가 비고 호출부가 예외로 끝낸다. <b>조용한 0건이 되지 않는 것</b>이 두 번째 겹을
     * 포기하는 대가다.
     *
     * @param tickers 비면 그 유니버스 전체. 값이 있으면 그 종목과의 교집합이다.
     */
    public List<Long> oneUniverseMemberIds(String universeCode, List<String> tickers) {
        String tickerFilter = tickers.isEmpty() ? ""
                : "AND s.ticker IN (%s) ".formatted(
                        String.join(",", java.util.Collections.nCopies(tickers.size(), "?")));
        Object[] args = java.util.stream.Stream.of(
                        java.util.stream.Stream.of((Object) universeCode),
                        tickers.stream().map(t -> (Object) t),
                        java.util.Arrays.stream(collectedMarkets()))
                .flatMap(java.util.function.Function.identity())
                .toArray();
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                  AND %s
                  AND m.universe_code = ?
                  %s
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(eraCovers("s", TODAY_US), tickerFilter, collectedMarketPlaceholders()), Long.class, args);
    }

    /**
     * 지금 활성 편입인가 — {@link #activeUniverseMemberIds}와 같은 술어(열린 편입 · 활성 유니버스)를 {@code stockIdColumn}에 건다.
     * 종목의 {@code is_active}·시장은 부르는 쪽이 건다. 편입 소비와 후보 소비가 이 한 조각으로 갈려야 한 종목이 두 다리에 다 들거나
     * 어디에도 안 드는 일이 없다.
     */
    public static String activeMembershipExists(String stockIdColumn) {
        return """
                EXISTS (SELECT 1 FROM universe_membership am JOIN universe au ON au.code = am.universe_code AND au.is_active
                        WHERE am.stock_id = %s AND am.effective_to IS NULL)""".formatted(stockIdColumn);
    }

    /** 미국 동부의 오늘 — 「오늘을 뜻하는 선택」의 시대 술어에 쓰는 SQL 식(ADR-0180 · 미국 행의 시대는 미국 동부 날짜다). */
    public static final String TODAY_US = "(now() AT TIME ZONE 'America/New_York')::date";

    /**
     * 그 날짜에 행의 시대가 열려 있는가(ADR-0180) — {@code era_from} 포함 · {@code era_to} 제외. 시대 술어는 <b>그 선택이 뜻하는
     * 날짜</b>로 건다: 오늘의 선택은 {@link #TODAY_US}, 기준일 판독기는 그 기준일({@code ?}), 공시 소비는 그 공시일 열. 「오늘」 하나로
     * 기준일 판독기까지 거르면 과거 기준일에 열린 시대였던 행이 그날 순위에서 빠진다(계획 {@code US-PAST-LISTING-PLAN-1017.md} §1).
     * 국내 행은 시대 열이 비어 늘 참이다.
     *
     * @param date SQL 식 — {@code ?}이면 바인드 자리가 <b>둘</b>이다(같은 날짜를 두 번)
     */
    public static String eraCovers(String alias, String date) {
        return "(%1$s.era_from IS NULL OR %1$s.era_from <= %2$s) AND (%1$s.era_to IS NULL OR %1$s.era_to > %2$s)"
                .formatted(alias, date);
    }

    /**
     * 미국 <b>순위 후보</b>를 티커로 고른다 — 미국 · 활성 종목 · 수집 시장 · <b>지금 활성 편입이 없음</b>(ADR-0178).
     *
     * <p>순위 어댑터({@code UsMarketCapUniverseAdapter})는 편입과 무관하게 미국 활성 종목의 저장 주식수로 줄 세운다
     * ({@code JdbcStoredShareCounts}) — 그 재료를 새로 두는 정기 경로(후보 소비·새 상장 인입)가 이 술어로 대상을 푼다. 비활성
     * 유니버스 갈래({@link #oneUniverseMemberIds})와 다르다: 그것은 손으로 고른 유니버스 하나를 채우는 수단이고 여전히 스케줄러에
     * 안 건다. 「활성 = 스크리닝 모집단」은 그대로이고, 이것은 그 밖의 둘째 모집단에 붙인 이름이다.
     *
     * <p>편입 종목은 고르지 않는다 — 편입 몫은 편입 소비가 먹인다. 소비와 이 해석 사이에 편입이 생긴 종목은 여기서 빠지고 다음
     * 편입 소비가 먹인다.
     *
     * @return 위 술어와 티커의 교집합. 호출부가 개수 차이로 빠진 것을 본다.
     */
    /**
     * 미국 <b>순위 후보 전원</b>의 티커 — {@link #usRankingCandidateIdsByTicker}와 같은 술어다(ADR-0178). 후보 모드 손 FR이 티커 없이
     * 불렸을 때 대상을 푼다. 티커 순이다.
     */
    public List<String> usRankingCandidateTickers() {
        if (!CollectedMarkets.ALL.contains("US")) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT s.ticker FROM stock s
                WHERE s.market_code = 'US' AND s.is_active
                  AND NOT %s
                  AND %s
                ORDER BY s.ticker
                """.formatted(activeMembershipExists("s.id"), eraCovers("s", TODAY_US)), String.class);
    }

    /**
     * 관문 손 재적재의 미국 모집단 — 활성 편입이 없고 <b>시대가 보존 창과 겹치는</b> 행(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U6). 오늘 후보
     * ({@link #usRankingCandidateTickers})보다 넓다 — 끝 판정 · 접기가 닫은 과거 구성원(QRVO · WBD꼴)과 들인 폐지 실체 행도 그 시대 안 날짜의
     * 과거 명부 후보라 재무 · 클래스 축 재료가 필요하다. 티커 순이다.
     *
     * @param windowStart 보존 창의 첫날 — 그 전에 시대가 끝난 행은 어느 격자일에도 후보가 아니다
     */
    public List<String> usRefillWindowTickers(LocalDate windowStart) {
        if (!CollectedMarkets.ALL.contains("US")) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT s.ticker FROM stock s
                WHERE s.market_code = 'US' AND s.is_active
                  AND NOT %s
                  -- 시대 무관: 보존 창과의 겹침이 술어다 — 창 안 어느 날이든 시대 안이면 그날의 재무가 필요하다
                  AND (s.era_to IS NULL OR s.era_to > ?) AND (s.era_from IS NULL OR s.era_from <= %s)
                ORDER BY s.ticker
                """.formatted(activeMembershipExists("s.id"), TODAY_US), String.class, windowStart);
    }

    /** {@link #usRefillWindowTickers}와 같은 술어로 티커를 푼다. */
    public List<Long> usRefillWindowIdsByTicker(List<String> tickers, LocalDate windowStart) {
        if (tickers.isEmpty() || !CollectedMarkets.ALL.contains("US")) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(tickers.size(), "?"));
        Object[] args = new Object[tickers.size() + 1];
        for (int i = 0; i < tickers.size(); i++) {
            args[i] = tickers.get(i);
        }
        args[tickers.size()] = windowStart;
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                WHERE s.market_code = 'US' AND s.is_active AND s.ticker IN (%s)
                  AND NOT %s
                  -- 시대 무관: 보존 창과의 겹침이 술어다(usRefillWindowTickers와 같다)
                  AND (s.era_to IS NULL OR s.era_to > ?) AND (s.era_from IS NULL OR s.era_from <= %s)
                ORDER BY s.id
                """.formatted(placeholders, activeMembershipExists("s.id"), TODAY_US), Long.class, args);
    }

    /**
     * 관문 재적재 모집단의 창 시작 — 미국 보존 창의 첫날({@code indicator_trade_date} 미국 최소 = 복원 격자의 첫날). 보존 창이 없으면(지표를
     * 한 번도 안 낸 DB) 과거 격자도 없으므로 미국 동부 오늘이다 — 그때 모집단은 오늘 시대 안 행과 같다.
     */
    public LocalDate usRefillWindowStart() {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce((SELECT min(trade_date) FROM indicator_trade_date WHERE market_code = 'US'), %s)
                """.formatted(TODAY_US), LocalDate.class);
    }

    public List<Long> usRankingCandidateIdsByTicker(List<String> tickers) {
        if (tickers.isEmpty() || !CollectedMarkets.ALL.contains("US")) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(tickers.size(), "?"));
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                WHERE s.market_code = 'US' AND s.is_active AND s.ticker IN (%s)
                  AND NOT %s
                  AND %s
                ORDER BY s.id
                """.formatted(placeholders, activeMembershipExists("s.id"), eraCovers("s", TODAY_US)), Long.class,
                tickers.toArray());
    }

    /**
     * 배치가 처리할 종목의 참조를 만든다.
     *
     * <p>{@code IN} 절을 문자열로 조립하는 이유는 {@code setArray}가 드라이버별 타입 이름에
     * 의존해 이식성이 낮기 때문이다. 값은 바인드 파라미터로 나가므로 주입 위험은 없다 —
     * 자리표시자만 개수만큼 만든다.
     */
    public List<SecurityRef> toSecurityRefs(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(stockIds.size(), "?"));
        Map<Long, List<SecurityRef.SymbolEra>> eras = new java.util.HashMap<>();
        jdbcTemplate.query("SELECT stock_id, symbol, valid_from, valid_to FROM stock_symbol_era WHERE stock_id IN ("
                        + placeholders + ") ORDER BY stock_id, valid_to",
                rs -> {
                    eras.computeIfAbsent(rs.getLong("stock_id"), k -> new ArrayList<>()).add(new SecurityRef.SymbolEra(
                            rs.getString("symbol"), rs.getObject("valid_from", LocalDate.class),
                            rs.getObject("valid_to", LocalDate.class)));
                }, stockIds.toArray());
        // 미국 행은 저장된 신원을 싣는다(ADR-0180) — 어댑터가 표지가 아니라 닻·등록인으로 소스에 묻는다.
        return jdbcTemplate.query("""
                SELECT id, market_code, ticker, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_from, era_to
                FROM stock WHERE id IN (%s) ORDER BY id
                """.formatted(placeholders),
                (rs, rowNum) -> {
                    long id = rs.getLong("id");
                    return new SecurityRef(id, rs.getString("market_code"), rs.getString("ticker"),
                            new SecurityRef.Identity(rs.getString("alpaca_anchor_symbol"),
                                    rs.getObject("alpaca_anchor_date", LocalDate.class),
                                    rs.getObject("sec_cik", Long.class),
                                    rs.getObject("era_from", LocalDate.class),
                                    rs.getObject("era_to", LocalDate.class),
                                    eras.getOrDefault(id, List.of())));
                },
                stockIds.toArray());
    }

    /**
     * 최신 거래일 대비 이 비율 미만으로 봉이 찬 종목을 {@code PRICE_COVERAGE}로 본다.
     *
     * <p><b>이 값은 예산이지 분포에서 유도한 값이 아니다.</b> 2026-09-13 편입 1,350의 분포는
     * {@code PPLC} 51.8% · {@code 440110} 88.7% · 나머지 전부 88.7% 이상이라 <b>관측된 빈 구간은
     * (51.8, 88.7)</b>이고, 거기 두면 지표 10,892행을 내는 정상 종목 {@code PPLC}가 발화한다.
     * 25%는 <b>관측된 결함 하나(코다코 0.4%)와 관측된 최솟값(51.8%)의 사이</b>이며, 모집단에
     * 결함이 하나뿐이라 분포로는 못 정한다. 오늘 발화 0건이다.
     */
    private static final double COVERAGE_FLOOR = 0.25;

    /** 직전 거래일 대비 지표 코드 수가 이 비율 미만이면 {@code INDICATOR_DROP}으로 본다. */
    private static final double INDICATOR_FLOOR = 0.70;

    /**
     * 편입 계약이 깨진 종목을 <b>일곱 축으로 한 번에</b> 센다.
     *
     * <p>편입은 「스크리닝 대상」이라는 뜻인데, 축 하나만 비어도 지표가 판정불가가 되어
     * <b>프리셋에서 조용히 빠지고 배치는 전부 SUCCESS다.</b> 그 침묵을 재는 유일한 자리다.
     *
     * <p><b>MUST: 일곱을 한 질의로 낸다.</b> 축마다 메서드를 만들면 셋은 안 만들어지고, 만들어도
     * 「편입 중인 활성 종목」의 정의가 일곱 벌로 갈린다 — 이 클래스가 존재하는 이유가 그것이다
     * ({@link #activeUniverseMemberIds()} javadoc). 실측 129~171 ms다.
     *
     * <p><b>MUST: 지표 달력을 {@code LEFT JOIN}으로 붙인다.</b> {@code INNER JOIN}이면
     * {@code indicator_trade_date}가 빈 시장의 종목이 <b>일곱 축 전부에서 사라진다</b> —
     * 실측으로 US를 빼면 대상이 1,350 → 350이 되고 US 1,000종목이 0건으로 접힌다.
     * {@link #activeUniverseMemberIdsByTicker} javadoc이 같은 자리를 이름으로 금지한다.
     * 창이 없으면 뒤의 두 축만 판정불가로 두고 나머지 넷은 그대로 판정한다.
     *
     * <p><b>MUST: 업종 축의 술어를 채우는 잡과 같게 둔다.</b> 여기 쓰는
     * {@code industry_code IS NULL AND sic_code IS NULL}은
     * {@link #activeUniverseMemberIdsWithoutIndustry()}와 같고 그것이
     * {@code CompanyProfileJob}의 대상 술어다. ADR-0044 §펜스는 {@code industry_group_key IS NULL}로
     * 재는데 <b>오늘 둘이 같은 셋을 낸다</b> — 갈리면 <b>「알렸는데 돌려도 안 채워지는」 종목</b>이
     * 생기므로 <b>채우는 쪽</b>에 맞춘다. 거울 표는 티커 목록만 지키고 술어는 원리적으로 안 지킨다.
     *
     * <p><b>재무 0행 축은 {@link #activeUniverseMemberIdsNeedingHistory()}의 둘째 갈래와 다르다.</b>
     * 저쪽은 <i>"미해결 {@code FINANCIAL} 실패가 있고 재무가 0행"</i>이라 <b>실패 기록이 있어야</b>
     * 걸린다. 여기는 그 조건을 뺐다 — 한 번도 시도되지 않은 종목이 저쪽에 안 걸리기 때문이다.
     */
    public List<MembershipGap> membershipGaps() {
        return jdbcTemplate.query("""
                WITH mem AS (
                  SELECT s.id, s.market_code, s.ticker, s.name,
                         s.industry_code, s.sic_code,
                         min(m.effective_from) AS joined,
                         coalesce(bool_or(%s), false) AS exempt_industry,
                         coalesce(bool_or((%s) OR (%s)), false) AS exempt_financial
                  FROM universe_membership m
                  JOIN stock s ON s.id = m.stock_id
                  JOIN universe u ON u.code = m.universe_code AND u.is_active
                  WHERE m.effective_to IS NULL AND s.is_active
                    AND %s
                    AND s.market_code IN (%s)
                  GROUP BY s.id, s.market_code, s.ticker, s.name, s.industry_code, s.sic_code
                ), win AS (
                  SELECT t.market_code, min(t.trade_date) AS lo, max(t.trade_date) AS hi,
                         (SELECT max(x.trade_date) FROM indicator_trade_date x
                           WHERE x.market_code = t.market_code
                             AND x.trade_date < max(t.trade_date)) AS prev
                  FROM indicator_trade_date t GROUP BY t.market_code
                ), g AS (
                  SELECT mem.*, win.lo, win.hi, win.prev,
                         (SELECT min(p.trade_date) FROM price_daily p
                           WHERE p.stock_id = mem.id) AS first_bar,
                         NOT EXISTS (SELECT 1 FROM financial_statement f
                                      WHERE f.stock_id = mem.id) AS no_financial,
                         NOT EXISTS (SELECT 1 FROM financial_statement f
                                      WHERE f.stock_id = mem.id
                                        AND f.operating_cash_flow IS NOT NULL) AS no_cash_flow,
                         NOT EXISTS (SELECT 1 FROM governance_report_row r
                                      WHERE r.stock_id = mem.id) AS no_governance,
                         -- 그 시장에 거버넌스 원문이 하나라도 있는가. 소스를 아직 안 붙인
                         -- 시장에서 편입 전원이 한꺼번에 붉는 것을 막는다 — 상시 붉는 펜스는
                         -- 무시되고(§규칙), 합집합 상한을 밀어 시세·재무 적재까지 멈춘다.
                         EXISTS (SELECT 1 FROM governance_report_row r2
                                  JOIN stock s2 ON s2.id = r2.stock_id
                                 WHERE s2.market_code = mem.market_code) AS market_has_governance
                  FROM mem LEFT JOIN win ON win.market_code = mem.market_code
                ), c AS (
                  SELECT g.*,
                         (SELECT count(*) FROM price_daily p
                           WHERE p.stock_id = g.id
                             AND p.trade_date BETWEEN greatest(g.lo, g.first_bar) AND g.hi) AS bars,
                         (SELECT count(*) FROM indicator_trade_date d
                           WHERE d.market_code = g.market_code
                             AND d.trade_date BETWEEN greatest(g.lo, g.first_bar) AND g.hi) AS days,
                         (SELECT count(*) FROM indicator_value iv
                           WHERE iv.stock_id = g.id AND iv.trade_date = g.hi) AS codes_now,
                         (SELECT count(*) FROM indicator_value iv
                           WHERE iv.stock_id = g.id AND iv.trade_date = g.prev) AS codes_prev
                  FROM g
                )
                SELECT id, market_code, ticker, name, exempt_industry, exempt_financial,
                       (first_bar IS NULL OR first_bar > joined) AS gap_price_history,
                       (industry_code IS NULL AND sic_code IS NULL) AS gap_industry,
                       no_financial AS gap_financial,
                       no_cash_flow AS gap_cash_flow,
                       (no_governance AND market_has_governance) AS gap_governance,
                       (days > 0 AND bars < days * %s) AS gap_price_coverage,
                       (codes_prev > 0 AND codes_now < codes_prev * %s) AS gap_indicator_drop
                FROM c
                WHERE (first_bar IS NULL OR first_bar > joined)
                   OR (industry_code IS NULL AND sic_code IS NULL)
                   OR no_financial OR no_cash_flow
                   OR (no_governance AND market_has_governance)
                   OR (days > 0 AND bars < days * %s)
                   OR (codes_prev > 0 AND codes_now < codes_prev * %s)
                ORDER BY market_code, ticker
                """.formatted(KnownSourceGaps.KNOWN_MISSING_SIC,
                        KnownSourceGaps.INFRA_FUND, KnownSourceGaps.FINANCIAL_CURRENCY_MISMATCH,
                        eraCovers("s", TODAY_US), collectedMarketPlaceholders(),
                        COVERAGE_FLOOR, INDICATOR_FLOOR, COVERAGE_FLOOR, INDICATOR_FLOOR),
                (rs, rowNum) -> {
                    Set<MembershipGap.Axis> missing = EnumSet.noneOf(MembershipGap.Axis.class);
                    Set<MembershipGap.Axis> exempt = EnumSet.noneOf(MembershipGap.Axis.class);
                    boolean industryExempt = rs.getBoolean("exempt_industry");
                    boolean financialExempt = rs.getBoolean("exempt_financial");
                    if (rs.getBoolean("gap_price_history")) {
                        missing.add(MembershipGap.Axis.PRICE_HISTORY);
                    }
                    if (rs.getBoolean("gap_industry")) {
                        (industryExempt ? exempt : missing).add(MembershipGap.Axis.INDUSTRY);
                    }
                    if (rs.getBoolean("gap_financial")) {
                        (financialExempt ? exempt : missing).add(MembershipGap.Axis.FINANCIAL);
                    }
                    if (rs.getBoolean("gap_cash_flow")) {
                        (financialExempt ? exempt : missing).add(MembershipGap.Axis.CASH_FLOW);
                    }
                    if (rs.getBoolean("gap_governance")) {
                        missing.add(MembershipGap.Axis.GOVERNANCE);
                    }
                    if (rs.getBoolean("gap_price_coverage")) {
                        missing.add(MembershipGap.Axis.PRICE_COVERAGE);
                    }
                    if (rs.getBoolean("gap_indicator_drop")) {
                        missing.add(MembershipGap.Axis.INDICATOR_DROP);
                    }
                    return new MembershipGap(rs.getLong("id"), rs.getString("market_code"),
                            rs.getString("ticker"), rs.getString("name"), missing, exempt);
                },
                collectedMarkets());
    }
}
