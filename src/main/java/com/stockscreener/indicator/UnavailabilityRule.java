package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 지표 하나가 <b>왜</b> 값이 없는지를 말하는 규칙.
 *
 * <p>{@code when}이 참이고 그 지표에 값이 없을 때만 문구가 붙는다. 값이 있으면 규칙은
 * 아예 보지 않는다 — 사유는 부재에 대한 설명이지 값에 대한 주석이 아니다.
 *
 * @param code   사유를 붙일 지표 코드
 * @param when   그 사유가 성립하는가. <b>이미 읽은 값과 종목 속성만</b> 본다
 * @param reason 사용자에게 보일 문장. <b>서버가 소유한다</b> — 프론트가 자기 문구를 들면
 *               두 곳이 갈리고, 갈린 쪽은 화면에서 드러나지 않는다
 */
public record UnavailabilityRule(String code, Predicate<Context> when, String reason) {

    public UnavailabilityRule {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code가 비어 있다.");
        }
        if (when == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("규칙과 문구가 모두 있어야 한다. code=" + code);
        }
    }

    /**
     * 판정에 쓸 수 있는 것. <b>이 셋으로 답할 수 없는 사유는 규칙으로 두지 않는다.</b>
     *
     * <p>{@code marketCode}가 셋째로 붙은 것은 미국을 켠 뒤다(ADR-0111). <b>원자료가 시장에
     * 따라 아예 없는 축</b>이 생겼고, 그때 「수집되지 않았다」와 「이 시장에는 원래 없다」는
     * 사용자가 할 일이 정반대다 — 앞은 기다리면 되고 뒤는 영영 안 온다.
     *
     * @param values           그 기준일에 계산된 지표 값들. 없는 지표는 키가 없다
     * @param industryDivisionKey 업종 중분류 키. <b>체계 접두가 붙어 있고</b>(ADR-0092)
     *                            업종 미상이면 null이다
     * @param marketCode       그 종목의 시장. 소스가 그 시장을 덮는지 가르는 데 쓴다
     */
    public record Context(Map<String, BigDecimal> values, String industryDivisionKey,
                          String marketCode, Set<String> sourceAbsentColumns,
                          GrossProfitPremise.Break grossProfitPremise) {

        /**
         * 넷째가 없던 시절의 호출부용. <b>부재를 「모른다」로 둔다</b> — 빈 집합은
         * 「그 종목을 그 축으로 안 훑었다」이지 「없는 것이 없다」가 아니다.
         */
        public Context(Map<String, BigDecimal> values, String industryDivisionKey,
                       String marketCode) {
            this(values, industryDivisionKey, marketCode, Set.of(), null);
        }

        public Context(Map<String, BigDecimal> values, String industryDivisionKey,
                       String marketCode, Set<String> sourceAbsentColumns) {
            this(values, industryDivisionKey, marketCode, sourceAbsentColumns, null);
        }

        public Context {
            sourceAbsentColumns = sourceAbsentColumns == null
                    ? Set.of() : Set.copyOf(sourceAbsentColumns);
        }

        public BigDecimal value(String code) {
            return values.get(code);
        }

        /**
         * <b>매출총이익률의 전제가 이 방식으로 깨졌는가.</b>
         *
         * <p>{@link GrossProfitPremise}가 <b>계산기와 이 자리 둘 다에서 같은 술어</b>로 돌아야
         * 한다 — 값을 지우는 쪽과 사유를 내는 쪽이 갈리면 <b>값도 없고 사유도 없는</b> 자리가
         * 생기고, 그것이 이 파일이 금지한 침묵이다.
         */
        public boolean grossProfitPremiseBroke(GrossProfitPremise.Break kind) {
            return grossProfitPremise == kind;
        }

        /**
         * <b>소스가 이 회사에 대해 그 금액 항목을 한 번도 안 냈는가.</b>
         *
         * <p>담기는 것은 {@code financial_column_absence}의 {@code ABSENT} 행뿐이다 —
         * {@code FILTERED}는 <b>우리 쪽 한계</b>라 그 회사에 대한 사실이 아니고, 그것으로
         * 사유를 지으면 이 파일이 금지한 <b>그럴듯한데 틀린 사유</b>가 된다.
         *
         * <p><b>행이 없으면 거짓을 낸다 — 그것이 「모른다」이고 그때는 아무 말도 안 한다.</b>
         */
        public boolean sourceOmits(String... columnNames) {
            for (String column : columnNames) {
                if (!sourceAbsentColumns.contains(column)) {
                    return false;
                }
            }
            return columnNames.length > 0;
        }
    }
}
