package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 한 종목·한 거래일의 일목 분석 결과. 지표 31개가 여기서 각자 자기 값을 꺼낸다.
 *
 * <p>MUST: 계산기 31개가 이 레코드 <b>하나</b>를 나눠 쓴다. 지표마다 다시 분석하면 값이 맞아도
 * 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>점수화 층은 2026-08-14에 걷혔다가 2026-09-02에 되살아났다(ADR-0122). 걷은 근거였던
 * "소비자가 없다"는 <b>에이전트 판단이었지 사용자 지시가 아니었다.</b> ADR-0046이 적은 한계는
 * 그대로다 — 이 값들은 수식자·단계·임계에서 결정론적으로 유도되는 파생이라 새 정보가 없고,
 * 판정이 지표 안에서 끝나 사용자가 왜 걸렸는지 화면에서 볼 수 없다.
 *
 * <p>IMPORTANT — <b>초과 수익률에 기대는 일곱은 {@code Integer}라 {@code null}이 될 수 있다</b>
 * (tier·signal·extSignal·confidence·conclusion·bull·bear). 시장 지수가 없는 일자가 그렇다.
 * <b>0으로 채우지 마라</b> — "지수 없음"이 "초과 수익률 0%"로 조용히 바뀐다. 그 일자를 통째로
 * 버리던 게이트는 철회됐다(ADR-0046 §승인된 이탈 2) — 나머지 스물넷은 지수를 쓰지 않는다.
 *
 * @param tier         1~4. 이 값을 조건으로 쓰던 프리셋은 폐기됐고(ADR-0061) 지금은 조건 빌더에서만 고른다
 * @param signal       {@code score_diff}(강세−약세). 같은 Tier 안 1차 우선순위
 * @param freshness    {@code drv_51}. <b>−1은 "해당 없음"이라 조건의 좌변으로 쓰면 안 된다</b>
 * @param extSignal    {@code score_d2 + score_d5}. 3차 우선순위
 * @param pillar       13 수식자 양수합 − 음수합. 4차 우선순위
 * @param stage        1신생 2진행 3중기 4성숙 5부재 6성숙기. <b>명목형이라 5·6은 1~4의 연장이 아니다</b>
 * @param confidence   1강한 2중간 3약한. 단조 순서형
 * @param conclusion   1강세 −1약세 0중립
 * @param kaizenMatch  {@code drv_45}. 원본의 수식자 i와 <b>같은 값</b>이라 코드를 따로 두지 않는다.
 *                     <b>−1은 「판정 미개시」라 조건의 좌변으로 쓰면 안 된다</b>
 * @param leaf         {@link ValueWidth}의 decision_tree leaf. <b>명목형이라 크기로 비교하지 않는다</b> —
 *                     신생 단계는 언제나 9이고 그 점수 기여는 0이다
 * @param t3yeok       {@code drv_50}. −2 성숙기 · −1 미성립 · 0~129.
 *                     <b>−2와 −1은 정반대 상태인데 값이 이웃해 조건의 좌변으로 쓰면 안 된다</b>
 * @param obvT         {@code drv_26}. 유일한 실수 지표이고 ±1.96이 임계다
 * @param divergence   {@code drv_41}. 1강세 −1약세 2정합 0판정불가. <b>명목형이다</b>
 * @param excessReturn {@code drv_55}. 26주 초과 수익률(%)
 * @param tkCross      전환선이 기준선 <b>위</b>면 1. {@code modH}와 달리 센티널이 없어 조건에 쓸 수 있다
 * @param priceVsKumo  종가와 구름의 위치. 1위 0안 −1아래
 */
public record IchimokuAnalysis(
        Integer tier,
        Integer signal,
        int freshness,
        Integer extSignal,
        int pillar,
        int stage,
        Integer confidence,
        Integer conclusion,
        Integer bull,
        Integer bear,
        int modA,
        int modB,
        int modC,
        int modD,
        int modE,
        int modF,
        int modG,
        int modH,
        int modJ,
        int modK,
        int modL,
        int modM,
        int kaizenWeekly,
        int kaizenDaily,
        int kaizenMatch,
        int leaf,
        int t3yeok,
        double obvT,
        int divergence,
        Double excessReturn,
        int tkCross,
        int priceVsKumo
) {
}
