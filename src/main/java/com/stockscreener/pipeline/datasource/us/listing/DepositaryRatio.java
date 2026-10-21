package com.stockscreener.pipeline.datasource.us.listing;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공시 표지의 증권 이름({@code dei:Security12bTitle})에서 예탁증권(ADS · ADR)의 비율 — <b>1 예탁증권이 대표하는 주식 수</b>를 읽는다
 * (탐색 {@code ADS-BASIS-PLAN-1022.md} §1b · §1e).
 *
 * <p><b>원장이 비율을 준다</b> — 최신 연간 표지 ADS 76줄 중 67이 이 글에 비율을 싣고, 비율 태그
 * ({@code dei:EntityListingDepositoryReceiptRatio})를 함께 낸 7줄과 전부 같다(2026-10-22). 꼴은 여럿이고 수사와 숫자가 섞인다:
 * <ul>
 *   <li>「each representing five ordinary shares」 · 「each ADS represents 15 ordinary shares」 · 「representing three Equity Shares」(HDB)</li>
 *   <li>「each ten (10) American depositary shares representing twenty-three (23) ordinary shares」 — 10 ADS = 23주(2.3)</li>
 *   <li>「each six (6) ADSs representing one (1) Class A ordinary share」 · 「each three representing one Class A common share」(TAL) — 1 미만</li>
 *   <li>「two hundred and forty Class B ordinary shares」 · 「the right to receive twenty (20) Ordinary Share」 · 「0.4 Class A」</li>
 *   <li>붙어 쓴 「representingone」 · 「representingthree」 · 「15Class A」 — 원문 그대로다</li>
 * </ul>
 *
 * <p><b>MUST: 틀린 수보다 빈칸이다.</b> 첫 원형이 TAL 「each three representing one」을 1로 읽었다(옳은 값 1/3 · 태그 0.33) — 그래서
 * 「each N … representing M」을 「representing M」보다 먼저 본다. 꼴을 더할 때는 시험에 원문을 싣는다.
 *
 * <p>우선주 예탁증권(「Depositary shares, each representing a 1/1,000th interest in a share of … Preferred」)은 ADS가 아니다 —
 * {@link #isDepositary}가 거짓이고 비율도 안 낸다(보통주 단위 낱말이 없다).
 */
public final class DepositaryRatio {

    /**
     * 예탁증권 줄 — 「American Depositary/Depository …」 · 「ADS(s)」 · 「ADR(s)」. 우선주 예탁증권(「Depositary shares」 단독)은 아니다.
     * 약칭은 앞뒤에 낱말 글자도 하이픈도 없어야 한다 — 「ADS-TEC Energy」(ADSE)의 「ADS-」는 회사 이름이다.
     */
    private static final Pattern DEPOSITARY = Pattern.compile(
            "american\\s+deposit[ao]ry|(?<![\\w-])(?:ads(?:s|'s)?|adrs?)(?![\\w-])|\\bdeposit[ao]ry\\s+receipts?\\b");

    /** 권리 · 워런트 줄 — 「the right to receive」(AMRN 비율 문구)는 아니다. */
    private static final Pattern RIGHTS_OR_WARRANTS = Pattern.compile(
            "\\brights?\\s+to\\s+(?:purchase|subscribe)\\b|\\bwarrants?\\b");

    private static final Map<String, Integer> NUMBER = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
            Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30),
            Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70),
            Map.entry("eighty", 80), Map.entry("ninety", 90));

    /** 분수 수사 — 「one-half」 · 「one-third」. */
    private static final Map<String, Integer> FRACTION = Map.of("half", 2, "third", 3, "quarter", 4, "fourth", 4);

    /**
     * 수 하나 — 숫자(「1,000」 · 「0.4」) 또는 수사(「two hundred and forty」 · 「twenty-three」 · 「one-half」 · 「a」) 뒤에 괄호 숫자가 올 수
     * 있다. 앞에 낱말 글자 · 점 · 쉼표 · 달러 기호가 붙어 있으면 수가 아니다(「us$0.0001」의 꼬리를 수로 잡지 않게).
     */
    private static final String QUANTITY = "(?<![\\w.,$])((?:\\d[\\d,.]*)|(?:(?:a|" + String.join("|", NUMBER.keySet())
            + "|" + String.join("|", FRACTION.keySet()) + "|hundred|thousand|and)\\b[\\s\\-]*)+)\\s*(?:\\((\\d[\\d,.]*)\\))?";

    /**
     * 주식 단위 — 앞에 「of our」 · 「of one」 · 「of an」이 낄 수 있고(「two of our Class A ordinary shares」 · 「one-half of one ordinary
     * share」), 원문이 「ordinaryshares」로 붙여 쓰기도 한다. 단위 낱말 없이 「one Class A」로 끝난 글(WB 표지)도 받는다.
     */
    private static final String UNIT = "(?:of\\s+(?:our|its|the|one|an|a)\\s+)?(?:(?:class\\s+[a-z]\\s*)?(?:ordinary|common|equity)\\s*shares?"
            + "|class\\s+[a-z](?=\\s*(?:$|[,;)*])))";

    private static final String ADS_WORD = "(?:american\\s+deposit[ao]ry\\s+(?:shares?|receipts?)|ads(?:s|'s)?|adrs?)";

    /**
     * 「(each) N (ADSs) … representing M (shares)」 — N 예탁증권 = M주: 「each ten (10) American depositary shares representing twenty-three
     * (23)」 · 「two American depositary shares representing one」 · 「each three representing one」 · 「one American depositary share
     * representing 300」. 「each representing」은 N이 없으므로 아래 꼴로 간다.
     */
    private static final Pattern N_FOR_M = Pattern.compile("(?:(?:each|every)\\s+)?(?!represent)" + QUANTITY + "\\s*(?:" + ADS_WORD
            + ")?\\s*(?:of\\s+which\\s+)?(?:\\w+\\s+){0,3}?represent\\w*\\s+" + QUANTITY + "\\s*" + UNIT);

    /** 「representing M (shares)」 · 「the right to receive M」 — 1 예탁증권 = M주. */
    private static final Pattern ONE_FOR_M = Pattern.compile(
            "(?:represent\\w*|receive)\\s+(?:the\\s+right\\s+to\\s+receive\\s+)?" + QUANTITY + "\\s*" + UNIT);

    /** 원문에 붙어 쓴 「representingone」 · 「15class」를 띄운다. 「representing one」의 「represent|ing」를 가르지 않게 꼴을 닫는다. */
    private static final Pattern GLUED_VERB = Pattern.compile(
            "(representing|represents|represented|represent(?!s|ing|ed)|receive)(?=[a-z0-9(])");
    private static final Pattern GLUED_DIGIT = Pattern.compile("(\\d)(?=[a-z])");
    /** 단위 낱말 앞에 붙어 쓴 글자를 띄운다 — 「oneordinary」 · 「class aordinary」. */
    private static final Pattern GLUED_UNIT = Pattern.compile("(?<=[a-z])(?=(?:ordinary|common|equity)\\s*shares?)");

    private DepositaryRatio() {
    }

    /** 예탁증권(ADS · ADR) 줄인가. 권리 · 워런트 줄(「Rights to purchase American Depositary Shares」)은 아니다. */
    public static boolean isDepositary(String title) {
        if (title == null) {
            return false;
        }
        String s = normalize(title);
        return DEPOSITARY.matcher(s).find() && !RIGHTS_OR_WARRANTS.matcher(s).find();
    }

    /**
     * 1 예탁증권이 대표하는 주식 수. 예탁증권 줄이 아니거나 글에 비율이 없으면 빈 값이다(「American depositary shares」 단독 — BZ · TIGR ·
     * XNET 표지가 그렇다).
     */
    public static OptionalDouble sharesPerDepositary(String title) {
        if (!isDepositary(title)) {
            return OptionalDouble.empty();
        }
        String s = GLUED_DIGIT.matcher(GLUED_VERB.matcher(normalize(title)).replaceAll("$1 ")).replaceAll("$1 ");
        s = GLUED_UNIT.matcher(s).replaceAll(" ");
        Matcher m = N_FOR_M.matcher(s);
        if (m.find()) {
            Double n = quantity(m.group(1), m.group(2));
            Double shares = quantity(m.group(3), m.group(4));
            if (n != null && shares != null && n > 0 && shares > 0) {
                return OptionalDouble.of(shares / n);
            }
        }
        m = ONE_FOR_M.matcher(s);
        if (m.find()) {
            Double shares = quantity(m.group(1), m.group(2));
            if (shares != null && shares > 0) {
                return OptionalDouble.of(shares);
            }
        }
        return OptionalDouble.empty();
    }

    private static String normalize(String title) {
        return title.replaceAll("[\\s\\u00A0]+", " ").toLowerCase(Locale.ROOT);
    }

    /** 괄호 숫자가 있으면 그것을 믿는다(「twenty-three (23)」) — 없으면 숫자 또는 수사. */
    private static Double quantity(String text, String parenthesized) {
        if (parenthesized != null) {
            return number(parenthesized);
        }
        String t = text.strip();
        Double digits = number(t);
        if (digits != null) {
            return digits;
        }
        return "a".equals(t) ? Double.valueOf(1) : words(t);
    }

    private static Double number(String text) {
        try {
            return Double.valueOf(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 「two hundred and forty」 → 240 · 「twenty-three」 → 23. 모르는 낱말이 끼면 빈 값. */
    private static Double words(String text) {
        long total = 0;
        long current = 0;
        boolean seen = false;
        for (String w : text.split("[\\s\\-]+")) {
            if (w.isEmpty() || w.equals("and")) {
                continue;
            }
            Integer n = NUMBER.get(w);
            Integer denominator = FRACTION.get(w);
            if (denominator != null) {
                return (total + (current == 0 ? 1.0 : current)) / denominator;
            }
            if (n != null) {
                current += n;
                seen = true;
            } else if (w.equals("hundred")) {
                current = (current == 0 ? 1 : current) * 100;
                seen = true;
            } else if (w.equals("thousand")) {
                total += (current == 0 ? 1 : current) * 1000;
                current = 0;
                seen = true;
            } else {
                return null;
            }
        }
        return seen ? Double.valueOf(total + current) : null;
    }
}
