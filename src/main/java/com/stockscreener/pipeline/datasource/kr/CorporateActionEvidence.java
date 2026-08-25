package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.PriceDiscontinuity;
import com.stockscreener.pipeline.datasource.ShareRatioSnap;
import com.stockscreener.pipeline.datasource.StoredPriceSeries;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 우리가 저장한 시계열이 기업 행위에 대해 <b>실제로 무엇을 말하는가</b>.
 *
 * <p>DART가 답하는 것은 <i>"그 무렵 이런 종류의 사건이 있었다"</i>까지다. 언제 효력이
 * 생겼고 비율이 얼마인지는 {@code listed_shares}가 답한다(ADR-0049 · api-spec dart-corporate-action.md의
 * 「분할·병합의 비율과 효력일」 절).
 * 여기 있는 것은 전부 <b>순수 함수</b>이며 소스를 모른다.
 *
 * <p>IMPORTANT — <b>일자 보정의 방향이 사건 종류마다 반대다.</b> 액면분할은 소스 원값이
 * 매매개시일보다 이르고(NAVER 2018.10.10 → 실제 10-12), 무상증자는 권리락보다 늦다
 * (루닛 2023.11.10 → 권리락 11-09). 한쪽 방향으로만 찾으면 무상증자에서 <b>권리락 당일
 * 봉이 조정 대상에 들어가 한 번 더 나뉜다</b> — 정확히 한 봉이 틀리고 차트를 직접 보기
 * 전까지 드러나지 않는다.
 */
final class CorporateActionEvidence {

    private static final MathContext MC = new MathContext(34);

    private final List<StoredPriceSeries.Bar> bars;

    CorporateActionEvidence(List<StoredPriceSeries.Bar> bars) {
        this.bars = bars;
    }

    /**
     * 액면 변경. {@code ratio}는 "1주가 몇 주가 되었는가"이며 병합이면 1보다 작다.
     */
    record ParValueChange(LocalDate effectiveDate, BigDecimal ratio) {
    }

    /**
     * 신주 상장. {@code base}는 상장 <b>직전</b> 거래일의 상장주식수다.
     */
    record ShareIssue(LocalDate listingDate, BigDecimal base, BigDecimal after) {

        BigDecimal ratio() {
            return after.divide(base, 10, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * 창 안에서 가격이 정상 매매로 설명되지 않게 뛴 <b>첫 거래일</b>.
     *
     * <p>MUST NOT: 여기서 비율을 함께 내지 않는다. {@code UNKNOWN}에 실을 관측값의 방향을 정하는
     * 곳은 {@code PriceDiscontinuity.observedRatio} 하나이고, 이 클래스에는 이미 방향이 반대인
     * {@link #closeMultipleOn}(교차확인용)이 있다. 둘이 섞이면 <b>어디에서도 안 걸린다</b> —
     * {@code UNKNOWN}은 도메인의 부호 검사도 DB 제약도 통과한다.
     *
     * <p>효력일의 정의가 "가격이 뛴 날"이므로 이것이 옳은 앵커다. 소스가 낱말만 주고 종류를
     * 확정해 주지 못하는 사건(무상감자·처음 보는 형태)에 쓴다.
     */
    Optional<LocalDate> firstPriceGap(LocalDate notBefore, LocalDate notAfter) {
        for (int i = 1; i < bars.size(); i++) {
            LocalDate date = bars.get(i).tradeDate();
            if (date.isBefore(notBefore)) {
                continue;
            }
            if (date.isAfter(notAfter)) {
                return Optional.empty();
            }
            if (PriceDiscontinuity.isDiscontinuous(bars.get(i).close(), bars.get(i - 1).close())) {
                return Optional.of(date);
            }
        }
        return Optional.empty();
    }

    /**
     * 상장주식수 배수가 <b>단순 유리수로 스냅되는 첫 거래일</b>.
     *
     * <p>MUST NOT: 그냥 "주식수가 바뀐 첫 거래일"을 쓰지 않는다. 유상증자·전환권행사·
     * 주식매수선택권행사가 상시 주식수를 조금씩 바꾸므로 분할 직전에 얼마든지 낀다 —
     * 카카오 2021은 분할(04-15) 엿새 전 04-09에 ×1.00017 변동이 있고, 소스 원값
     * 2021.04.14에서 사흘을 물러 잡은 탐색 창의 시작과 <b>이틀 차이</b>다. 크기를 안 보면
     * 그 봉을 효력일로 집고, 액면가 교차확인까지 함께 어긋난다.
     *
     * <p>MUST NOT: 스냅됐다는 것을 "분할·병합이다"로 읽지 않는다. 삼바 인적분할의 0.650은
     * 13/20이라 스냅을 통과한다 — 그것을 거르는 것은 소스의 낱말이거나 가격 배수와의 대조다.
     */
    Optional<ParValueChange> firstSnappedShareChange(LocalDate notBefore, LocalDate notAfter) {
        for (int i = 1; i < bars.size(); i++) {
            StoredPriceSeries.Bar bar = bars.get(i);
            if (bar.tradeDate().isBefore(notBefore) || bar.tradeDate().isAfter(notAfter)) {
                continue;
            }
            BigDecimal before = bars.get(i - 1).listedShares();
            BigDecimal after = bar.listedShares();
            if (before == null || after == null || before.signum() <= 0 || after.signum() <= 0
                    || before.compareTo(after) == 0) {
                continue;
            }
            Optional<BigDecimal> snapped = ShareRatioSnap.snap(after.divide(before, MC));
            if (snapped.isPresent() && snapped.get().compareTo(BigDecimal.ONE) != 0) {
                return Optional.of(new ParValueChange(bar.tradeDate(), snapped.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * 상장주식수가 <b>정확히 {@code quantity}만큼</b> 는 거래일.
     *
     * <p>IMPORTANT — 정수 완전 일치다. 근사가 아닌 이유는 그래야 <b>애매하지 않기</b>
     * 때문이다. 실측으로 두 표본 모두 성립한다 — 루닛 2023-12-01은
     * {@code 28,476,308 − 14,238,154 = 14,238,154}, 2026-05-26은
     * {@code 74,382,636 − 37,191,318 = 37,191,318}이고 둘 다 {@code isu_dcrs_qy}와 같다.
     * 우선주 행이 섞여 와도 이 앵커가 걸러낸다.
     *
     * <p><b>분모가 왜 상장일 직전인가</b> — 권리락일 주식수를 쓰면 틀린다. 루닛 2023은
     * 권리락일 12,381,004인데 상장 직전은 14,238,154이고(11-24에 별개 증가) 2026은
     * 29,284,502 대 37,191,318이다. 각각 2.15·2.27이 나와 <b>정답 2.0이 아니다.</b>
     */
    Optional<ShareIssue> shareIncreaseOf(BigDecimal quantity, LocalDate notBefore, LocalDate notAfter) {
        if (quantity == null || quantity.signum() <= 0) {
            return Optional.empty();
        }
        for (int i = 1; i < bars.size(); i++) {
            StoredPriceSeries.Bar bar = bars.get(i);
            if (bar.tradeDate().isBefore(notBefore) || bar.tradeDate().isAfter(notAfter)) {
                continue;
            }
            BigDecimal before = bars.get(i - 1).listedShares();
            BigDecimal after = bar.listedShares();
            if (before == null || after == null || before.signum() <= 0) {
                continue;
            }
            if (after.subtract(before).compareTo(quantity) == 0) {
                return Optional.of(new ShareIssue(bar.tradeDate(), before, after));
            }
        }
        return Optional.empty();
    }

    /** {@code date}보다 <b>엄격히 이른</b> 마지막 거래일. 무상증자의 권리락이 이 자리다. */
    Optional<LocalDate> lastTradingDayBefore(LocalDate date) {
        LocalDate found = null;
        for (StoredPriceSeries.Bar bar : bars) {
            if (bar.tradeDate().isBefore(date)) {
                found = bar.tradeDate();
            } else {
                break;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * 그 거래일의 {@code close / prev_close}. 직전 봉이 없거나 0이면 비어 있음.
     *
     * <p>일자 보정이 맞았는지를 <b>가격으로 교차확인</b>하는 데 쓴다. 비율을 여기서 내지는
     * 않는다 — 종가 배수는 그날 시장이 움직인 만큼 흐려진다(대한전선 10.63 대 정확한 10).
     */
    /**
     * 그 거래일에 관측된 {@code ratio} 방향의 값. 종류를 모르는 사건에 실어 보낸다.
     *
     * <p>{@link #closeMultipleOn}과 <b>방향이 반대다</b>. 그래서 역수를 호출부에서 구하지 않고
     * 여기서 {@code PriceDiscontinuity}에 넘긴다 — 방향을 정하는 곳이 둘이 되면 뒤집혀도
     * {@code UNKNOWN}은 도메인·DB 어느 쪽에서도 안 걸린다.
     */
    Optional<BigDecimal> observedRatioOn(LocalDate tradeDate) {
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).tradeDate().equals(tradeDate)) {
                continue;
            }
            BigDecimal previous = bars.get(i - 1).close();
            BigDecimal current = bars.get(i).close();
            if (previous == null || current == null || current.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(PriceDiscontinuity.observedRatio(current, previous));
        }
        return Optional.empty();
    }

    Optional<BigDecimal> closeMultipleOn(LocalDate tradeDate) {
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).tradeDate().equals(tradeDate)) {
                continue;
            }
            BigDecimal previous = bars.get(i - 1).close();
            BigDecimal current = bars.get(i).close();
            if (previous == null || current == null || previous.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(current.divide(previous, MC));
        }
        return Optional.empty();
    }
}
