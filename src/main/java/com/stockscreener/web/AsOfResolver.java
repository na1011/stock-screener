package com.stockscreener.web;

import com.stockscreener.indicator.IndicatorCalendar;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/**
 * 요청의 기준 일자를 실제 지표 일자로 해석한다. 해석할 수 없으면 <b>빈 결과가 아니라 오류</b>다.
 *
 * <p>이 클래스가 존재하는 이유는 세 경계가 전부 "결과가 0건"으로 보이기 때문이다.
 * <ul>
 *   <li>배치가 아직 안 돌아 지표가 없다 → 사용자가 할 수 있는 것이 없다. <b>503</b>, 자동 재시도 대상.
 *   <li>보존 구간보다 이전 일자를 물었다 → 조건을 고쳐도 영원히 안 나온다. <b>400</b>.
 *   <li>주말·휴장일을 물었다 → 오류가 아니다. 그 이하 가장 가까운 거래일로 내린다.
 * </ul>
 * 셋을 구분하지 않고 빈 목록을 주면 사용자는 조건을 계속 고치게 되고, 그것이 조용한 실패다.
 *
 * <p>{@code indicator} 패키지가 아니라 여기 있는 이유: "어떤 상태 코드로 거절하는가"는
 * HTTP 계층의 정책이다. 지표 계층이 그것을 알면 배치·워치리스트가 웹 예외에 결합된다.
 */
@Component
public class AsOfResolver {

    private final IndicatorCalendar calendar;

    public AsOfResolver(IndicatorCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * @param requested 요청이 지정한 일자. {@code null}이면 서버가 최신으로 정한다.
     * @param marketCodes 어느 시장의 달력으로 볼 것인가. <b>호출부가 반드시 고른다</b> —
     *                    기본값을 주면 전 시장을 고른 호출부가 <b>아무 증상 없이</b> 국내
     *                    휴장·미국 개장일에 국내를 통째로 비운다(ADR-0095).
     *                    {@link MarketScope}가 유니버스·종목에서 그것을 유도한다
     * @return 실제로 지표가 있는 거래일. 호출자는 이것을 <b>응답에 에코해야 한다.</b>
     * @throws ApiExceptions.IndicatorDataNotReadyException          지표가 하나도 없다
     * @throws ApiExceptions.IndicatorHistoryUnavailableException 보존 구간보다 이전이다
     */
    public LocalDate resolve(LocalDate requested, Collection<String> marketCodes) {
        if (marketCodes == null || marketCodes.isEmpty()) {
            // 시장을 못 정했다 = 그 요청이 볼 수 있는 지표가 없다. 「전 시장」으로 접으면
            // 활성 유니버스가 0인 상태(ADR-0012가 막는 그것)가 조용히 성공으로 보인다.
            throw new ApiExceptions.IndicatorDataNotReadyException();
        }
        LocalDate latest = calendar.latestIndicatorDate(marketCodes)
                .orElseThrow(ApiExceptions.IndicatorDataNotReadyException::new);

        if (requested == null) {
            return latest;
        }
        if (requested.isAfter(latest)) {
            // 미래나 "오늘"을 물었는데 배치가 아직 안 돈 경우다. 거절하지 않고 최신으로 내린다 —
            // 응답의 asOf 에코가 하루 밀렸다는 사실을 그대로 드러낸다.
            return latest;
        }

        return calendar.nearestOnOrBefore(requested, marketCodes)
                .orElseThrow(() -> new ApiExceptions.IndicatorHistoryUnavailableException(
                        requested,
                        calendar.earliestIndicatorDate(marketCodes).orElse(latest),
                        latest));
    }

    /** 보존 구간. 화면이 날짜 선택기의 범위를 정할 때 쓴다. */
    public Optional<LocalDate> earliest(Collection<String> marketCodes) {
        return calendar.earliestIndicatorDate(marketCodes);
    }

    /**
     * 그 시장들의 <b>지표 최신 일자</b>.
     *
     * <p>편입 술어가 이것을 필요로 한다(ADR-0135). 원장의 시계와 지표의 시계가 <b>다르기</b>
     * 때문이다 — 토요일 20:00 리밸런싱이 {@code effective_from = 금요일}을 찍는데 그 금요일
     * 지표는 <b>다음 평일에야</b> 생긴다. 그 사이에는 {@code effective_from > asOf}인 열린 행이
     * 있고, 하한을 그대로 걸면 <b>신규 편입이 화면에서 조용히 사라진다</b>(실측: 지표 최신일이
     * 2026-09-03이었다면 47행). 최신일 조회에서 열린 행의 하한을 접는 판단이 이 값에 기댄다.
     */
    public Optional<LocalDate> latest(Collection<String> marketCodes) {
        return calendar.latestIndicatorDate(marketCodes);
    }
}
