package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.pipeline.datasource.GovernanceDerivation;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceFactMapper;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 미국 배당 사건 스트림을 <b>회계연도 하나의 스칼라</b>로 옮긴다(P28).
 *
 * <p>국내와 무엇이 다른지가 이 클래스의 전부다. DART는 사업보고서 한 장에 회계연도·공시일·
 * 주당배당금·배당총액이 <b>함께</b> 오지만, Alpaca는 <b>결제 하나하나</b>만 준다. 나머지는
 * 재무제표와 기업행위 원장에서 오고, 그것을 넘겨주는 것이 {@link GovernanceDerivation}이다.
 *
 * <h2>버킷은 역년이 아니라 그 회사의 회계연도다</h2>
 * <p>역년으로 자르면 <b>비12월 결산 회사에서 분자와 분모의 기간이 어긋난다</b> —
 * {@code DIVIDEND_PAYOUT}이 같은 회계연도의 순이익으로 나누기 때문이다. 실측으로 미국 편입의
 * 약 24%가 비12월 결산이고(FY2025 기준 991 중 229), 어긋난 값은 <b>그럴듯하다.</b>
 * 창은 {@code [period_start, period_end]}를 그대로 쓴다 — {@code period_end}의 차분으로 만들면
 * 결측 회계연도에서 창이 두 해로 벌어진다.
 *
 * <h2>공시일은 그 회계연도 연간 재무가 <b>처음</b> 공시된 날이다</h2>
 * <p>지어낸 날짜가 아니고, 그날에는 그 회계연도가 끝나 있다(실측: 공시일이 기말보다 이른 그룹
 * 0건 · 최소 지연 18일). <b>국내와 같은 모양</b>이 되는 것이 요점이다 — 둘 다 연차 공시일에
 * 계단으로 바뀐다.
 *
 * <h2>MUST: 완결 판정은 공시일이 아니라 <b>수집일</b>로 한다</h2>
 * <p>「그 해에 배당이 없었다」를 말하려면 그 해를 끝까지 봤어야 한다. 그런데 소스의 조회 창은
 * <b>처리 시각</b> 기준이고 실측 처리 지연이 <b>최대 50일</b>인데, 연간 재무의 공시 지연은
 * <b>최소 18일</b>이다 — 즉 <b>공시일에 서 있는 것만으로는 다 봤다고 말할 수 없다.</b>
 * 그래서 {@code period_end + }{@value #SETTLE_DAYS}{@code 일 <= 수집일}일 때만 사실을 낸다.
 *
 * <h2>MUST: 관측하지 않은 역년이 창에 걸치면 값을 내지 않는다</h2>
 * <p>잡은 <b>역년 단위</b>로 부르는데 회계연도는 두 역년에 걸칠 수 있다. 걸친 역년 중 하나라도
 * 안 받았으면 그 합은 <b>부분 합</b>이고, 부분 합은 「배당이 적다」로 읽힌다.
 *
 * <h2>{@code special}·{@code foreign}을 둘 다 더한다</h2>
 * <p>실측으로 {@code foreign}은 <b>발행사가 외국</b>이라는 뜻이고 {@code rate}는 그대로 USD다 —
 * 플래그가 붙은 셋의 연 합계를 종가로 나누면 0.66~1.13%로 안 붙은 대조와 같은 범위다
 * ({@code alpaca-corporate-action.md}). {@code special}은 국내 {@code DPS_COMMON}이 사업보고서
 * 배당표의 주당 현금배당금이라 특별배당이 그 표에 실리면 함께 들어가므로, <b>빼면 오히려</b>
 * 같은 이름이 시장마다 다른 것을 재게 된다.
 */
@Component
class AlpacaGovernanceFactMapper implements GovernanceFactMapper {

    private static final Logger log = LoggerFactory.getLogger(AlpacaGovernanceFactMapper.class);

    /**
     * 회계연도가 끝난 뒤 <b>이만큼 지나야</b> 그 해의 배당을 다 본 것으로 친다.
     * 실측 최대 처리 지연 50일에 마진을 얹었다({@code alpaca-corporate-action.md}).
     * <b>표본은 50종목·8년</b>이고, 넓히면 꼬리가 길어질 수 있다.
     */
    static final int SETTLE_DAYS = 60;

    /** {@code governance_fact.value}가 {@code numeric(24,8)}이다. 그 자리에서 반올림한다. */
    private static final int SCALE = 8;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of(UsMarket.CODE);
    }

    @Override
    public List<GovernanceFact> derive(GovernanceEndpoint endpoint,
                                       List<GovernanceReportRow> rows,
                                       GovernanceDerivation context) {
        if (endpoint != GovernanceEndpoint.DIVIDEND || rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (context.collectedOn() == null) {
            // 언제 받았는지를 모르면 「그 해를 끝까지 봤는가」를 판정할 수 없다.
            log.warn("수집일을 몰라 배당 사실을 유도하지 않는다. 원문 {}행", rows.size());
            return List.of();
        }

        // 우리가 실제로 물어본 역년. noData 행도 「물어봤다」의 증거다.
        Set<Integer> observedYears = new HashSet<>();
        List<Payment> payments = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            observedYears.add(row.bsnsYear());
            if (row.isNoData()) {
                continue;
            }
            LocalDate exDate = row.publishedAt();
            BigDecimal rate = decimalOf(row.field("rate"));
            if (exDate == null || rate == null) {
                // 원문에 필수 필드가 없다. 지어내지 않고 세어서 남긴다 — 실측 결손 0건이므로
                // 여기가 켜지면 소스 계약이 바뀐 것이다.
                log.warn("배당 행에 효력일이나 금액이 없어 뺀다. bsnsYear={} receiptNo={}",
                        row.bsnsYear(), row.receiptNo());
                continue;
            }
            payments.add(new Payment(exDate, rate));
        }

        List<GovernanceFact> facts = new ArrayList<>();
        int notSettled = 0;
        int notObserved = 0;
        for (GovernanceDerivation.AnnualPeriod period : context.annualPeriods()) {
            if (period.start() == null || period.end() == null
                    || period.firstPublishedAt() == null) {
                continue;
            }
            if (context.collectedOn().isBefore(period.end().plusDays(SETTLE_DAYS))) {
                notSettled++;
                continue;
            }
            if (!coversWholeWindow(observedYears, period)) {
                notObserved++;
                continue;
            }

            LocalDate stamp = period.firstPublishedAt();
            String receiptNo = stamp.format(STAMP);
            BigDecimal perShare = BigDecimal.ZERO;
            for (Payment payment : payments) {
                if (!period.contains(payment.exDate())) {
                    continue;
                }
                // 공시일 기준의 주식수로 환산한다. 회계연도 안에 분할이 있으면 결제마다
                // 주식수 기준이 달라, 안 나누면 그 해의 합이 배수만큼 틀리고 값은 그럴듯하다.
                BigDecimal multiple = context.shareMultiples().between(payment.exDate(), stamp);
                perShare = perShare.add(multiple != null && multiple.signum() > 0
                        ? payment.rate().divide(multiple, SCALE, RoundingMode.HALF_UP)
                        : payment.rate());
            }
            perShare = perShare.setScale(SCALE, RoundingMode.HALF_UP);
            facts.add(new GovernanceFact(period.fiscalYear(), GovernanceMetric.DPS_COMMON,
                    perShare, stamp, receiptNo));

            BigDecimal shares = period.sharesOutstanding();
            if (shares != null && shares.signum() > 0) {
                facts.add(new GovernanceFact(period.fiscalYear(),
                        GovernanceMetric.CASH_DIVIDEND_TOTAL,
                        perShare.multiply(shares).setScale(SCALE, RoundingMode.HALF_UP),
                        stamp, receiptNo));
            }
        }

        if (notSettled > 0 || notObserved > 0) {
            log.debug("배당 사실을 안 낸 회계연도. 미완결 {} · 관측 창 밖 {}", notSettled, notObserved);
        }
        return facts;
    }

    /**
     * 그 회계연도가 걸치는 역년을 <b>전부</b> 물어봤는가.
     *
     * <p>잡은 역년 단위로 부르고 회계연도는 두 역년에 걸칠 수 있다. 한쪽만 받았으면 그 합은
     * <b>부분 합</b>이고, 부분 합은 결손이 아니라 <b>「배당이 적다」</b>로 읽힌다.
     */
    private static boolean coversWholeWindow(Set<Integer> observedYears,
                                             GovernanceDerivation.AnnualPeriod period) {
        for (int year = period.start().getYear(); year <= period.end().getYear(); year++) {
            if (!observedYears.contains(year)) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal decimalOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 결제 하나. {@code ex_date}가 그 배당을 「그날 알았다」로 만드는 날짜다. */
    private record Payment(LocalDate exDate, BigDecimal rate) {
    }
}
