package com.stockscreener.screening.web;

import com.stockscreener.screening.EvaluationMode;

import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 스크리닝 실행 요청.
 *
 * <p>{@code screenId}·{@code presetCode}·{@code conditions} 중 <b>정확히 하나</b>를 지정한다.
 * 셋을 한 엔드포인트에 두는 이유는 그것들이 "조건이 어디서 오는가"만 다르고 실행·기준일 해석·
 * 유니버스 해석·응답 조립이 전부 같기 때문이다. 엔드포인트를 셋으로 쪼개면 그 공통부가
 * 세 번 복제되고, ADR-0003이 조건 모델에 대해 말한 것과 같은 이유로 시간이 지나며 어긋난다.
 *
 * <p>낱개 파라미터가 아니라 record 하나로 받는다 — Phase 6의 인터랙션 로깅 애스펙트가
 * "무엇에 대한 행위였는가"를 인자에서 뽑을 수 있어야 하고, 그때 시그니처를 고치지 않기 위해서다.
 *
 * @param evaluationMode 생략하면 저장된 화면의 값. 즉석 조건은 {@code FILTER}.
 * @param universeCode   생략하면 저장된 화면의 값. 그것도 없으면 전체 유니버스.
 * @param asOf           생략하면 서버가 최신 지표 일자로 정한다. 응답에 항상 에코된다.
 */
public record ScreeningRequest(
        Long screenId,
        String presetCode,
        @Valid ConditionGroupPayload conditions,
        EvaluationMode evaluationMode,
        String universeCode,
        LocalDate asOf
) {

    /** 지정된 실행 대상들. 0개나 2개 이상이면 오류다. */
    public List<String> specifiedSources() {
        List<String> present = new ArrayList<>(3);
        if (screenId != null) {
            present.add("screenId");
        }
        if (presetCode != null && !presetCode.isBlank()) {
            present.add("presetCode");
        }
        if (conditions != null) {
            present.add("conditions");
        }
        return present;
    }
}
