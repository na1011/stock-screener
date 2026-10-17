package com.stockscreener.pipeline.datasource.us.listing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 등록인 이름 대조 — 증권 이름(시세 소스 · 우리 행)과 SEC 색인의 제출자 이름을 같은 꼴로 맞춰 CIK 후보를 낸다(탐색
 * {@code US-PAST-LISTING-PLAN-1017.md} ★6 {@code match2.py} 규칙 · 구현 계획 §2.2 (a)).
 *
 * <p><b>후보일 뿐이다</b> — 이름만 맞고 다른 회사인 것이 잡혔다(ARIS → Aris Mining · ★6). 묶기는 표지 확인이 정한다. 그래서 이 규칙은
 * 재현율 쪽으로 너그럽다: 증권 종류 낱말 · 법인 꼴 · 「/DE/」 같은 주(州) 꼬리를 걷고, 정확히 맞는 것이 없으면 끝 낱말 하나를 뗀
 * 이름도 본다.
 */
public final class RegistrantNames {

    private static final Pattern STATE_TAIL = Pattern.compile("/[A-Z]{2,3}/?\\s*$");
    private static final Pattern EACH_REPRESENTING = Pattern.compile("\\beach representing.*");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern DROP = Pattern.compile(
            "\\b(common stock|common shares|ordinary shares?|american depositary shares?|depositary shares?|ads|class [a-c]"
                    + "|series [a-c]|new|the|inc|incorporated|corp|corporation|co|company|ltd|limited|plc|n ?v|s ?a|l ?p|llc|sa|ag"
                    + "|se|group|holdings?|cayman islands)\\b");

    private RegistrantNames() {
    }

    /** 대조용 꼴. 빈 글자가 나오면 대조에 못 쓴다(부르는 쪽이 버린다). */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = STATE_TAIL.matcher(name.strip()).replaceAll("").toLowerCase(Locale.ROOT).replace("&", " and ");
        n = EACH_REPRESENTING.matcher(n).replaceAll("");
        n = NON_ALNUM.matcher(n).replaceAll(" ");
        n = DROP.matcher(n).replaceAll(" ");
        return String.join(" ", n.trim().split("\\s+")).trim();
    }

    /** 제출자 이름 → CIK 색인. 같은 꼴의 이름이 여러 CIK면 전부 둔다. */
    public static Map<String, Set<Long>> index(Collection<Map.Entry<String, Long>> filers) {
        Map<String, Set<Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> filer : filers) {
            String key = normalize(filer.getKey());
            if (!key.isEmpty()) {
                out.computeIfAbsent(key, k -> new TreeSet<>()).add(filer.getValue());
            }
        }
        return out;
    }

    /** 이름의 CIK 후보 — 정확히 맞는 꼴이 먼저, 없으면 끝 낱말 하나를 뗀 꼴(「… Waterbury」 꼴 꼬리). */
    public static List<Long> candidates(String name, Map<String, Set<Long>> index) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return List.of();
        }
        Set<Long> exact = index.get(key);
        if (exact != null) {
            return new ArrayList<>(exact);
        }
        String[] words = key.split(" ");
        if (words.length > 1) {
            Set<Long> prefix = index.get(String.join(" ", Arrays.copyOf(words, words.length - 1)));
            if (prefix != null) {
                return new ArrayList<>(prefix);
            }
        }
        return List.of();
    }
}
