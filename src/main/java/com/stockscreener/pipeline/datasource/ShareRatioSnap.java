package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 관측한 상장주식수 배수를 <b>단순 유리수로 스냅한다</b>(ADR-0049).
 *
 * <p>액면분할·병합의 배수는 <b>정확히 떨어지지 않는다</b> — 단수주 처리 때문이다. 실측:
 * 대한전선 {@code 0.099999999277}(0.1이 아니다) · 휴림로봇 {@code 0.399999997740} ·
 * 아시아나항공 {@code 0.333333330}. 관측값을 그대로 비율로 쓰면 {@code numeric(18,10)}에서
 * 역수가 {@code 10.0000000700}이 되어 <b>1,492원짜리 봉이 14,920이 아니라 14,920.000104로
 * 조정된다.</b> 반올림 스케일을 낮추는 것은 악화다 — 1/3을 6자리로 접으면 역수 오차가 40배 커진다.
 *
 * <h2>MUST NOT: 무상증자에 쓰지 않는다</h2>
 * <p>ADR-0049의 스냅 규칙은 <b>분할·병합에만 걸린다.</b> 무상증자의 배수도 안 떨어지지만
 * 그 편차는 단수주가 아니라 <b>자사주 미배정</b>이라 실체가 있다 — 씨젠 2021은
 * {@code 52,225,994 = 2 × 26,234,020 − 242,046}이고 그 242,046이 자기주식이다(0.92%).
 * KRX 권리락 기준가가 총주식수 기준이므로 {@code 1.9907735833}이 정본이고 {@code 2}가 아니다.
 * 여기에 스냅을 걸면 실측 무상증자의 상당수가 거부되어 <b>계수가 영영 1로 남는다.</b>
 *
 * <h2>왜 분모 상한이 작아야 하는가</h2>
 * <p>연분수 수렴분수는 분모 {@code q}에 대해 오차가 대략 {@code 1/q²}까지 작아진다. 그래서
 * {@code q ≤ 1000}을 허용하면 <b>어떤 실수든 상대오차 1e-6 안에 수렴분수가 있어 판정력이
 * 0이 된다.</b> {@code q ≤ 50}이면 무관한 값의 최선 근사가 대개 1e-4 수준에 머물러,
 * 아래 거부 사례들이 실제로 거부된다.
 *
 * <p>남는 한계 하나 — {@code 1/3}은 {@code numeric(18,10)}에서 {@code 0.3333333333}으로 접혀
 * 역수가 {@code 3.0000000003}이다. 스냅이 오차를 100배 줄이지만 0으로 만들지는 못한다.
 */
public final class ShareRatioSnap {

    /**
     * 허용하는 최대 분모. <b>키우면 안 된다</b> — 위 javadoc의 판정력 논거가 이 값에 달려 있다.
     * 실측 분모는 10(대한전선) · 5(휴림로봇) · 3(아시아나) · 1(5:1 분할)이다.
     */
    private static final long MAX_DENOMINATOR = 50;

    /** 단수주 처리가 만드는 편차의 실측 상한(1e-8)보다 두 자리 넉넉하다. */
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("1E-6");

    /** {@code corporate_action.ratio numeric(18,10)}에 맞춘다. */
    private static final int RATIO_SCALE = 10;

    private static final MathContext MC = new MathContext(34);

    /** 수렴분수 항의 상한. 분모 상한이 먼저 걸리므로 실제로는 서너 항에서 끝난다. */
    private static final int MAX_TERMS = 24;

    /** 항 하나가 이보다 크면 분모가 어차피 상한을 넘는다. long 곱셈 넘침도 여기서 막힌다. */
    private static final BigDecimal TERM_LIMIT = new BigDecimal("1000000");

    private ShareRatioSnap() {
    }

    /**
     * 관측 배수에 가장 단순한 유리수를 붙인다.
     *
     * <p>{@link Optional#empty()}는 <b>"이 값은 단순 유리수가 아니다"</b>이고, 그것은 대개
     * 분할·병합이 아니거나 다른 사건이 겹쳤다는 신호다(삼성전자 2018은 분할과 자사주 소각이
     * 같은 해에 겹쳐 발행총수 배수가 50이 아니다). <b>비어 있음을 근사값으로 메우지 않는다</b> —
     * 그러면 틀린 계수가 아무 에러 없이 들어간다.
     *
     * <p>반환값이 {@code 1}일 수 있다(주식수가 사실상 안 바뀐 날). 호출자가 그것을 기업 행위로
     * 읽으면 안 된다 — {@link CorporateAction.Type#allows}가 분할·병합 어느 쪽도 허용하지 않아
     * 생성 시점에 예외가 된다.
     *
     * @param observed 관측한 배수. "1주가 몇 주가 되었는가"와 같은 방향이다.
     * @throws IllegalArgumentException 관측 배수가 양수가 아닐 때. 그것은 데이터 손상이다.
     */
    public static Optional<BigDecimal> snap(BigDecimal observed) {
        if (observed == null || observed.signum() <= 0) {
            throw new IllegalArgumentException(
                    "관측 배수는 양수여야 한다: " + observed + " — 상장주식수가 0이거나 음수다.");
        }
        BigDecimal tolerance = observed.multiply(RELATIVE_TOLERANCE, MC);

        // 연분수 수렴분수. h/k가 근사분수이고, 두 항 전까지를 들고 있어야 다음 항을 만든다.
        long numerator = 1;
        long numeratorBefore = 0;
        long denominator = 0;
        long denominatorBefore = 1;

        BigDecimal remainder = observed;
        for (int term = 0; term < MAX_TERMS; term++) {
            BigDecimal whole = remainder.setScale(0, RoundingMode.FLOOR);
            if (whole.compareTo(TERM_LIMIT) > 0) {
                return Optional.empty();
            }
            long a = whole.longValueExact();

            long nextNumerator = a * numerator + numeratorBefore;
            long nextDenominator = a * denominator + denominatorBefore;
            numeratorBefore = numerator;
            numerator = nextNumerator;
            denominatorBefore = denominator;
            denominator = nextDenominator;

            if (denominator > MAX_DENOMINATOR) {
                return Optional.empty();
            }

            BigDecimal candidate = BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), MC);
            if (candidate.subtract(observed).abs().compareTo(tolerance) <= 0) {
                return Optional.of(BigDecimal.valueOf(numerator)
                        .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP));
            }

            BigDecimal fraction = remainder.subtract(whole);
            if (fraction.signum() == 0) {
                // 정확히 유리수인데 위 허용오차에 못 들었다. 더 나눌 것이 없다.
                return Optional.empty();
            }
            remainder = BigDecimal.ONE.divide(fraction, MC);
        }
        return Optional.empty();
    }
}
