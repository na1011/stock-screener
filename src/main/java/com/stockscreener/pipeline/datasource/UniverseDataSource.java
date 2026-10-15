package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.Set;

/**
 * 지수 편입 종목 소스.
 *
 * <p>IMPORTANT — 세 포트 중 가장 깨지기 쉽다. 코스피200·코스닥150 구성종목을 주는 공식 무료
 * API가 확인되지 않았고(KRX OPEN API 서비스 목록에 없으며, 과거 구성종목은 유료 판매 항목이다),
 * S&amp;P500은 위키피디아 스크래핑이 사실상의 표준이다. spec §7이 인용한 "국내 거래소 정보
 * 시스템의 회원제 전환" 전례가 정확히 이 계열이다.
 *
 * <p>그래서 수동 시드 구현을 항상 폴백으로 유지한다. 유니버스가 없으면 다른 어떤 배치도
 * 대상 종목을 정하지 못하므로, 이것이 파이프라인 전체의 병목이다.
 *
 * <p>MUST NOT: 스크래핑 실패 시 시드로 <b>조용히</b> 폴백하지 않는다. 폴백은 반드시 알림을
 * 동반한다 — 조용한 폴백은 "6개월 전 유니버스로 스크리닝하고 있었다"는 결과를 만들고,
 * 그 상태는 아무 에러도 내지 않는다.
 */
public interface UniverseDataSource {

    /**
     * 이 소스가 담당하는 지수 코드. {@code universe.code}의 값을 쓴다 —
     * {@code KOSPI200}, {@code KOSDAQ150}, {@code SP500}.
     */
    Set<String> supportedUniverseCodes();

    /** 실패 기록과 로그에 남는 소스 이름. 시드와 스크래핑을 구분할 수 있어야 한다. */
    String sourceName();

    /**
     * 기준일 시점의 편입 종목 <b>전체</b> 스냅샷.
     *
     * <p>MUST: 부분 목록을 내지 않는다. 불완전하다고 판단되면 {@link DataSourceException}을
     * 던진다. 리밸런싱이 "목록에 없으면 제외"로 동작하므로, 부분 목록은 대량 오탈락이 된다.
     */
    UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf)
            throws DataSourceException;

    /**
     * 앞 편입을 아는 판 — 미국 시총 상위가 주식수 클래스 축의 편입 보류에 쓴다(관문 뒤 · 보류는 앞 편입에만 선다). 기본은 앞
     * 편입을 안 쓴다(다른 소스의 계약 불변).
     *
     * @param previous 이 리밸런싱 직전의 편입(복원은 앞 격자의 결과 · 복원 구간 첫 격자는 빈 집합)
     */
    default UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf, Set<String> previous)
            throws DataSourceException {
        return fetchConstituents(universeCode, asOf);
    }
}
