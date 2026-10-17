package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;

/**
 * 외부 소스에서 한 종목을 식별하는 데 필요한 최소 정보. {@code stock} 행의 축약이다.
 *
 * <p>엔티티가 아니라 이 record를 포트에 넘기는 이유는 두 가지다. 어댑터가 JPA 영속성 컨텍스트에
 * 닿을 이유가 없고, 어댑터 테스트가 DB 없이 성립해야 하기 때문이다.
 *
 * @param stockId    실패 기록과 upsert의 대상. 티커가 아니라 이것이 내부 식별자다.
 * @param marketCode {@code DataSourceRegistry}의 라우팅 키.
 * @param ticker     MUST: 문자열이다. 한국 티커 '005930'을 정수로 다루면 5930이 된다. 미국에서는 <b>지금 보이는 표지</b>일 뿐이고
 *                   소스에 물을 신원은 {@code identity}다(ADR-0180).
 * @param identity   미국 행의 저장된 신원. 국내와 시험의 3인자 생성은 {@link Identity#NONE}이다 — 그때 어댑터는 지금까지처럼
 *                   {@code ticker}로 묻는다.
 */
public record SecurityRef(long stockId, String marketCode, String ticker, Identity identity) {

    public SecurityRef {
        if (marketCode == null || marketCode.isBlank()) {
            throw new IllegalArgumentException("marketCode가 비어 있다. stockId=" + stockId);
        }
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker가 비어 있다. stockId=" + stockId);
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity가 null이다 — 신원이 없으면 Identity.NONE. stockId=" + stockId);
        }
    }

    public SecurityRef(long stockId, String marketCode, String ticker) {
        this(stockId, marketCode, ticker, Identity.NONE);
    }

    /**
     * 미국 행의 신원(ADR-0180) — 실체 닻 · 등록인 · 등록인 시대 · 이 행이 이름을 바꿔 오기 전의 기호 시대.
     *
     * @param anchorSymbol 이 실체를 푸는 Alpaca 기호. {@code null}이면 닻이 없다 — 어댑터가 {@code ticker}로 기본 asof에 묻는다
     * @param anchorDate   닻 기호가 이 실체를 가리키던 날. {@code anchorSymbol}과 함께 null이거나 함께 있다
     * @param secCik       포함 검사로 검증된 등록인. {@code null}이면 오늘 SEC 티커 파일로 푼다
     * @param eraFrom      등록인 시대의 시작(포함). {@code null}이면 행의 시작부터
     * @param eraTo        등록인 시대의 끝(제외). {@code null}이면 열려 있다
     * @param recordedEras {@code stock_symbol_era}의 줄 — 이 행의 옛 기호들(오래된 순). 소비자는 이것과 원장 걷기의 합집합을 쓴다
     */
    public record Identity(String anchorSymbol, LocalDate anchorDate, Long secCik, LocalDate eraFrom,
                           LocalDate eraTo, List<SymbolEra> recordedEras) {

        public static final Identity NONE = new Identity(null, null, null, null, null, List.of());

        public Identity {
            if ((anchorSymbol == null) != (anchorDate == null)) {
                throw new IllegalArgumentException("닻 기호와 닻 날짜는 함께 있거나 함께 없다: " + anchorSymbol + "@" + anchorDate);
            }
            recordedEras = recordedEras == null ? List.of() : List.copyOf(recordedEras);
        }
    }

    /** 이 행이 {@code symbol}이던 시절 — {@code validFrom} 포함(모르면 null) · {@code validTo} 제외. */
    public record SymbolEra(String symbol, LocalDate validFrom, LocalDate validTo) {
        public SymbolEra {
            if (symbol == null || symbol.isBlank() || validTo == null) {
                throw new IllegalArgumentException("기호 시대는 기호와 끝 날짜가 있어야 한다: " + symbol + " ~" + validTo);
            }
        }
    }

    /** 비운 표지의 구분자 — 기호에 안 나오는 글자라 비운 표지는 어떤 오늘 기호와도 안 맞는다(ADR-0180). */
    public static final char VACATED = '~';

    /**
     * 시대가 닫힌 행의 표지 — 그 기호를 그날까지 쓴 행. 기호 + 마지막 날이라 다시 비워도 겹치지 않는다(varchar 20 안 · 최장 기호 6 + 11).
     * 닫힌 행이 기호를 쥐고 있으면 그 기호를 새로 받은 실체가 표지로 그 행에 붙는다 — 그래서 시대를 닫는 쓰기는 언제나 비운다.
     */
    public static String vacatedLabel(String symbol, LocalDate lastDay) {
        return symbol + VACATED + lastDay;
    }

    /**
     * 이 행이 마지막으로 쓴 기호 — 열린 행은 표지 그대로, 닫힌 행은 비운 표지에서 되찾는다. 기업 행위·배당처럼 <b>그때의 기호</b>로
     * 묻는 소스에 쓴다. 닫힌 행은 {@link #currentSymbolUntil()}까지만 이 기호다 — 그 뒤의 같은 기호는 다른 실체일 수 있다.
     */
    public String currentSymbol() {
        int cut = ticker.indexOf(VACATED);
        return cut < 0 ? ticker : ticker.substring(0, cut);
    }

    /** {@link #currentSymbol()}의 끝(제외) — 시대 끝. 열린 행은 {@code null}. */
    public LocalDate currentSymbolUntil() {
        return identity.eraTo();
    }

    /** Alpaca 봉에 물을 기호 — 닻이 있으면 닻 기호, 없으면 표지. */
    public String barSymbol() {
        return identity.anchorSymbol() != null ? identity.anchorSymbol() : ticker;
    }

    /** Alpaca 봉의 {@code asof} — 닻이 없으면 {@code null}(소스 기본 = 오늘 그 기호의 실체). */
    public LocalDate barAsOf() {
        return identity.anchorDate();
    }
}
