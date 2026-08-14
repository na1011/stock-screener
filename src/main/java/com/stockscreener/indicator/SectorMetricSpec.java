package com.stockscreener.indicator;

/**
 * "이 지표의 <b>업종 내</b> 상대값도 함께 저장한다"는 선언.
 *
 * <p>{@link PercentileSpec}과 형제다 — 둘 다 {@link IndicatorCalculator}가 될 수 없다.
 * 계산기는 한 종목의 원자료만 보는데 이 값들은 같은 날 <b>여러 종목</b>을 봐야 정해진다.
 * 다른 점은 <b>모집단</b>이다. 백분위는 유니버스 안에서 매기고(ADR-0036), 이쪽은
 * <b>활성 유니버스 전체 중 같은 업종</b>에서 매긴다(ADR-0044).
 *
 * <p>MUST: 한 원본 지표에 선언이 <b>둘 이상</b>이면 표본 수 행({@link #peerCountCode()})을
 * 그들이 공유한다. 그 행의 이름은 {@code kind}가 아니라 <b>원본 지표</b>에서 지어야 한다 —
 * spec 이름을 쓰면 카탈로그 UPSERT가 뒤에 오는 선언으로 덮어써서, 표시 이름이
 * <b>리스트 순서에 따라 뒤집힌다.</b>
 *
 * <p>선언 하나가 지표 <b>둘</b>을 만든다 — 상대값과 <b>그 값을 낼 때 실제로 비교한 표본 수</b>다.
 * 표본 수를 함께 내는 이유는 임계(5)가 <b>5 미만만</b> 막기 때문이다. 5와 40의 차이는 값에
 * 아무 흔적을 남기지 않고, 계층 폴백까지 들어가면 같은 컬럼의 두 값이 서로 다른 크기의
 * 모집단에서 나온다. 표본 수를 지표 행으로 내면 결과 표에 그대로 나오고 조건으로도 걸 수 있다.
 *
 * <p>MUST: 표본 수는 <b>지표마다</b> 따로 센다. 같은 업종이라도 지표에 따라 값이 있는 종목이
 * 다르다 — 실측에서 부채비율은 350종목 중 346이지만 PER은 269다(적자 기업에 값을 내지 않는다).
 * 구조적 업종 크기 하나로 대신하면 <b>표본 5 이상인 소분류 23개 중 6개에서 PER의 실제 표본이
 * 5 미만</b>인데도 "충분"이라 말하게 된다.
 *
 * @param sourceCode 상대값을 낼 원본 지표 코드
 * @param kind       업종과 비교하는 방식
 * @param name       화면에 보일 이름
 * @param description 이 값이 무엇인지
 * @param interpretation 어느 방향이 유리한지
 */
public record SectorMetricSpec(
        String sourceCode,
        Kind kind,
        String name,
        String description,
        String interpretation
) {

    /** 업종 상대값 코드의 접미사. 원본 코드에 붙여 만든다. */
    public static final String PERCENTILE_SUFFIX = "_SECTOR_PCTL";

    /** 업종 중앙값 배수 코드의 접미사. */
    public static final String MEDIAN_RATIO_SUFFIX = "_SECTOR_REL";

    /** 업종 중앙값 자체의 접미사. */
    public static final String MEDIAN_SUFFIX = "_SECTOR_MEDIAN";

    /** 표본 수 코드의 접미사. 위 둘 어느 쪽이든 짝으로 따라온다. */
    public static final String PEER_COUNT_SUFFIX = "_SECTOR_N";

    public SectorMetricSpec {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode가 비어 있다.");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind가 비어 있다. sourceCode=" + sourceCode);
        }
    }

    /** 저장될 상대값 지표 코드. */
    public String code() {
        return sourceCode + kind.suffix();
    }

    /** 저장될 표본 수 지표 코드. */
    public String peerCountCode() {
        return sourceCode + PEER_COUNT_SUFFIX;
    }

    /**
     * 업종과 비교하는 방식.
     *
     * <p>둘을 한 접미사로 합치지 않는 이유는 <b>값의 뜻이 다르기 때문</b>이다. 백분위는 0~1이고
     * 0.5가 중앙이며, 배수는 1.0이 중앙이고 위로 열려 있다. 같은 이름으로 부르면 화면이
     * "상위 20% = 0.2 이하" 같은 안내를 배수 값에도 붙인다(ADR-0044).
     */
    public enum Kind {

        /** 업종 내 순위(0~1, 오름차순). 원본 §S-01·S-02의 "업종 상위 N%"가 이것이다. */
        PERCENTILE(PERCENTILE_SUFFIX),

        /** 값 ÷ 업종 중앙값. 원본 §H-02의 "D/E ÷ 업종 중앙값"이 이것이다. */
        MEDIAN_RATIO(MEDIAN_RATIO_SUFFIX),

        /**
         * 업종 중앙값 <b>자체</b>. 같은 업종 종목이 같은 값을 받는다.
         *
         * <p>위 둘과 성격이 다르다 — 저 둘은 <b>이 종목에 대한 판정</b>이고 이것은
         * <b>참조점</b>이다. 그래서 유불리 방향이 없다. "업종 PER이 낮아서 좋다"는 문장이
         * 성립하지 않는데, 방향을 주면 화면이 정확히 그 문장을 그린다.
         */
        MEDIAN(MEDIAN_SUFFIX);

        private final String suffix;

        Kind(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }
}
