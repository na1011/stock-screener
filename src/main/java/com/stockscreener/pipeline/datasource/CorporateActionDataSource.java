package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기업 행위(액면분할·병합·무상증자) 공시 소스. 벤더를 모른다.
 *
 * <p>{@link PriceDataSource}에 얹지 않은 이유는 <b>국내에서 그 값을 주는 곳이 DART이고
 * DART는 시세 소스가 아니기</b> 때문이다. 시세 어댑터가 DART를 부르면 ADR-0006의 국소화가
 * 깨진다. 반대로 {@link PriceDataSource#fetchCorporateActions}는 <b>지우지 않는다</b> —
 * v1.1의 미국 소스는 시세와 기업 행위를 함께 준다(ADR-0049 §버린 대안).
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 */
public interface CorporateActionDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * {@code [from, to]} 구간에 <b>효력일</b>이 드는 기업 행위.
     *
     * <p>MUST: 구현체가 {@code [from, to]}를 {@link CorporateAction#effectiveDate} 기준으로
     * 거른다. 국내 소스는 한 번의 호출이 5년 이력을 주므로, 거르지 않으면 창 밖 사건이
     * <b>매 실행 다시 올라온다</b>(ADR-0049).
     *
     * <p>IMPORTANT — <b>소스가 최대 넉 달 늦을 수 있다.</b> 국내는 이 값이 정기보고서에만
     * 실리므로, 사건은 그것을 담는 보고서가 제출되기 전까지 <b>원리적으로 보이지 않는다</b>
     * (api-spec dart-corporate-action.md의 「증자(감자) 현황」 절). 그래서 <b>좁은 최근 창으로 부르면
     * 구조적으로 0건</b>이고 그것은
     * 실패가 아니다. 호출자의 창은 그 지연보다 넓어야 하며, 그 사이의 사건은
     * {@code AdjustmentRecalculator.detectCandidates}가 후보로 올린다.
     *
     * <p>MUST: <b>시세를 먼저 저장한 뒤 부른다 — 다만 이유가 시장마다 다르다.</b> 국내 구현은
     * 우리가 이미 저장한 시계열({@code StoredPriceSeries})에서 일자와 비율을 <b>유도</b>하므로
     * 순서가 반대면 유도가 조용히 실패한다. 미국 구현은 소스가 비율을 직접 주므로 유도하지
     * 않지만, 호출자({@code CorporateActionCollector})가 봉 없는 종목을 건너뛰므로 결과는 같다 —
     * <b>그쪽에서는 조정할 봉이 없다는 것이 근거다</b>(ADR-0090).
     *
     * <p>빈 목록은 "그 창에 사건이 없다"이지 실패가 아니다.
     */
    /**
     * 이 소스가 그 창의 <b>뒤쪽을 못 덮는다면</b> 그 사실을 한 문장으로 돌려준다.
     * 덮으면 빈 값이다.
     *
     * <p>IMPORTANT — <b>커버리지를 성립으로 읽지 않는다</b>(phase 기준 §P9). 소스가 못 본
     * 구간을 조용히 건너뛰면 호출자가 창 전체를 조회했다고 읽는다. 실제로는 정기보고서
     * 제출 전 구간(최대 넉 달)이 빠져 있다.
     *
     * <p>실패가 아니다 — 그 구간의 불연속은 {@code AdjustmentRecalculator.detectCandidates}가
     * 후보로 올린다. 여기서는 <b>무엇을 못 봤는지 말할 수 있게</b> 하는 것이 전부다.
     *
     * <p>MUST: 종목을 인자로 받지 않는다. 커버리지는 <b>창과 시장의 성질이지 종목의
     * 성질이 아니다</b> — 종목 루프 안에서 찍던 때는 한 판에 350줄이 티커만 다른 채
     * 똑같이 나왔다(2026-09-11 실측 · 운영 로그의 12.7%). 호출자가 루프 <b>앞에서</b>
     * 한 번 부른다.
     *
     * <p>MUST NOT: 구현이 「직전에 답한 창과 같으면 침묵한다」식 상태를 갖지 않는다.
     * 어댑터가 무상태 싱글턴인 것이 종목 루프를 나중에 워커로 흩어도 안전한 근거이고,
     * 깨지면 증상이 "어느 종목의 줄이 다른 스레드 때문에 안 찍혔다"다.
     *
     * @return 못 덮는 구간이 있으면 그 설명, 없으면 빈 값
     */
    default Optional<String> windowCoverage(LocalDate from, LocalDate to) {
        return Optional.empty();
    }

    List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException;
}
