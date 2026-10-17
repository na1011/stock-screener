package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource.EndEvent;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 끝 판정 — 열린 행의 실체가 피인수 · 가치 없음 제거로 사라졌는가(ADR-0180 · 계획 {@code US-PAST-LISTING-PLAN-1017.md} 「끝 판정」).
 * 순수 함수다. 사건은 폐지의 <b>후보</b>일 뿐이다 — 실측(★3)에서 기호에 행이 있는 사건 21 중 17은 사건 뒤에도 같은 실체가 거래됐다
 * (CUSIP만 바뀐 재조직 · AZN · TRI · QGEN · IRTC). 그래서 둘을 원장의 봉으로 확인한다.
 *
 * <ol>
 *   <li><b>같은 실체</b> — 사건 기호를 효력일 전날 또는 효력일 {@code asof}로 받은 봉이 행의 닻 봉과 같다(사건 기호 = 닻 기호면 겹친
 *       날 전부 · 다르면 겹침 {@link IdentityJudge#MIN_OVERLAP} 이상). 기호가 재사용됐으면 그 사건은 다른 실체의 것이다. 두 시점을 보는
 *       이유: 소스의 기호 → 실체 대응이 폐지 경계에서 하루 단위로 갈린다(CCO 2026-10-17 실측 — {@code asof=10-14}는 그날 봉 하나만,
 *       {@code asof=10-15}는 10-14까지 전부를 준다 · 닻 {@code asof=08-28}은 10-13에서 끝난다).</li>
 *   <li><b>끝</b> — 닻 봉에 효력일 뒤 거래 봉({@code n} &gt; 0)이 없다. 장외로 간 기호의 거래 0 채움 봉(★7)은 거래가 아니다.</li>
 * </ol>
 *
 * <p>끝 날짜는 사건일이 정하고 봉은 확인만 한다 — 거래가 드문 상장 주식은 거래 사이가 64일까지 빈다(★5 BIO.B)라 마지막 거래 봉으로
 * 끝을 정하면 당겨진다. 실측 35/35: 마지막 거래 봉이 효력일 전 거래일(31) 또는 효력일 당일(4 · 장 뒤 효력)이다(★4). 그래서
 * {@code era_to} = 효력일에 거래 봉이 있으면 다음 날, 없으면 효력일이다. 한 실체에 사건이 여럿이면(합병 뒤 가치 없음 제거 · ☆ 안 7)
 * 확인이 서는 가장 이른 것이다.
 */
public final class EndJudge {

    private EndJudge() {
    }

    /** @param eraTo 시대 끝(제외) */
    public record End(EndEvent event, LocalDate eraTo, String evidence) {
    }

    /**
     * @param anchor       행의 닻 봉 — 사건들의 효력일 앞 {@link IdentityJudge#MIN_OVERLAP}세션 넘게와 그 뒤를 덮어야 한다
     * @param anchorSymbol 행의 닻 기호
     * @param candidates   그 행에 걸린 사건과 각 사건 기호의 효력일 전날 봉
     */
    public static Optional<End> decide(List<EntityBar> anchor, String anchorSymbol, List<Candidate> candidates) {
        List<Candidate> ordered = candidates.stream().sorted(Comparator.comparing(c -> c.event().effective())).toList();
        for (Candidate c : ordered) {
            LocalDate effective = c.event().effective();
            boolean sameSymbol = c.event().symbol().equals(anchorSymbol);
            boolean same = c.views().stream().anyMatch(view -> sameSymbol
                    ? IdentityJudge.compareSameWindow(anchor, view) == IdentityJudge.Comparison.SAME
                    : IdentityJudge.matchesAcrossSymbols(anchor, view));
            if (!same) {
                continue;
            }
            long tradedAfter = anchor.stream().filter(b -> b.date().isAfter(effective) && b.trades() > 0).count();
            if (tradedAfter > 0) {
                continue;
            }
            boolean tradedOnDay = anchor.stream().anyMatch(b -> b.date().equals(effective) && b.trades() > 0);
            LocalDate eraTo = tradedOnDay ? effective.plusDays(1) : effective;
            LocalDate lastTrade = anchor.stream().filter(b -> b.trades() > 0).map(EntityBar::date).max(LocalDate::compareTo)
                    .orElse(null);
            return Optional.of(new End(c.event(), eraTo, "%s %s %s(효력일 %s) · 닻 봉 = 사건 기호의 효력일 무렵 봉 · 효력일 뒤 거래 0 · 마지막 거래 %s"
                    .formatted(c.event().type(), c.event().symbol(), c.event().cusip(), effective, lastTrade)));
        }
        return Optional.empty();
    }

    /** @param views 사건 기호를 효력일 전날 · 효력일 {@code asof}로 받은 봉(둘 중 하나라도 닻 봉과 같으면 같은 실체) */
    public record Candidate(EndEvent event, List<List<EntityBar>> views) {
    }
}
