package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 가격 불연속을 두고 묻는 <b>서로 다른 질문 넷</b>과 그 정본 임계.
 *
 * <p>IMPORTANT — <b>폭이 다른 것은 실수가 아니다.</b> 아래 셋은 각각 다른 것을 묻고, 값을 맞추면
 * 그중 하나가 반드시 제 일을 못 하게 된다. 실측이 그것을 보여준다 — {@link #supportsShareRatio}의
 * 폭(2.0/0.5)으로 {@link #explainsPriceGap}의 자리를 대신하면 <b>루닛 2026-05-04의 주식수 점프 셋이
 * 전부 통과해</b> 판정력이 0이 되고, 반대로 {@code explainsPriceGap}의 폭(1.35/0.65)으로
 * {@code supportsShareRatio}를 대신하면 아시아나항공 2021-01-15(관측 1.4252)이 잘려 나간다.
 *
 * <p>흩어져 있던 것을 모은 이유는 <b>부호와 방향을 정하는 곳을 하나로 유지하기 위해서다</b>
 * (ADR-0049 · ADR-0050). 특히 {@link #observedRatio}가 그렇다 — 코드베이스에 이미
 * {@code close/prevClose}(교차확인용)와 {@code prevClose/close}(계수 방향)가 함께 있고,
 * {@link CorporateAction.Type#UNKNOWN}은 부호 검사를 <b>도메인도 DB 제약도 통과시키므로</b>
 * 뒤집혀도 아무것도 안 잡는다.
 */
public final class PriceDiscontinuity {

    /**
     * KRX 가격제한폭. 이 폭을 넘는 하루 변동은 정상 매매로 설명되지 않는다.
     *
     * <p>IMPORTANT — <b>비대칭이다.</b> 상한가는 {@code close/prev = 1.30}, 하한가는 {@code 0.70}이고
     * 두 값은 서로 역수가 아니다. 한쪽 값을 양방향에 쓰면(예: 1/0.7 = 1.4286을 위쪽에도)
     * +30~42.9% 구간이 통째로 그물을 빠져나간다.
     */
    public static final BigDecimal LIMIT_UPPER = new BigDecimal("1.30");
    public static final BigDecimal LIMIT_LOWER = new BigDecimal("0.70");

    /**
     * 권리락 당일 봉이 기준가에서 벗어날 수 있는 폭.
     *
     * <p>{@link #LIMIT_UPPER}보다 넓은 것이 맞다 — 여기서 재는 것은 <b>기준가 대비</b>이고,
     * 기준가 자체가 호가단위로 반올림되므로 제한폭에 그만큼 여유가 붙는다.
     */
    public static final BigDecimal EX_RIGHTS_UPPER = new BigDecimal("1.35");
    public static final BigDecimal EX_RIGHTS_LOWER = new BigDecimal("0.65");

    /**
     * 주식수에서 낸 비율을 가격이 뒷받침하는지 볼 때의 허용 폭.
     *
     * <p>IMPORTANT — <b>일일 가격제한폭으로 잡으면 안 된다.</b> 변경상장·재상장일의 시초가는 그 폭
     * 밖에서 정해질 수 있다. 실측 25건에서 이론 배수 대비 관측 배수의 비가 0.90~1.43이었고
     * (아시아나항공 2021-01-15의 무상감자가 1.43이다), ±30%로 자르면 그것이 잘린다. 여기서 잡으려는
     * 것은 <b>주식수 배수가 우연히 단순 유리수인데 가격과 아무 관계도 없는 경우</b>이므로 두 배 폭으로
     * 넉넉히 둔다.
     */
    public static final BigDecimal SUPPORT_UPPER = new BigDecimal("2.0");
    public static final BigDecimal SUPPORT_LOWER = new BigDecimal("0.5");

    /** {@code price_daily.adj_factor numeric(18,10)}에 맞춘다. */
    public static final int RATIO_SCALE = 10;

    private static final MathContext MC = new MathContext(34);

    private PriceDiscontinuity() {
    }

    /**
     * 그날 변동이 정상 매매로 설명되지 않는가.
     *
     * <p>종가가 없거나 0 이하면 {@code false}다 — 그것은 불연속이 아니라 결손이고, 나눗셈에 넣으면
     * 예외가 된다. {@code AdjustmentRecalculator}의 탐지 SQL이 같은 가드를 {@code WHERE}에 갖는다.
     */
    public static boolean isDiscontinuous(BigDecimal close, BigDecimal prevClose) {
        if (close == null || prevClose == null || close.signum() <= 0 || prevClose.signum() <= 0) {
            return false;
        }
        BigDecimal multiple = close.divide(prevClose, MC);
        return multiple.compareTo(LIMIT_UPPER) > 0 || multiple.compareTo(LIMIT_LOWER) < 0;
    }

    /**
     * 관측된 종가 배수가 <b>그 비율의 권리락</b>으로 설명되는가.
     *
     * <p>권리락 당일 봉은 기준가({@code 직전 종가 / ratio})에서 일일 가격제한폭 안에 있어야 한다.
     * 이것으로 <b>비율의 정확도를 재지는 못한다</b> — 폭이 ±35%나 되기 때문이다. 여기서 잡는 것은
     * "그날 아무 일도 없었다"는 경우다.
     *
     * <p>그래서 이 판정은 <b>후보가 여럿일 때 고르는 데</b> 쓸 수 있다. 실측: 권리락 당일 주식수가
     * 그대로인 후보 37건에서 이후 75일 안의 주식수 점프 60개를 이 폭으로 재면 35건이 정확히 하나만
     * 통과하고, 하나도 통과하지 않는 후보는 없으며, 둘 이상 통과하는 2건이 <b>사람이 실제로 봐야 하는
     * 것들</b>이다(2026-08-12 측정, ADR-0050의 premise가 이 값을 다시 잰다).
     *
     * @param observedMultiple 권리락 당일의 {@code close / prevClose}
     * @param ratio            "1주가 몇 주가 되었는가"
     */
    public static boolean explainsPriceGap(BigDecimal observedMultiple, BigDecimal ratio) {
        if (observedMultiple == null || ratio == null || ratio.signum() <= 0) {
            return false;
        }
        BigDecimal relative = observedMultiple.multiply(ratio, MC);
        return relative.compareTo(EX_RIGHTS_LOWER) >= 0 && relative.compareTo(EX_RIGHTS_UPPER) <= 0;
    }

    /**
     * 주식수에서 낸 비율이 그날 가격 움직임과 <b>아무 관계도 없지는 않은가</b>.
     *
     * <p>{@link #explainsPriceGap}과 같은 식이지만 폭이 넓고 묻는 것이 다르다 — 저쪽은 "이 비율이
     * 그날을 설명하는가", 이쪽은 "이 비율이 우연이 아닌가"다. 변경상장·재상장일의 시초가가 제한폭
     * 밖에서 정해지므로 좁히면 멀쩡한 사건이 잘린다.
     */
    public static boolean supportsShareRatio(BigDecimal ratio, BigDecimal close,
                                             BigDecimal prevClose) {
        if (ratio == null || close == null || prevClose == null
                || ratio.signum() <= 0 || prevClose.signum() <= 0) {
            return false;
        }
        BigDecimal relative = close.multiply(ratio, MC).divide(prevClose, MC);
        return relative.compareTo(SUPPORT_LOWER) >= 0 && relative.compareTo(SUPPORT_UPPER) <= 0;
    }

    /**
     * 종류를 모르는 불연속에 실어 보낼 <b>관측값</b>. 이것이 그 방향의 정본이다.
     *
     * <p>MUST NOT: 이 값을 계수로 쓰지 않는다. {@link CorporateAction.Type#UNKNOWN}의
     * {@code ratio}는 관측된 가격 배수일 뿐이고 {@code findApplicable}이 그 종류를 제외한다.
     *
     * <p>방향은 {@code ratio}의 계약("1주가 몇 주가 되었는가")과 <b>같은 쪽</b>이다 — 5:1 분할이면
     * 가격이 1/5이 되므로 {@code prevClose/close}가 5다. 뒤집으면 아시아나항공 2021-01-15에서
     * 0.2339가 4.2755가 되는데, {@code UNKNOWN}은 도메인의 {@code Type.allows}도 V21의
     * {@code ck_ca_ratio_sign}도 통과시키므로 <b>어디에서도 안 걸린다.</b>
     */
    public static BigDecimal observedRatio(BigDecimal close, BigDecimal prevClose) {
        if (close == null || prevClose == null || close.signum() <= 0) {
            throw new IllegalArgumentException(
                    "관측 배수를 낼 수 없다. close=" + close + " prevClose=" + prevClose);
        }
        return prevClose.divide(close, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
