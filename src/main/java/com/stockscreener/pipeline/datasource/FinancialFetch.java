package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 한 종목의 재무 수집 결과. <b>보고서와 「못 얻은 컬럼」을 함께 낸다.</b>
 *
 * <p><b>왜 반환형을 넓혔는가</b>: 부재는 <b>기간이 아니라 컬럼</b>의 성질이라
 * {@link RawFinancialReport} 안에 담을 자리가 없다. 거버넌스는 같은 문제를 <b>원자료 표 안의
 * 센티널 행</b>으로 풀었지만({@code GovernanceReportRow.noData} · ADR-0081 §규칙
 * <i>"소스가 「데이터 없음」이라 답한 것도 행으로 남긴다"</i>) 그쪽은 부재가 <b>행</b> 단위라
 * 가능했다.
 *
 * <p><b>MUST: 부재를 안 내는 소스는 빈 목록을 낸다 — {@code null}이 아니다.</b> 빈 목록은
 * 「이 소스는 그 축을 안 본다」이고, 그때 저장 계층이 <b>기존 행을 지우지 않는다.</b>
 * 「모른다」와 「없다」를 가르는 것이 이 축의 존재 이유다.
 */
public record FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                             boolean completeVintageHistory, Set<LocalDate> unconfirmedVintages,
                             Set<LocalDate> supplementedVintages, boolean vintagesUncertain,
                             List<DataSourceException.Kind> unfetchedSupplements, FilingTrace filingTrace,
                             List<DataSourceException.Kind> unfetchedClassFacts, Registrant registrant) {

    public FinancialFetch {
        filingTrace = filingTrace == null ? FilingTrace.NONE : filingTrace;
        reports = reports == null ? List.of() : List.copyOf(reports);
        absences = absences == null ? List.of() : List.copyOf(absences);
        unconfirmedVintages = unconfirmedVintages == null ? Set.of() : Set.copyOf(unconfirmedVintages);
        supplementedVintages = supplementedVintages == null ? Set.of() : Set.copyOf(supplementedVintages);
        unfetchedSupplements = unfetchedSupplements == null ? List.of() : List.copyOf(unfetchedSupplements);
        unfetchedClassFacts = unfetchedClassFacts == null ? List.of() : List.copyOf(unfetchedClassFacts);
    }

    /** 등록인을 안 내는 소스용(국내 · 시험). */
    public FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                          boolean completeVintageHistory, Set<LocalDate> unconfirmedVintages,
                          Set<LocalDate> supplementedVintages, boolean vintagesUncertain,
                          List<DataSourceException.Kind> unfetchedSupplements, FilingTrace filingTrace,
                          List<DataSourceException.Kind> unfetchedClassFacts) {
        this(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages, vintagesUncertain,
                unfetchedSupplements, filingTrace, unfetchedClassFacts, null);
    }

    /** 클래스 축 재료를 안 받는 소스·갈래용(관문 꺼짐 포함). */
    public FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                          boolean completeVintageHistory, Set<LocalDate> unconfirmedVintages,
                          Set<LocalDate> supplementedVintages, boolean vintagesUncertain,
                          List<DataSourceException.Kind> unfetchedSupplements, FilingTrace filingTrace) {
        this(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages, vintagesUncertain,
                unfetchedSupplements, filingTrace, List.of());
    }

    /** 공시 근거({@link FilingTrace})를 안 내는 소스·시험용. */
    public FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                          boolean completeVintageHistory, Set<LocalDate> unconfirmedVintages,
                          Set<LocalDate> supplementedVintages, boolean vintagesUncertain,
                          List<DataSourceException.Kind> unfetchedSupplements) {
        this(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages, vintagesUncertain,
                unfetchedSupplements, FilingTrace.NONE);
    }

    /** 보충 층이 없는 소스용 — 받지 못한 보충이 없다. */
    public FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                          boolean completeVintageHistory, Set<LocalDate> unconfirmedVintages,
                          Set<LocalDate> supplementedVintages, boolean vintagesUncertain) {
        this(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages, vintagesUncertain,
                List.of());
    }

    /** 판 이력의 빈칸을 모르는 소스용 — 아래 셋이 비어 있다. */
    public FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                          boolean completeVintageHistory) {
        this(reports, absences, completeVintageHistory, Set.of(), Set.of(), false);
    }

    /**
     * <b>이 호출이 그 종목의 판 이력을 전부 냈는가.</b> 참이면 저장 계층이
     * 「여기 없는 공시일은 더 이상 그 라벨의 판이 아니다」로 읽어도 된다.
     *
     * <p><b>MUST: 소스마다 다시 묻는다 — 이식하면 이력을 파괴한다.</b>
     * SEC는 참이다 — 다만 {@code companyfacts} 혼자서가 아니다. companyfacts는 정기 공시 일부의 us-gaap를 통째로
     * 안 싣고(2026-10-06 83 CIK 표본 87건), 그 공시를 인스턴스로 보충한 트리가 판 이력 전부다(ADR-0173). 보충이 못 실은
     * 공시는 {@link #unconfirmedVintages()}로 따로 낸다 — 그것까지가 이 참의 조건이다.
     * DART는 {@code (corp_code, 연도, 보고서코드)}마다 <b>최신본 하나만</b> 내므로 거짓이다 —
     * 실측(2026-09-16)으로 국내에 공시일이 둘 이상인 라벨이 <b>54 · 26종목</b>이고
     * 전부 {@code revision_no} 0과 1을 가진 <b>진짜 정정공시</b>다. 참으로 읽으면
     * 그 원본 판이 지워지고 <b>DART는 그것을 다시 주지 않는다.</b>
     *
     * <p>기본값이 거짓인 것도 그 때문이다 — 선언하지 않은 소스는 안전한 쪽에 선다.
     */
    public boolean completeVintageHistory() {
        return completeVintageHistory;
    }

    /**
     * <b>이 호출이 못 실은 공시의 제출일.</b> 판 이력을 전부 내는 소스라도 공시 하나를 못 받았으면 그 공시의 판은 출력에
     * 없다 — 그때 「여기 없는 공시일」로 회수하면 멀쩡한 판이 지워진다. 저장 계층은 이 날짜의 행을 회수에서 지킨다
     * (SEC 공시 보충 · ADR-0173).
     */
    public Set<LocalDate> unconfirmedVintages() {
        return unconfirmedVintages;
    }

    /**
     * <b>집계 출처가 빠뜨려 보충으로 실은 공시의 제출일.</b> 그 판은 원장이 처음부터 준 판이라, 같은 라벨에 늦은 판이 이미
     * 있어도 저장 계층이 이른 판으로 넣는다 — 보충의 성공·실패 순서나 배포 시점의 저장 상태에 무관하게 같은 판 집합에 닿게
     * (SEC 공시 보충 · ADR-0173).
     */
    public Set<LocalDate> supplementedVintages() {
        return supplementedVintages;
    }

    /**
     * <b>못 실은 공시가 무엇인지조차 모른다</b>(공시 목록을 못 받았다). 참이면 저장 계층이 그 종목의 회수를 통째로 멈춘다.
     */
    public boolean vintagesUncertain() {
        return vintagesUncertain;
    }

    /**
     * <b>이 호출이 받지 못한 보충의 종류</b>(SEC 공시 보충 — 받기 실패 · 목록 실패 · 해석 실패는 빼고). 종목은 성공으로 끝나지만
     * 그 시장은 이번 실행에서 끝까지 받지 못했다 — 잡이 실행 안 시장 판정에 싣는다(ADR-0174).
     */
    public List<DataSourceException.Kind> unfetchedSupplements() {
        return unfetchedSupplements;
    }

    /**
     * <b>이 호출이 원장 응답에서 본 공시 번호</b> — 공시 소비의 근거다(ADR-0176 2차 · {@link FilingTrace}). 보고서·저장에는
     * 아무 영향이 없다.
     */
    public FilingTrace filingTrace() {
        return filingTrace;
    }

    /**
     * <b>이 호출이 받지 못한 클래스 축 인스턴스의 종류</b>(관문 뒤 SEC 클래스 축 · ADR-0179 — 해석 실패는 빼고). 보충과 같은 뜻의
     * 다른 층이다 — 종목은 성공으로 끝나지만 그 시장은 이번 실행에서 끝까지 받지 못했다. 잡이 시장 판정에 「클래스 축」 층으로 싣는다.
     */
    public List<DataSourceException.Kind> unfetchedClassFacts() {
        return unfetchedClassFacts;
    }

    /** 같은 결과에 공시 근거를 싣는다. */
    public FinancialFetch withFilingTrace(FilingTrace trace) {
        return new FinancialFetch(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages,
                vintagesUncertain, unfetchedSupplements, trace, unfetchedClassFacts, registrant);
    }

    /** 같은 결과에 받지 못한 클래스 축 인스턴스를 싣는다. */
    public FinancialFetch withUnfetchedClassFacts(List<DataSourceException.Kind> kinds) {
        return new FinancialFetch(reports, absences, completeVintageHistory, unconfirmedVintages, supplementedVintages,
                vintagesUncertain, unfetchedSupplements, filingTrace, kinds, registrant);
    }

    /**
     * 이 수집이 물은 등록인(미국 SEC CIK · ADR-0180)과 그 등록인 트리(보충 뒤)가 싣는 접수번호 전부. 국내·시험은 {@code null}이다.
     * 수집기가 행에 등록인이 없을 때 포함 검사(저장 접수번호 ⊂ 이 집합 ∪ 같은 CIK 보충 표)로 묶을지 · 쓸지를 가른다.
     */
    public Registrant registrant() {
        return registrant;
    }

    /** @param accessions 그 등록인 companyfacts(보충을 실은 트리)의 접수번호 전부 */
    public record Registrant(long cik, Set<String> accessions) {
        public Registrant {
            accessions = Set.copyOf(accessions);
        }
    }

    /** 부재 축을 안 보고 판 이력도 전부 내지 않는 소스용. */
    public static FinancialFetch of(List<RawFinancialReport> reports) {
        return new FinancialFetch(reports, List.of(), false);
    }
}
