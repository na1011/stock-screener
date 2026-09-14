package com.stockscreener.pipeline.datasource;

/**
 * 한 종목의 <b>어느 금액 컬럼을 이번 수집에서 하나도 못 얻었는가</b>, 그리고 <b>그것이 소스 탓인가
 * 우리 탓인가.</b>
 *
 * <p><b>왜 필요한가</b>: 결측이 전부 같은 얼굴이라 화면이 업종이라는 <b>프록시</b>로밖에 말하지
 * 못한다. 실측(2026-09-14 · {@code US_TOP1000})에서 매출총이익률 결측 380 중 금융 업종으로
 * 설명되는 것이 <b>143(37.6%)</b>뿐이고, 그 설명력은 이 저장소의 가장 약한 기존 사유 규칙
 * ({@code PSR} 64%)보다도 낮다. 나머지는 「데이터 없음」 한마디로 남는다.
 *
 * <p><b>그 사실을 적재가 이미 알고 버린다.</b> {@code SecEdgarFacts}가 컬럼마다 후보 태그를 훑어
 * 팩트 맵을 만드는데, 그것이 비면 「우리 후보로는 그 컬럼이 안 온다」는 뜻이다. 그 값이 쓰이고
 * 사라진다.
 *
 * <p><b>MUST: 두 상태를 갈라 담는다 — 하나로 담으면 화면 문구가 거짓이 된다.</b> 팩트 맵이 비는
 * 원인이 둘이고 사용자가 할 일이 다르다.
 *
 * <p><b>MUST NOT: {@link State#FILTERED}를 화면 사유로 내보내지 않는다.</b> 그것은 그 회사에
 * 대한 사실이 아니라 <b>우리 쪽 한계</b>라서, 「소스에 없습니다」로 말하면
 * {@code IndicatorUnavailabilityConfig}가 금지하는 <b>그럴듯한데 틀린 사유</b>가 된다.
 * 그 구분은 {@code phase-criteria.md} §P22 축①이 이미 요구한 것이다 —
 * <i>"「없다」와 「우리가 못 만들었다」는 다르다"</i>.
 *
 * @param columnName {@code financial_statement}의 컬럼 이름
 * @param state      왜 못 얻었는가
 */
public record FinancialColumnAbsence(String columnName, State state) {

    public enum State {
        /**
         * <b>소스의 팩트 목록에 그 컬럼의 후보 태그가 이름으로도 없다.</b> 그 회사가 그 항목을
         * 공시하지 않는다는 뜻이고, <b>이것만 화면 사유가 된다.</b>
         *
         * <p><b>이 상태는 「우리 후보 목록의 함수」다</b> — 후보를 더하면 같은 종목이
         * {@link #FILTERED}나 정상으로 바뀐다. 그래서 저장 행은 <b>관측 시점의 판정</b>이고,
         * 후보를 바꾼 뒤에는 다시 적재해야 뜻이 맞는다.
         */
        ABSENT,

        /**
         * <b>태그는 있는데 우리가 못 썼다.</b> 금액 단위가 3자 ISO 키가 아니거나
         * ({@code SecEdgarColumn.acceptsUnit}), 팩트가 정기보고서 밖의 폼에만 실렸거나
         * ({@code 8-K}·{@code DEF 14A}), 종료일·공시일·값 중 하나가 비어 있었다.
         *
         * <p><b>이쪽이 크면 그것 자체가 다음 축이다</b> — 우리가 못 만든 것이지 없는 것이 아니다.
         */
        FILTERED,

        /**
         * <b>소스가 그 컬럼의 부분 개념만 낸다.</b> 후보 태그는 하나도 없는데
         * {@code SecEdgarColumn.partialCandidates}의 태그는 있다.
         *
         * <p>{@link #ABSENT}와 <b>사용자가 할 일이 다르다</b> — 저쪽은 그 회사가 그 항목을
         * 아예 공시하지 않는 것이고, 이쪽은 <b>값은 오는데 뜻이 달라 못 쓰는</b> 것이다.
         * 하나로 뭉치면 화면이 「공시에 내지 않습니다」라는 <b>거짓</b>을 말한다(실측 20종목).
         *
         * <p><b>이것도 화면 사유가 된다</b> — {@link #FILTERED}와 달리 그 회사에 대한 사실이다.
         */
        PARTIAL_ONLY
    }
}
