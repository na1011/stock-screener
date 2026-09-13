package com.stockscreener.pipeline.collector;

/**
 * 편입 종목인데 <b>소스가 원리적으로 안 주거나, 채워도 지표가 안 나는</b> 자리.
 *
 * <p>감시({@link StockRegistrar#membershipGaps()})가 이것을 결손으로 <b>신고하지 않게</b> 빼는
 * 술어다. <b>MUST: 빼되 지우지 않는다</b> — 면제된 종목은 알림이 발화할 때 이름으로 함께 적고
 * 로그가 매번 센다. 사실을 없애는 것이 아니라 <b>OK 문장으로 옮기는 것</b>이며, 그 형태는
 * ADR-0044 §펜스가 정했다: <i>"상시 경보는 읽는 쪽을 훈련시켜 새 결손까지 함께 놓치게 만든다."</i>
 *
 * <p><b>MUST NOT: 여기에 「지금은 못 고치니까」를 넣지 않는다.</b> 면제의 자격은
 * <b>「돌려도 안 닫힌다」</b> 하나다. 고칠 수 있는데 안 고친 것은 알림으로 남아야 한다 —
 * 그것을 면제로 옮기는 순간 이 기제는 자기가 감시하려던 침묵을 스스로 만든다.
 */
public final class KnownSourceGaps {

    /**
     * 업종 축의 면제 — SEC {@code submissions}가 {@code sic}을 <b>빈 값으로 주는</b> 종목.
     *
     * <p>{@code company-profile}은 이들을 매 실행 대상으로 잡고 매번 빈 값을 받는다. 즉
     * <b>어떤 잡으로도 안 채워진다</b>(ADR-0099 실측).
     *
     * <p><b>IMPORTANT — 이 배열은 {@code docs/adr/ADR-0044-sector-axis.md} §검증의 펜스와 같은
     * 사본이고 {@code check-doc-staleness.sh}의 거울 표가 둘을 대조한다.</b> 한쪽만 고치면 세션
     * 시작에 발화한다. 사본을 없앨 수 없는 이유는 트리거가 서로 안 닿기 때문이다 — 펜스는
     * 세션 시작에 psql로 돌고 이 상수는 배치 런타임에 돈다.
     *
     * <p><b>줄일 때도 늘릴 때도 함께 고친다.</b> ADR-0044가 <i>"그 셋이 편입에서 빠지면 목록도
     * 함께 줄인다"</i>고 정했고, 실제로 2026-09-13에 {@code MAIN}·{@code OTF}가 편입에서 빠져
     * 다섯에서 셋이 됐다.
     */
    public static final String KNOWN_MISSING_SIC =
            "s.ticker = ANY (ARRAY['ARCC','CBC','OBDC'])";

    /**
     * 재무 두 축(재무 0행 · 영업활동현금흐름 0행)의 면제 — <b>인프라 투자회사</b>.
     *
     * <p>맥쿼리인프라({@code 088980} · KSIC {@code 64201})와 KB발해인프라({@code 415640} ·
     * {@code 642})가 여기다. 둘 다 재무제표를 우리 소스가 주지 않는다.
     *
     * <p><b>티커 목록이 아니라 업종 키로 거는 이유</b>는 이 집합이 <b>실제로 자라기 때문</b>이다 —
     * 목록이면 새 인프라 펀드가 편입될 때마다 사람이 고쳐야 하고, 안 고치면 상시 경보가 된다.
     *
     * <p><b>MUST NOT: 중분류 {@code KSIC:64}로 올리지 않는다.</b> 올리면 편입 36종목이 한꺼번에
     * 면제된다(2026-09-13 실측) — 64에는 재무가 있어야 정상인 사업지주가 섞여 있고, ADR-0044가
     * 같은 이유로 Altman Z 제외에서도 64를 뺐다.
     *
     * <p><b>근거가 n=2라는 것을 알고 쓴다.</b> 지금 편입 {@code KSIC:642}는 1종목이고 그 하나가
     * 재무 0행이다. <b>{@code KSIC:642}인데 재무가 있는 종목이 생기면 이 면제를 다시 연다.</b>
     */
    public static final String INFRA_FUND =
            "s.industry_group_key = 'KSIC:642'";

    /**
     * 재무 두 축의 면제 — <b>재무 통화가 시장 통화와 다른 종목</b>(ADR-0094).
     *
     * <p>{@code IndicatorDataReader.CURRENCY_GATE}가 그 종목의 재무를 <b>종목 단위로 통째로 안
     * 준다.</b> 그래서 <b>재무를 채워도 지표가 안 난다</b> — 감시가 이것을 결손으로 신고하면
     * 본문에 적을 「닫는 명령」이 원리적으로 닫지 못하고, 알림은 7일마다 영원히 반복된다.
     *
     * <p>2026-09-13 실측으로 편입 셋이다 — {@code 950260}(인제니아테라퓨틱스 Reg.S · USD) ·
     * {@code 950160}(코오롱티슈진 · USD) · {@code 241560}(두산밥캣 · KRW+USD 혼재).
     * <b>{@code 950260}이 영업활동현금흐름 축의 유일한 「실제」 건이었다.</b>
     *
     * <p><b>MUST: 게이트와 같은 술어를 쓴다 — 「USD인가」가 아니라 「시장 통화와 같은가」다.</b>
     * 통화 리터럴로 바꾸면 미국에서 전 종목이 면제된다.
     */
    public static final String FINANCIAL_CURRENCY_MISMATCH = """
            EXISTS (
                SELECT 1 FROM financial_statement mismatched
                JOIN market gate_market ON gate_market.code = s.market_code
                WHERE mismatched.stock_id = s.id
                  AND mismatched.currency_code <> gate_market.currency_code)
            """;

    private KnownSourceGaps() {
    }
}
