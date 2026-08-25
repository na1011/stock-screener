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
 *
 * <p><b>시더가 심는 프리셋 설명은 아래 {@code description} 상한을 넘는다</b>(2026-08-25 실측:
 * {@code BUFFETT_QUALITY_KR} 2,089자 / 상한 2,000, 넘는 것은 그 하나다). <b>오늘은 안 깨진다</b> —
 * 프리셋은 이 DTO를 거치지 않고, {@code screen.description}은 {@code text}라 저장도 안 막으며,
 * 프론트의 {@code screenDraft}에는 {@code description} 필드가 아예 없어 프리셋을 불러와도
 * 그 값이 요청으로 흘러가지 않는다.
 *
 * <p><b>MUST: 프리셋 설명을 사용자 화면으로 들고 가는 기능을 만들면 그때 이 상한을 정한다.</b>
 * 그 경로가 생기는 순간 시더가 심은 값이 400으로 돌아온다. <b>MUST NOT: 그때 상한을 프리셋
 * 길이에 맞춰 올리지 않는다</b> — 그것은 결과 수로 기준을 정하는 것이고, 프리셋 설명이 지고 있는
 * 결손 고지는 {@code docs/adr/}로 흘려보내는 길이 이미 있다(ADR-0070이 그 둘을 가른다).
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
