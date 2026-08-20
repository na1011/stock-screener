package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 한 종목·한 거래일의 일목 분석 결과. 지표 18개가 여기서 각자 자기 값을 꺼낸다.
 *
 * <p>MUST: 계산기들이 이 레코드 <b>하나</b>를 나눠 쓴다. 지표마다 다시 분석하면 값이 맞아도
 * 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>여기 있는 값은 전부 <b>관측</b>이다 — 구름의 색, 선의 기울기, 가격의 위치. 이것들을
 * 점수로 합산해 등급을 내던 층은 걷어냈다(ADR-0077). 그 층은 {@code screen_condition}이
 * 표현할 수 없어 지표 안에 숨었고, 그래서 사용자가 왜 걸렸는지 볼 수 없었다. 지금은 조합이
 * 프리셋의 조건 목록으로 화면에 드러난다.
 *
 * @param modA         현재 구름 색. 1 양운 −1 음운
 * @param modB         미래 구름 색. 1 양운 −1 음운
 * @param modC         구름 두께가 130주 중앙값보다 두꺼운가
 * @param modD         후행스팬 앞이 트였는가
 * @param modE         시간론 변화일. <b>−1은 「해당 없음」이라 조건의 좌변으로 쓰면 안 된다</b>
 * @param modF         전환선 기울기 1 상승 0 평탄 −1 하락
 * @param modG         기준선 기울기
 * @param modH         전환·기준선과 구름의 위치. <b>−2는 「교차 없음」이라 좌변으로 쓰면 안 된다</b>
 * @param modJ         선행스팬 B 평탄도
 * @param modK         구름 내부 체류. 0 없음 1 단기 2 장기
 * @param modL         선행스팬 A 기울기
 * @param modM         미래 구름 트위스트까지 남은 주. <b>−1은 「없음」이라 좌변으로 쓰면 안 된다</b>
 * @param kaizenWeekly 주봉 호전 분류 0~3
 * @param kaizenDaily  일봉 호전 분류 0~3
 * @param obvT         13주 OBV의 OLS t-통계량. 유일한 실수 지표이고 ±1.96이 임계다
 * @param divergence   OBV와 가격의 다이버전스. 1강세 −1약세 2정합 0판정불가. <b>명목형이다</b>
 * @param tkCross      전환선이 기준선 <b>위</b>면 1. 센티널이 없어 조건에 쓸 수 있다
 * @param priceVsKumo  종가와 구름의 위치. 1 위 0 안 −1 아래
 */
public record IchimokuAnalysis(
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
        double obvT,
        int divergence,
        int tkCross,
        int priceVsKumo
) {
}
