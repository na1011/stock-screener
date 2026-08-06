package com.stockscreener.screening.web;

import com.stockscreener.screening.EvaluationMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 커스텀 화면 생성·수정 요청.
 *
 * <p>IMPORTANT — {@code screenType}과 {@code ownerUserId}가 <b>여기 없다.</b> 서버가 항상
 * {@code CUSTOM} + 현재 사용자로 고정한다. 바디가 정할 수 있게 두면 {@code ck_screen_ownership}을
 * 우회하려는 요청이 500(DataIntegrityViolation)으로 나가고, 그것은 사용자 입력 문제처럼
 * 보이지 않는다. 남의 화면을 만들려는 시도도 표현 자체가 불가능해진다.
 *
 * <p>수정은 조건을 <b>통째로 교체</b>한다. 부분 패치가 없는 이유는 조건 목록의 부분 갱신이
 * "3번을 지우고 5번을 고친다"류의 인덱스 협상을 요구하고, 그 상태에서 {@code sort_order}가
 * 어긋나기 시작하기 때문이다.
 */
public record ScreenRequest(
        @NotBlank(message = "화면 이름이 필요하다")
        @Size(max = 100, message = "화면 이름은 100자를 넘을 수 없다")
        String name,

        @Size(max = 2000, message = "설명이 너무 길다")
        String description,

        @NotNull(message = "판정 방식이 필요하다")
        EvaluationMode evaluationMode,

        String universeCode,

        @Valid @NotNull(message = "조건이 필요하다")
        ConditionGroupPayload conditions
) {
}
