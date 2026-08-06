package com.stockscreener.web;

import com.stockscreener.indicator.IndicatorCalendar;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
     * @return 실제로 지표가 있는 거래일. 호출자는 이것을 <b>응답에 에코해야 한다.</b>
     * @throws ApiExceptions.IndicatorDataNotReadyException          지표가 하나도 없다
     * @throws ApiExceptions.IndicatorHistoryUnavailableException 보존 구간보다 이전이다
     */
    public LocalDate resolve(LocalDate requested) {
        LocalDate latest = calendar.latestIndicatorDate()
                .orElseThrow(ApiExceptions.IndicatorDataNotReadyException::new);

        if (requested == null) {
            return latest;
        }
        if (requested.isAfter(latest)) {
            // 미래나 "오늘"을 물었는데 배치가 아직 안 돈 경우다. 거절하지 않고 최신으로 내린다 —
            // 응답의 asOf 에코가 하루 밀렸다는 사실을 그대로 드러낸다.
            return latest;
        }

        return calendar.nearestOnOrBefore(requested)
                .orElseThrow(() -> new ApiExceptions.IndicatorHistoryUnavailableException(
                        requested,
                        calendar.earliestIndicatorDate().orElse(latest),
                        latest));
    }

    /** 보존 구간. 화면이 날짜 선택기의 범위를 정할 때 쓴다. */
    public Optional<LocalDate> earliest() {
        return calendar.earliestIndicatorDate();
    }
}
