package com.stockscreener.pipeline.datasource.us.shareclass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 상장 줄 하나의 판정을 재무 행에 입힌다 — 관문이 켜진 판독기 셋(순위 · 지표 · 기저 판정)이 같은 규칙을 쓰는 한 자리(탐색
 * {@code SHARES-CLASS-PLAN-V5-1015.md} §1e·§10b-3).
 *
 * <p>재무 행은 자기 공시(접수번호 — {@code report_code}의 뒤)를 안다. 그 공시의 판정이:
 * <ul>
 *   <li><b>숨김</b>이면 주식수도 분모도 없다 — 앞 판으로 내려가지 않는다(상태로 막는다 · null이 아니다).</li>
 *   <li><b>보임 · 지금 경로</b>(축 없는 표지 · 앵커 아닌 공시)면 그 행이 실은 주식수 그대로다.</li>
 *   <li><b>보임 · 클래스 축</b>이면 판정의 N_L이다.</li>
 * </ul>
 * 판정이 없는 행:
 * <ul>
 *   <li><b>정기 공시</b>({@link IssuerFacts#PERIODIC_FORMS})이고 그 줄이 이미 클래스 축에 든 뒤(그 날까지 앵커 판정이 하나라도 있다)이며
 *       인스턴스 구간({@link IssuerFacts#INSTANCE_FROM}) 안이면 <b>미판정 = 숨김</b>이다 — 유도가 아직 안 돈 새 공시가 옛 N_L로 조용히
 *       되돌아가지 않게(§10b-3). 그 밖의 정기 공시는 지금 경로다(단일 클래스 발행사 · 인스턴스 구간 앞의 이력).</li>
 *   <li><b>정기 공시가 아니면</b>(정정 · 6-K 등) 그 공시일에 서 있던 판정을 따른다 — 그 공시는 주식수를 따로 판정받지 않는다. N_L은
 *       그 판정의 공시일에서 이 행의 공시일까지의 분할만큼 옮긴다(호출부는 모든 행을 자기 공시일 기준으로 받는다).</li>
 * </ul>
 * 분모 n은 보인 판정의 것만 낸다(비 지표 셋의 두 끝 · §10c).
 */
public final class ShareClassLine {

    private static final LocalDate INSTANCE_FROM = IssuerFacts.INSTANCE_FROM;
    private static final Set<String> PERIODIC = IssuerFacts.PERIODIC_FORMS;

    public enum State { SHOWN, HIDDEN }

    /** 행 하나를 읽은 결과. {@code shares}는 그 행의 공시일 기준(지금 경로면 행이 실은 값 그대로 — null일 수 있다). */
    public record Read(BigDecimal shares, BigDecimal denominator, State state, String reason) {

        static Read hidden(String reason) {
            return new Read(null, null, State.HIDDEN, reason);
        }
    }

    /** 판정 표 한 행. */
    public record Verdict(String accession, LocalDate filed, char kind, char status, boolean currentPath,
                          boolean ledgerContradiction, BigDecimal lineShares, BigDecimal denominator) {
    }

    public record Split(LocalDate date, BigDecimal ratio) {
    }

    private final Map<String, Verdict> byAccession = new HashMap<>();
    private final List<Verdict> byFiled;
    private final LocalDate firstAnchor;
    private final List<Split> splits;

    public ShareClassLine(List<Verdict> verdicts, List<Split> splits) {
        verdicts.forEach(v -> byAccession.put(v.accession(), v));
        this.byFiled = verdicts.stream()
                .sorted(Comparator.comparing(Verdict::filed).thenComparing(Verdict::accession)).toList();
        this.firstAnchor = verdicts.stream().filter(v -> v.kind() == 'T').map(Verdict::filed)
                .min(Comparator.naturalOrder()).orElse(null);
        this.splits = List.copyOf(splits);
    }

    /** 판정이 하나도 없는 줄 — 모든 행이 지금 경로다. */
    public static ShareClassLine none() {
        return new ShareClassLine(List.of(), List.of());
    }

    /**
     * @param reportCode    {@code financial_statement.report_code}({@code 폼:접수번호})
     * @param currentShares 그 행이 실은 주식수(지금 경로)
     */
    public Read read(String reportCode, LocalDate publishedAt, BigDecimal currentShares) {
        int colon = reportCode == null ? -1 : reportCode.indexOf(':');
        String form = colon < 0 ? "" : reportCode.substring(0, colon);
        String accession = colon < 0 ? "" : reportCode.substring(colon + 1);
        Verdict own = byAccession.get(accession);
        if (own != null) {
            return apply(own, publishedAt, currentShares);
        }
        if (PERIODIC.contains(form)) {
            if (firstAnchor != null && !firstAnchor.isAfter(publishedAt) && !publishedAt.isBefore(INSTANCE_FROM)) {
                return Read.hidden("미판정");
            }
            return new Read(currentShares, null, State.SHOWN, "판정 없음");
        }
        Verdict standing = standingOn(publishedAt);
        return standing == null ? new Read(currentShares, null, State.SHOWN, "판정 없음")
                : apply(standing, publishedAt, currentShares);
    }

    private Read apply(Verdict v, LocalDate publishedAt, BigDecimal currentShares) {
        if (v.status() == 'X') {
            return Read.hidden("숨김");
        }
        if (v.currentPath()) {
            return new Read(currentShares, v.denominator(), State.SHOWN, "지금 경로");
        }
        return new Read(v.lineShares().multiply(factor(v.filed(), publishedAt)), v.denominator(), State.SHOWN, "클래스 축");
    }

    /** {@code day}에 서 있던 판정(그날까지 공시된 것의 최신). */
    public Verdict standingOn(LocalDate day) {
        Verdict found = null;
        for (Verdict v : byFiled) {
            if (v.filed().isAfter(day)) {
                break;
            }
            found = v;
        }
        return found;
    }

    /** {@code from} 뒤 · {@code to} 이하에 발효한 분할·병합 비율의 곱(순위 판독기의 {@code SHIFT_TO_ASOF}와 같은 경계). */
    public BigDecimal factor(LocalDate from, LocalDate to) {
        return factor(splits, from, to);
    }

    public static BigDecimal factor(List<Split> splits, LocalDate from, LocalDate to) {
        BigDecimal f = BigDecimal.ONE;
        for (Split s : splits) {
            if (s.date().isAfter(from) && !s.date().isAfter(to)) {
                f = f.multiply(s.ratio());
            }
        }
        return f;
    }

    /** 보류 함수의 입력 — 이 줄의 판정 이력(공시일 순). */
    public List<MembershipHold.Verdict> history() {
        return byFiled.stream().map(v -> new MembershipHold.Verdict(v.filed(), v.status() == 'X', v.ledgerContradiction(),
                v.lineShares() == null ? null : v.lineShares().doubleValue())).toList();
    }

    public boolean isEmpty() {
        return byFiled.isEmpty();
    }
}
