package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeSet;

/**
 * 공시 표지 하나로 순위 후보가 예탁증권(ADS)인지, 그렇다면 1 ADS가 보통주 1주인지를 판정한다(탐색 {@code ADS-BASIS-PLAN-1022.md} §2 v3).
 *
 * <p><b>왜 필요한가</b>: 시가총액 = 주식수 × 종가인데 해외 발행사 표지 주식수는 보통주이고 종가는 ADS당이다. 비율이 1이 아니면
 * 시총 · PER · EPS 계열이 그 비율배로 틀린다(UXIN 1 ADS = 300주 · 58.9B로 순위에 든다 · 실제 약 0.2B). 관문 밖 순위는 그 비율을 지표까지
 * 적용할 재료(판마다 저장한 표지)가 없으므로 <b>비율이 1이 아니거나 모르면 뺀다</b>(ADR-0162 「모르면 값을 안 낸다」와 같은 방향).
 *
 * <p>규칙(원장이 실제로 보인 꼴에서 왔다 · 최신 표지 83판 · 과거 933판):
 * <ul>
 *   <li>그 후보 기호가 달린 줄만 본다 — 기호가 ADS 줄과 보통주 줄에 함께 달린 표지가 있다(TAL · TIGR · NIO · YI …)라 <b>하나라도 ADS면 ADS</b>다.
 *       기호 줄이 없거나 그 줄에 이름이 없으면(DSGX 표지 기호 오타 「DGSX」) 표지 전체의 줄로 본다.</li>
 *   <li>비율은 ADS 줄 이름({@link DepositaryRatio})과 비율 태그를 함께 본다. <b>둘이 다르면 모른다</b> — 태그가 낡거나 뒤집힌 판이 있다
 *       (NTES 태그 25 · 이름 5 · API 태그 0.25 · 이름 4).</li>
 *   <li>후보 이름이 예탁증권을 말하는데 표지가 ADS 줄을 안 보이면 모른다(EDU 2024 · 2025 ADS 줄 이름이 「Name of Exchange…」 · SCNI 2022 ·
 *       BEKE 최신 표지는 보통주 줄뿐).</li>
 *   <li>비율이 1이어도 <b>표지 주식수가 클래스 구성원에만 실리면</b> 뺀다 — companyfacts가 축 없는 표지 주식수를 못 줘 저장 주식수가 표지 합이
 *       아니다(SE FY2025 저장 638,227,141 · 표지 A 565,506,375 + B 45,527,793 = 611,034,168 · +4.45% · 대조 ARM · NICE · BLTE는 표지와
 *       같다). 관문 뒤 클래스 축이 그 판을 판정한다 — 관문 밖에서 들이면 관문 날 숨김으로 빠져 명부가 갈린다(탐색 {@code ADS-B0-PLAN-1022.md}).</li>
 * </ul>
 */
public final class DepositaryCover {

    /** 판정. {@code EXCLUDED}면 순위에서 뺀다. */
    public enum Outcome { NOT_DEPOSITARY, RATIO_ONE, EXCLUDED }

    /** @param reason 사람 말 사유(빼는 판정만) */
    public record Verdict(Outcome outcome, String reason) {

        public boolean excluded() {
            return outcome == Outcome.EXCLUDED;
        }
    }

    /** 비율 두 값을 같다고 볼 상대 허용 — 태그의 「0.33」(TAL 1/3)이 이름의 1/3과 같게. */
    private static final double SAME_RATIO = 0.02;

    private DepositaryCover() {
    }

    /**
     * @param cover             상장 판정 표지({@link ClassFactReader#readListingCover})
     * @param symbol            순위 후보 기호(시세 소스 꼴 — 점 · 하이픈은 같게 본다)
     * @param nameSaysDepositary 후보 이름(목록 · 저장)이 예탁증권을 말하는가
     */
    public static Verdict judge(ClassFacts cover, String symbol, boolean nameSaysDepositary) {
        Map<String, String> titles = cover.coverOf(ClassFactReader.SECURITY_TITLE);
        Map<String, String> symbols = cover.coverOf(ClassFactReader.TRADING_SYMBOL);
        Map<String, String> tags = cover.coverOf(ClassFactReader.DEPOSITORY_RATIO);

        List<String> lines = new ArrayList<>();
        symbols.forEach((member, listed) -> {
            if (titles.containsKey(member) && listsSymbol(listed, symbol)) {
                lines.add(member);
            }
        });
        if (lines.isEmpty()) {
            lines.addAll(titles.keySet());
        }
        List<String> depositary = lines.stream().filter(m -> DepositaryRatio.isDepositary(titles.get(m))).toList();
        if (depositary.isEmpty()) {
            return nameSaysDepositary
                    ? new Verdict(Outcome.EXCLUDED, "이름은 예탁증권인데 표지가 예탁증권 줄을 안 보인다")
                    : new Verdict(Outcome.NOT_DEPOSITARY, null);
        }

        TreeSet<Double> ratios = new TreeSet<>();
        for (String member : depositary) {
            OptionalDouble fromTitle = DepositaryRatio.sharesPerDepositary(titles.get(member));
            fromTitle.ifPresent(ratios::add);
            addTag(ratios, tags.get(member));
        }
        addTag(ratios, tags.get(ClassFacts.AXISLESS));
        if (ratios.isEmpty()) {
            return new Verdict(Outcome.EXCLUDED, "예탁증권 비율을 표지가 안 싣는다");
        }
        if (ratios.last() > ratios.first() * (1 + SAME_RATIO)) {
            return new Verdict(Outcome.EXCLUDED, "예탁증권 비율이 갈린다 " + ratios);
        }
        double ratio = ratios.first();
        if (Math.abs(ratio - 1) > SAME_RATIO) {
            return new Verdict(Outcome.EXCLUDED, "1 예탁증권 = %s주".formatted(stripTrailingZeros(ratio)));
        }
        Map<String, String> shares = cover.coverOf(ClassFactReader.SHARES_OUTSTANDING);
        if (!shares.isEmpty() && !shares.containsKey(ClassFacts.AXISLESS)) {
            return new Verdict(Outcome.EXCLUDED, "표지 주식수가 클래스에만 실린다 " + shares.keySet().stream().map(DepositaryCover::member).toList());
        }
        return new Verdict(Outcome.RATIO_ONE, null);
    }

    private static String member(String key) {
        int colon = key.lastIndexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    private static boolean listsSymbol(String listed, String symbol) {
        String want = normalizeSymbol(symbol);
        for (String part : listed.split(",")) {
            if (normalizeSymbol(part).equals(want)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSymbol(String s) {
        return s.strip().toUpperCase(Locale.ROOT).replace('-', '.');
    }

    private static void addTag(TreeSet<Double> ratios, String tag) {
        if (tag == null) {
            return;
        }
        try {
            double v = Double.parseDouble(tag.strip());
            if (v > 0) {
                ratios.add(v);
            }
        } catch (NumberFormatException e) {
            // 수가 아닌 태그 값은 비율 재료가 아니다 — 이름의 비율만으로 판정한다(빈 값이면 위에서 뺀다).
        }
    }

    private static String stripTrailingZeros(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
