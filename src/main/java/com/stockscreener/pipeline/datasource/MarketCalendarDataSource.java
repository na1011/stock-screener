package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 거래소 세션 달력의 원자료 포트(ADR-0091).
 *
 * <p><b>라우팅 키가 시장이 아니라 거래소다.</b> 미국은 {@code market_code}가 {@code US} 하나인데
 * 그 안에 NYSE·NASDAQ이 있고, 소스가 둘을 따로 조회하게 해 두었다. 시장으로 라우팅하면 그 둘을
 * 합쳐야 하는데 <b>합칠 근거가 없다</b> — 일정이 같다는 것은 2024년 실측이지 계약이 아니다.
 * {@code MarketIndexDataSource}가 지수 코드로 라우팅하는 것과 같은 자리다.
 *
 * <p>기존 포트에 얹지 않은 이유는 <b>질의 단위와 실패 단위가 둘 다 다르기</b> 때문이다.
 * 시세·재무·기업행위는 종목당 한 번 묻고 종목 단위로 실패하지만, 이쪽은 거래소당 한 번 묻고
 * 거래소 단위로 실패한다 — 종목 하나가 아니라 <b>그 거래소 전 종목의 판정</b>이 함께 비어 있게
 * 된다.
 */
public interface MarketCalendarDataSource {

    /**
     * 이 어댑터가 답할 수 있는 거래소 코드. {@code stock.listing_exchange}와 같은 값 집합이어야
     * 조인이 성립한다 — 어긋나면 조회가 0행이고 그 0행은 「그날 휴장」과 구별되지 않는다.
     */
    Set<String> supportedExchangeCodes();

    /** 실패를 기록할 때 남길 소스 이름. */
    String sourceName();

    /**
     * {@code [from, to]} 구간에서 그 거래소가 정규장을 연 날.
     *
     * <p><b>빈 목록이 정상이다</b> — 휴일만 든 구간이 실재한다. 그것을 오류로 접으면 「그 구간에
     * 세션이 없다」와 「받지 못했다」가 같은 모양이 된다.
     *
     * <p>MUST NOT: 부분 결과를 반환하지 않는다. 중간에 실패하면 예외이고, 잘린 목록을 돌려주면
     * 호출자가 그것을 완전한 구간으로 저장해 <b>빠진 날이 휴장으로 굳는다.</b>
     */
    List<MarketSession> fetchSessions(String exchangeCode, LocalDate from, LocalDate to)
            throws DataSourceException;
}
