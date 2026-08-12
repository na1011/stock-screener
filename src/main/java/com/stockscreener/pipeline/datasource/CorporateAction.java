package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수정계수에 영향을 주는 기업 행위.
 *
 * <p>{@code ratio}는 <b>1주가 몇 주가 되었는가</b>다. 50:1 액면분할이면 50, 10주를 1주로
 * 합치는 병합이면 0.1, 1:1 무상증자면 2다. 이 방향을 뒤집으면 과거 시세가 50배로 부풀고,
 * 그 오류는 분할 종목의 차트를 직접 보기 전까지 드러나지 않는다.
 *
 * <p>IMPORTANT — <b>부호를 정하는 곳은 하나다.</b> 종류와 비율은 같은 나눗셈
 * ({@code prev_close / close}) 하나에서 함께 나오고, 적용부는 {@link #type()}을 읽지 않고
 * {@code 1/ratio}만 쓴다. 실제로 탐지가 병합에 역수를 실어 보내는데 적용부가 종류를 읽지
 * 않아 무증상이었던 적이 있다 — 오차 113배인데 값은 그럴듯했다(ADR-0049). 아래 컴팩트
 * 생성자와 {@code ck_ca_ratio_sign}(V21)이 그 조합을 양쪽에서 막는다.
 *
 * @param effectiveDate 권리락일. 변경된 가격으로 거래가 시작되는 날이며, 이 날 <b>이전</b>
 *                      봉들이 조정 대상이다.
 * @param listingDate   신주상장일. 상장주식수가 늘어나는 날이다. 액면분할은
 *                      {@code effectiveDate}와 같지만 <b>무상증자는 14~16거래일 벌어진다</b> —
 *                      그 구간은 이미 희석된 가격을 희석 전 주식수와 짝지어 시가총액·PER·PBR이
 *                      배수만큼 틀린다. 모르면 null.
 * @param sourceDate    소스가 준 원값(DART {@code isu_dcrs_de}). 일자 보정 전이다. 보정이
 *                      틀렸을 때 <b>소스가 틀린 건지 우리가 틀린 건지</b> 가르는 데 쓴다.
 * @param source        이 사건을 무엇이 알려줬는가. 소스 공시와 휴리스틱 탐지를 구분해야
 *                      자동 적용 여부를 판단할 수 있다.
 * @param sourceForm    소스가 이 사건을 부르는 낱말(DART {@code isu_dcrs_stle}). 모르면 null.
 *                      {@link Type#UNKNOWN}인데 이 값이 있으면 <b>"이름은 아는데 계수를 못
 *                      낸다"</b>는 뜻이고, 그 구분이 없으면 사람이 판정 화면에서 무상감자를
 *                      액면병합과 구별할 수 없다(ADR-0050).
 */
public record CorporateAction(
        LocalDate effectiveDate,
        LocalDate listingDate,
        LocalDate sourceDate,
        Type type,
        BigDecimal ratio,
        DetectionSource source,
        String sourceForm
) {

    public CorporateAction {
        if (effectiveDate == null) {
            throw new IllegalArgumentException("effectiveDate가 null이다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type이 null이다.");
        }
        if (ratio == null || ratio.signum() <= 0) {
            throw new IllegalArgumentException("ratio는 양수여야 한다: " + ratio);
        }
        if (!type.allows(ratio)) {
            throw new IllegalArgumentException(
                    "type과 ratio의 부호가 어긋난다. type=" + type + " ratio=" + ratio
                            + " — ratio는 '1주가 몇 주가 되었는가'이고 병합이면 1보다 작다.");
        }
    }

    /** 날짜를 하나만 아는 소스용. 미국 어댑터(v1.1)와 휴리스틱 탐지가 이 자리다. */
    public CorporateAction(LocalDate effectiveDate, Type type, BigDecimal ratio,
                           DetectionSource source) {
        this(effectiveDate, null, null, type, ratio, source, null);
    }

    /**
     * 소스가 <b>이름만</b> 알려준 사건. 종류와 비율은 모른다.
     *
     * <p>범위 밖 형태(무상감자)와 처음 보는 형태가 이 자리다. {@code ratio}는 관측된 가격
     * 배수이며 계수가 아니다 — {@code findApplicable}이 {@link Type#UNKNOWN}을 제외한다.
     */
    public static CorporateAction named(LocalDate effectiveDate, LocalDate sourceDate,
                                        BigDecimal observedRatio, String sourceForm) {
        return new CorporateAction(effectiveDate, null, sourceDate, Type.UNKNOWN, observedRatio,
                DetectionSource.SOURCE_DISCLOSURE, sourceForm);
    }

    public enum Type {
        /** 액면분할. ratio > 1. */
        SPLIT,
        /** 액면병합. ratio < 1. */
        REVERSE_SPLIT,
        /** 무상증자. ratio > 1이며 1:1이면 2다. 상장주식수가 권리락보다 늦게 는다. */
        BONUS_ISSUE,
        /**
         * 가격이 뛴 것은 확실하나 종류를 모른다. 인적분할·복합 사건·거래재개가 여기 들어온다.
         *
         * <p>IMPORTANT — 이 종류의 {@code ratio}는 <b>관측된 가격 배수일 뿐 계수가 아니다.</b>
         * 그대로 적용하면 틀린 계수가 조용히 들어가므로 재구축 대상에서 제외된다.
         */
        UNKNOWN;

        /** V21의 {@code ck_ca_ratio_sign}과 같은 판정이다. 둘이 갈리면 적재가 막혀 드러난다. */
        boolean allows(BigDecimal ratio) {
            int vsOne = ratio.compareTo(BigDecimal.ONE);
            return switch (this) {
                case SPLIT, BONUS_ISSUE -> vsOne > 0;
                case REVERSE_SPLIT -> vsOne < 0;
                case UNKNOWN -> true;
            };
        }
    }

    /**
     * IMPORTANT — 이 구분이 사람의 판정을 요구하는지를 가른다.
     *
     * <p>{@link #SOURCE_DISCLOSURE}는 신뢰할 수 있으므로 원장에 {@code APPROVED}로 들어온다.
     * {@link #HEURISTIC}은 하한가 연속·거래재개·인적분할을 분할로 오인할 수 있고, 오탐으로
     * 2년치를 잘못 재계산하면 되돌리기가 비싸다. 그래서 {@code OBSERVED}에서 멈춘다.
     */
    public enum DetectionSource {
        SOURCE_DISCLOSURE,
        HEURISTIC
    }
}
