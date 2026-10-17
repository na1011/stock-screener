package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 미국 종목 행의 신원 판정 재료(ADR-0180). 신원 판정({@code UsIdentityTriage})이 리밸런싱 앞에서 한 번 묻는다.
 *
 * <p>벤더 지식은 구현이 든다 — 판정은 「이 두 봉 계열이 같은 실체인가」 · 「이 기호의 등록인이 누구인가」 · 「그 교체에 날짜 박힌 증거가
 * 있는가」만 묻는다.
 */
public interface IdentitySource {

    /** 오늘의 상장 목록(NYSE·NASDAQ 활성). 신원 판정의 「지금 그 기호의 실체」 쪽 모집단이다. */
    List<Listed> listedSecurities() throws DataSourceException;

    /** 이 실행이 쓸 등록인 파일 한 판 — 실행 안에서 판 하나를 잡고 쓴다(낡은 판은 던진다). */
    RegistrantFile registrantFile() throws DataSourceException;

    /**
     * 기호마다 일봉 계열. 응답 키는 요청 기호다.
     *
     * @param asOf {@link BarAsOf#anchor} — 그날 그 기호가 가리키던 실체 · {@link BarAsOf#TODAY} — 오늘 그 기호의 실체(소스 기본) ·
     *             {@link BarAsOf#LITERAL} — 그 문자열로 거래된 봉만(실체를 안 따라간다 — 개명일을 읽는 데 쓴다)
     * @return 봉이 없는 기호는 키가 없다
     */
    Map<String, List<EntityBar>> bars(Collection<String> symbols, BarAsOf asOf, LocalDate from, LocalDate to)
            throws DataSourceException;

    /** 등록인 companyfacts가 싣는 접수번호 전부. 그 등록인이 XBRL을 낸 적이 없으면(404) 빈 집합이다. */
    Set<String> registrantAccessions(long cik) throws DataSourceException;

    /** 등록인이 {@code since} 이후 낸 공시(양식 · 제출일). */
    List<RegistrantFiling> registrantFilings(long cik, LocalDate since) throws DataSourceException;

    /** 기호들이 걸린 신원 사건 — 개명(CUSIP·기호 바뀜) · 재편 · 피인수 합병의 인수 쪽. 날짜가 효력일이다. */
    List<IdentityEvent> identityEvents(Collection<String> symbols, LocalDate from, LocalDate to) throws DataSourceException;

    record Listed(String symbol, String name, String exchange) {
    }

    /** 등록인 파일 한 판 — 기호 → CIK. */
    interface RegistrantFile {
        Optional<Long> cikOf(String symbol);
    }

    /** 봉 하나 — 같은 실체의 판정은 (종가 · 거래량 · 체결 수) 셋이 다 같은지로 한다. */
    record EntityBar(LocalDate date, BigDecimal close, long volume, long trades) {
        public boolean sameAs(EntityBar other) {
            return date.equals(other.date) && close.compareTo(other.close) == 0 && volume == other.volume
                    && trades == other.trades;
        }
    }

    /** 봉의 실체 기준. */
    record BarAsOf(LocalDate date, boolean literal) {
        public static final BarAsOf TODAY = new BarAsOf(null, false);
        public static final BarAsOf LITERAL = new BarAsOf(null, true);

        public static BarAsOf anchor(LocalDate date) {
            return new BarAsOf(date, false);
        }
    }

    record RegistrantFiling(String form, LocalDate filed) {
    }

    /**
     * @param type    {@code name_change} · {@code reorganization} · {@code stock_merger}
     * @param symbols 사건이 걸린 기호(옛·새 · 재편 대상 · 인수 쪽)
     */
    record IdentityEvent(String type, LocalDate date, Set<String> symbols, String detail) {
    }
}
