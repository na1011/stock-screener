package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 재무 적재 창이 지표가 읽는 창을 덮는지 본다.
 *
 * <p><b>쓰기 창과 읽기 창이 서로를 모르는 것이 실측된 누수의 기제다</b>(ADR-0145).
 * 적재 기본값은 {@code 3}년인데 {@code IndicatorRunner}는
 * {@code registry.maxFinancialYears()}가 내는 <b>10</b>년을 읽는다. 그 사이 회계연도는
 * 매핑을 고쳐도 <b>옛 규칙의 값이 남고</b>, 컬럼을 더해도 영원히 NULL이다 — 둘 다
 * 에러 없이 그럴듯한 지표가 되어 <b>최신 일자 화면은 멀쩡하다.</b>
 *
 * <p>실측 둘이 이 자리를 만들었다(2026-09-14).
 * <ul>
 *   <li>세율 두 컬럼을 창 6년으로 적재했더니 <b>FY2017~2019가 세 시장 모두 0건</b>으로
 *       남았고, 보존 창 앞머리의 5년 세후 커버리지가 최신 일자보다 US 114·KOSPI 14·
 *       KOSDAQ 8만큼 낮았다.</li>
 *   <li>{@code Selection.LARGEST}(e127bd1)로 미국 매출 선택 규칙을 바꾼 뒤 <b>최신 FY
 *       단면에서 센 92종목만</b> 다시 받았는데, 창을 넓혀 전수로 돌리자 교정이
 *       130종목 1,332행이었고 그 연도가 전부 2020~2022였다.</li>
 * </ul>
 *
 * <p>MUST NOT: 이 경고를 「참고」로 읽지 않는다. 매핑·후보 목록·선택 규칙을 바꾼 뒤에
 * 이것이 뜨면 그 창 밖은 <b>고쳐지지 않은 것</b>이다.
 */
@Component
public class FinancialLoadWindow {

    private static final Logger log = LoggerFactory.getLogger(FinancialLoadWindow.class);

    private final IndicatorRegistry registry;

    public FinancialLoadWindow(IndicatorRegistry registry) {
        this.registry = registry;
    }

    /** 지표를 전부 계산하려면 몇 년치 재무가 있어야 하는가. */
    public int yearsNeededByIndicators() {
        return registry.maxFinancialYears();
    }

    /**
     * 적재 창이 지표 창보다 좁으면 경고한다. 좁게 도는 것 자체는 정상이다 — 최신 공시를
     * 따라잡는 정기 실행이 그렇다. 경고가 답하는 것은 <b>「이 실행으로 그 앞 연도는 안
     * 바뀐다」</b> 하나다.
     */
    public void warnIfShorterThanIndicators(String jobName, int years) {
        int needed = yearsNeededByIndicators();
        if (years >= needed) {
            return;
        }
        log.warn("적재 창이 지표 창보다 좁다. 잡={} 적재={}년 지표={}년 — 그보다 앞선 회계연도는"
                        + " 이 실행이 한 행도 건드리지 않는다. 매핑·후보 목록·선택 규칙을 바꾼"
                        + " 뒤라면 --financial-years={}로 다시 돌려야 그 구간이 고쳐진다.",
                jobName, years, needed, needed);
    }
}
