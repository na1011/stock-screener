package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 국내 재무 행 하나(대차대조표 날짜 하나)의 <b>보통주 자본 재료</b> — 보통주 자본 = (지배 자본 − H) × c(ADR-0199). 외부 호출이 없는 순수 함수다.
 * 손익 앵커({@link KrCommonIncome})와 따로 판정한다 — 대차대조표는 손익이 못 정한 판에서도 선다.
 *
 * <p>원칙 둘이다. ① <b>지배기업 자신의 청구권은 지배기업 자신의 재무제표(별도)가 말한다</b> — 연결의 신종자본증권은 자회사 것을 섞는다
 * (한화에어로 2,332.8B는 한화오션 것 · 롯데지주 연결 772.1B 대 별도 623.1B). ② <b>보통주 몫으로 나누는 것은 원장이 그 종류를 보통주와 함께
 * 이익에 참가하는 지분으로 실을 때(2계층 EPS)뿐이다</b> — 부채로 실은 종류(상환전환우선주)는 자본 밖이고, 둘 다 아니면 원장이 그 종류의 청구권을
 * 안 준다(못 정함).
 */
public final class KrCommonEquity {

    private static final String HYBRID = "HybridBonds";
    private static final String HYBRID_MEMBER = "HybridBondsMember";
    private static final String EQUITY = "Equity";
    private static final String COMPONENTS = "ComponentsOfEquityAxis";
    private static final String CARRYING_AXIS = "CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis";
    private static final String REPORTED = "ReportedAmountMember";
    private static final String RCPS_LIABILITY = "ConvertibleRedeemablePreferredStockLiabilities";
    private static final String OWNERS = "EquityAttributableToOwnersOfParent";
    private static final String OWNERS_MEMBER = "EquityAttributableToOwnersOfParentMember";
    /**
     * 보통주 밖 비중(명부 발행 기준)이 이 안이면 그 종류의 청구권이 무엇이든 보통주 자본은 [(지배 자본 − H) × c, 지배 자본 − H] 안이라 c로 정한다 —
     * 미국 {@code CommonEquity.TOLERANCE}(이웃 값이 보통주 자본의 2% 안이면 정함 · {@code immat})와 같은 값 · 같은 근거다.
     */
    static final BigDecimal IMMATERIAL = new BigDecimal("0.02");

    private KrCommonEquity() {
    }

    /**
     * 판정 결과 — {@code branch} {@code kr}(정함) · {@code kr-s}(정함 · 총자본이 곧 지배 자본 — 별도재무제표만 낸 판이거나, 그 판 연결 자본변동표의
     * 지배 성분이 축 없는 자본총계와 같다 · 비지배 0) · 못 정함(두 값 null) — {@code kr-nokey}(판 재료 없음) · {@code kr-open-h}(신종자본증권) ·
     * {@code kr-open-c}(보통주 몫).
     */
    public record Equity(String branch, BigDecimal hybrid, BigDecimal fraction) {

        /** 그 판 재료가 없다. */
        public static final Equity OPEN = new Equity("kr-nokey", null, null);
        /** 신종자본증권 지배 몫을 못 정함. */
        static final Equity OPEN_HYBRID = new Equity("kr-open-h", null, null);
        /** 보통주 몫을 못 정함. */
        static final Equity OPEN_FRACTION = new Equity("kr-open-c", null, null);
    }

    /**
     * 발행사 수준의 재료 — 그 발행사 판 전부에서 한 번 짓는다.
     *
     * @param pairs    연결 신종자본증권 값 → 같은 판 · 같은 날짜의 별도 값(별도로 확인된 짝 · 가장 늦은 날짜의 것). 장부가는 발행 · 상환으로만
     *                 움직이므로, 별도 태그가 빠진 판(HL만도 2026 분기)은 연결 값이 같으면 그 별도 값을 잇는다.
     * @param twoClassYears 2계층 EPS(DART 우선주 EPS · IFRS 참가지분상품 EPS ≠ 0)를 실은 판의 사업연도들
     */
    public record Issuer(Map<BigDecimal, BigDecimal> pairs, Set<Integer> twoClassYears, TreeMap<LocalDate, BigDecimal> confirmed,
                         LocalDate firstEvidence) {

        /** {@code day} 앞에서 별도로 확인된 마지막 H — 없으면 {@code null}. */
        BigDecimal confirmedBefore(LocalDate day) {
            Map.Entry<LocalDate, BigDecimal> e = confirmed.lowerEntry(day);
            return e == null ? null : e.getValue();
        }

        /**
         * 종류 구성(명부의 종류 수)이 {@code reference}와 같은 해의 판이 2계층 EPS를 실었는가 — 같은 종류에 대한 증거만 받는다(구성이 다른 해의
         * 증거는 뒤에 새로 낸 종류를 덮는다). 2024 앞 판은 우선주 EPS를 회사 확장 개념으로 실은 발행사가 많아(ADR-0192 no-eps) 그 해 증거만 보면
         * 같은 종류의 앞 해가 못 정함이 된다.
         */
        boolean twoClass(Register reference, Map<Integer, Register> registers) {
            for (int year : twoClassYears) {
                Register r = registers.get(year);
                if (r == null) {
                    r = registers.get(year - 1);
                }
                if (r != null && r.classCount() == reference.classCount()) {
                    return true;
                }
            }
            return false;
        }

        /** @param filings 판마다 (사업연도, 사실) — 사실이 {@code null}인 판은 건너뛴다 */
        public static Issuer of(Collection<Map.Entry<Integer, List<Fact>>> filings) {
            Map<BigDecimal, LocalDate> pairDay = new HashMap<>();
            Map<BigDecimal, BigDecimal> pairs = new HashMap<>();
            Set<Integer> twoClassYears = new java.util.HashSet<>();
            TreeMap<LocalDate, BigDecimal> confirmed = new TreeMap<>();
            LocalDate firstEvidence = null;
            for (Map.Entry<Integer, List<Fact>> filing : filings) {
                List<Fact> facts = filing.getValue();
                if (facts == null) {
                    continue;
                }
                Set<Basis> own = ownBases(facts);
                Set<Basis> consolidated = consolidatedBases(facts);
                Map<LocalDate, BigDecimal> separateByDay = new TreeMap<>();
                Map<LocalDate, BigDecimal> consolidatedByDay = new TreeMap<>();
                for (Fact f : facts) {
                    if (preferredEps(f) && filing.getKey() != null) {
                        twoClassYears.add(filing.getKey());
                    }
                    if (f.source() == Source.DART && f.concept().contains("Hybrid") && f.value().signum() != 0) {
                        LocalDate seen = f.instant() ? f.end() : f.start();
                        if (seen != null && (firstEvidence == null || seen.isBefore(firstEvidence))) {
                            firstEvidence = seen;
                        }
                    }
                    if (f.instant() && hybrid(f)) {
                        if (own.contains(f.basis())) {
                            separateByDay.putIfAbsent(f.end(), f.value());
                        }
                        if (consolidated != null && consolidated.contains(f.basis())) {
                            consolidatedByDay.putIfAbsent(f.end(), f.value());
                        }
                    }
                }
                separateByDay.forEach(confirmed::putIfAbsent);
                consolidatedByDay.forEach((day, value) -> {
                    BigDecimal separate = separateByDay.get(day);
                    if (separate == null || value.signum() == 0) {
                        return;
                    }
                    BigDecimal key = normalized(value);
                    LocalDate seen = pairDay.get(key);
                    if (seen == null || day.isAfter(seen)) {
                        pairDay.put(key, day);
                        pairs.put(key, separate);
                    }
                });
            }
            return new Issuer(pairs, twoClassYears, confirmed, firstEvidence);
        }
    }

    /**
     * 대차대조표 날짜 {@code day}의 판정.
     *
     * @param facts  그 행에 묶인 판의 인스턴스 사실 — {@code null}이면 그 판 재료가 없다(못 정함)
     * @param registers 사업연도 → 명부(없는 해는 빠진다)
     */
    public static Equity judge(LocalDate day, int fiscalYear, List<Fact> facts, Issuer issuer, Map<Integer, Register> registers) {
        if (facts == null) {
            return Equity.OPEN;
        }
        BigDecimal hybrid = hybrid(day, facts, issuer);
        if (hybrid == null) {
            return Equity.OPEN_HYBRID;
        }
        BigDecimal fraction = fraction(day, fiscalYear, facts, issuer, registers);
        if (fraction == null) {
            return Equity.OPEN_FRACTION;
        }
        return new Equity(totalIsControlling(day, facts) ? "kr-s" : "kr", hybrid, fraction);
    }

    /**
     * 총자본이 곧 지배 자본인가 — 별도재무제표만 낸 판이거나, 그 날짜 연결 지배 자본(표준 귀속 줄 · 자본변동표 지배 성분)이 축 없는 연결 자본총계와
     * 같다(비지배 0). 행의 지배 자본 칸이 비어도 원장이 그 값을 준 것이다(코리안리 2023 3분기 — DART 재무상태표 응답에 지배 줄이 없다).
     */
    static boolean totalIsControlling(LocalDate day, List<Fact> facts) {
        Set<Basis> consolidated = consolidatedBases(facts);
        if (consolidated == null) {
            return true;
        }
        BigDecimal total = null;
        BigDecimal owners = null;
        for (Fact f : facts) {
            if (f.source() != Source.IFRS || !f.instant() || !day.equals(f.end()) || !consolidated.contains(f.basis())) {
                continue;
            }
            if (EQUITY.equals(f.concept()) && f.members().isEmpty()) {
                total = f.value();
            } else if (OWNERS.equals(f.concept()) && f.members().isEmpty()
                    || EQUITY.equals(f.concept()) && f.members().size() == 1 && COMPONENTS.equals(f.members().get(0).axis())
                    && OWNERS_MEMBER.equals(f.members().get(0).member()) && f.members().get(0).source() != Source.ENTITY) {
                owners = f.value();
            }
        }
        return total != null && owners != null && total.compareTo(owners) == 0;
    }

    /** H — 지배기업이 낸 신종자본증권. {@code null}이면 못 정함. */
    static BigDecimal hybrid(LocalDate day, List<Fact> facts, Issuer issuer) {
        Set<Basis> own = ownBases(facts);
        Set<Basis> consolidated = consolidatedBases(facts);
        BigDecimal separate = hybridAt(day, facts, own);
        if (separate != null) {
            return separate;
        }
        BigDecimal group = consolidated == null ? null : hybridAt(day, facts, consolidated);
        if (group == null || group.signum() == 0) {
            return withoutBalance(day, facts, issuer, own, LocalDate.of(day.getYear(), 1, 1));
        }
        BigDecimal carried = issuer.pairs().get(normalized(group));
        if (carried != null) {
            return carried;
        }
        // 별도 재무제표가 그 날짜에 있는데 신종자본증권이 없다 — 지배기업은 신종자본증권이 없고 연결 값은 자회사 것이다(현대차 · 한화에어로).
        return separateStatementAt(day, facts, own) ? BigDecimal.ZERO : null;
    }

    /**
     * 그 판에 신종자본증권 잔액이 어느 기저에도 없다 — <b>잔액 = 기초 + 발행 − 상환</b>(장부가는 발행 · 상환으로만 움직인다). 기초는 사업연도 시작
     * 직전에 별도로 확인된 값, 그날까지 발행사에 신종자본증권 증거가 하나도 없으면 0이다. 발행 · 상환은 지배기업 자신의 자본변동표 움직임
     * ({@code IssueOfHybridBond} · {@code RepaymentOfHybridBond} 축 없음 · 사업연도 시작 ~ {@code day}). 그 판에 움직임 개념이 없으면 앞의 확인값을
     * 그대로 잇는다(에코프로비엠 2025 분기 334.6B · 롯데에너지머티리얼즈 2026) — 확인값도 없고 지배기업 자신의 신종자본증권 흐름(이자 · 배당)이
     * 있으면 못 정함, 없으면 0. 첫 발행(파크시스템스 2026 반기 106.6B — 자본잉여금에 실어 잔액 태그가 없다)이 이 식으로 선다. 기초가 0이 아니면
     * 발행 · 상환 둘 다 실린 판에서만 식을 쓴다.
     */
    private static BigDecimal withoutBalance(LocalDate day, List<Fact> facts, Issuer issuer, Set<Basis> own, LocalDate fiscalStart) {
        BigDecimal issued = movement(facts, own, fiscalStart, day, "IssueOfHybridBond");
        BigDecimal repaid = movement(facts, own, fiscalStart, day, "RepaymentOfHybridBond");
        BigDecimal before = issuer.confirmedBefore(day);
        if (issued == null && repaid == null) {
            if (before != null && before.signum() != 0) {
                return before;
            }
            boolean flows = facts.stream().anyMatch(f -> f.source() == Source.DART && f.concept().contains("Hybrid") && !f.instant()
                    && own.contains(f.basis()) && f.value().signum() != 0);
            return flows ? null : BigDecimal.ZERO;
        }
        BigDecimal opening = issuer.confirmed().get(fiscalStart.minusDays(1));
        if (opening == null) {
            if (issuer.firstEvidence() == null || !issuer.firstEvidence().isBefore(fiscalStart)) {
                opening = BigDecimal.ZERO;
            } else {
                return null;
            }
        }
        if (opening.signum() != 0 && (issued == null || repaid == null)) {
            // 기초가 있으면 두 움직임이 다 실려야 식이 닫힌다 — 한쪽이 빠진 판은 그 몫을 0으로 읽어 틀린다(SK텔레콤 FY2023 — 상환 400B를 안
            // 실어 기초 398.7B + 발행 398.5B = 797B).
            return null;
        }
        BigDecimal balance = opening.add(issued == null ? BigDecimal.ZERO : issued)
                .subtract(repaid == null ? BigDecimal.ZERO : repaid.abs());
        return balance.signum() < 0 ? null : balance;
    }

    /** 지배기업 자신의 신종자본증권 자본 움직임(축 없음 · 그 기간) — 없으면 {@code null}. */
    private static BigDecimal movement(List<Fact> facts, Set<Basis> own, LocalDate start, LocalDate end, String concept) {
        for (Fact f : facts) {
            if (f.source() == Source.DART && concept.equals(f.concept()) && !f.instant() && own.contains(f.basis())
                    && f.members().isEmpty() && start.equals(f.start()) && end.equals(f.end())) {
                return f.value();
            }
        }
        return null;
    }

    /** c — 보통주와 나란한 잔여 청구권 중 보통주 몫. {@code null}이면 못 정함. */
    static BigDecimal fraction(LocalDate day, int fiscalYear, List<Fact> facts, Issuer issuer, Map<Integer, Register> registers) {
        Register prior = registers.get(fiscalYear - 1);
        Register same = registers.get(fiscalYear);
        Register register = same != null && day.equals(same.asOf()) ? same : prior != null ? prior : same;
        if (register == null) {
            return preferenceEvidence(facts) ? null : BigDecimal.ONE;
        }
        if (!register.multiClass() || register.commonOutstanding() != null
                && register.commonOutstanding().compareTo(register.totalOutstanding()) == 0) {
            // 한 종류 · 또는 보통주 밖 종류가 전부 자기주식이다(미래에셋생명 · 로보티즈) — 나눌 몫이 없다.
            return BigDecimal.ONE;
        }
        if (rcpsLiability(day, facts)) {
            // 그 종류는 부채로 실렸다 — 지배 자본 밖이라 나눌 몫이 없다. 보통주 밖 종류가 둘 이상이면 어느 종류인지 원장이 안 가른다.
            return classes(register) == 2 ? BigDecimal.ONE : null;
        }
        if (register.totalOutstanding() == null || register.totalOutstanding().signum() <= 0 || register.commonOutstanding() == null) {
            return null;
        }
        boolean immaterial = register.nonCommonShare() != null && register.nonCommonShare().compareTo(IMMATERIAL) <= 0;
        if (!immaterial && !issuer.twoClass(register, registers)) {
            return null;
        }
        return register.commonOutstanding().divide(register.totalOutstanding(), new MathContext(18, RoundingMode.HALF_UP));
    }

    private static int classes(Register register) {
        return register.classCount();
    }

    /** 지배기업 자신의 기저 — 연결 맥락이 있는 판은 별도, 별도만 낸 판은 별도 · 맥락 없음. */
    private static Set<Basis> ownBases(List<Fact> facts) {
        return consolidatedBases(facts) == null ? Set.of(Basis.SEPARATE, Basis.NONE) : Set.of(Basis.SEPARATE);
    }

    /** 연결 기저 — 연결 맥락이 없는 판은 {@code null}. */
    private static Set<Basis> consolidatedBases(List<Fact> facts) {
        return facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED) ? Set.of(Basis.CONSOLIDATED, Basis.NONE) : null;
    }

    /** 그 날짜 · 그 기저의 신종자본증권 — 자본변동표 성분을 먼저, 없으면 재무상태표 줄. 없으면 {@code null}. */
    private static BigDecimal hybridAt(LocalDate day, List<Fact> facts, Set<Basis> bases) {
        BigDecimal line = null;
        for (Fact f : facts) {
            if (!f.instant() || !day.equals(f.end()) || !bases.contains(f.basis()) || !hybrid(f)) {
                continue;
            }
            if (EQUITY.equals(f.concept())) {
                return f.value();
            }
            if (line == null) {
                line = f.value();
            }
        }
        return line;
    }

    /** 신종자본증권 사실 — 표준 {@code dart:HybridBonds}(축 없음 · 표준 「공시금액」) · 자본변동표 성분 {@code HybridBondsMember}. */
    private static boolean hybrid(Fact f) {
        if (f.source() == Source.DART && HYBRID.equals(f.concept())) {
            return f.members().isEmpty() || f.members().size() == 1 && CARRYING_AXIS.equals(f.members().get(0).axis())
                    && REPORTED.equals(f.members().get(0).member()) && f.members().get(0).source() != Source.ENTITY;
        }
        if (f.source() == Source.IFRS && EQUITY.equals(f.concept()) && f.members().size() == 1) {
            Member m = f.members().get(0);
            return COMPONENTS.equals(m.axis()) && HYBRID_MEMBER.equals(m.member()) && m.source() != Source.ENTITY;
        }
        return false;
    }

    /** 그 날짜 · 그 기저의 재무상태표가 판에 있는가 — 축 없는 자본총계. */
    private static boolean separateStatementAt(LocalDate day, List<Fact> facts, Set<Basis> bases) {
        return facts.stream().anyMatch(f -> f.source() == Source.IFRS && EQUITY.equals(f.concept()) && f.instant()
                && day.equals(f.end()) && bases.contains(f.basis()) && f.members().isEmpty());
    }

    /** 그 날짜 지배기업 자신의 상환전환우선주 부채 ≠ 0(DART 표준 개념 이름에 그 낱말 · 유동 몫 · 상환할증금 포함). */
    private static boolean rcpsLiability(LocalDate day, List<Fact> facts) {
        Set<Basis> own = ownBases(facts);
        return facts.stream().anyMatch(f -> f.source() == Source.DART && f.concept().contains(RCPS_LIABILITY) && f.instant()
                && day.equals(f.end()) && own.contains(f.basis()) && f.value().signum() != 0
                && f.members().stream().noneMatch(m -> m.source() == Source.ENTITY));
    }

    private static boolean preferredEps(Fact f) {
        if (f.value().signum() == 0) {
            return false;
        }
        return f.source() == Source.DART && f.concept().startsWith(KrCommonIncome.PREF_EPS_DART)
                || f.source() == Source.IFRS && KrCommonIncome.PREF_EPS_IFRS.equals(f.concept());
    }

    /** 보통주 밖 주식의 증거 — 판정 엔진과 같은 술어({@code Material.preferenceConcepts}). */
    private static boolean preferenceEvidence(List<Fact> facts) {
        return KrCommonIncome.preferenceConcepts(facts);
    }

    private static BigDecimal normalized(BigDecimal v) {
        return v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros();
    }
}
