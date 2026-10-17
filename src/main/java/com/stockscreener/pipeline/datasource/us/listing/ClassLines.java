package com.stockscreener.pipeline.datasource.us.listing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 거래소 양식의 「증권 종류」 글을 증권 줄로 가르고, 줄마다 보통주인지 본다(탐색 {@code US-PAST-LISTING-PLAN-1017.md} D5 「종류 맞추기」).
 *
 * <p><b>가르는 것이 먼저다</b> — 발행사 {@code 25} 56 중 16이 여러 종류를 한 글에 싣는다(탐색 ☆ 안 6). LEU는 「Class A Common Stock - par
 * value $0.10 per share Rights to purchase Series A … Preferred Stock, par value $1.00 per share」 한 글이고, 가르지 않고 「우선주가
 * 들었다」로 보면 보통주 제거가 사라진다. 가르는 자리는 {@code ;} · 줄바꿈 · 「per share」 뒤다.
 *
 * <p>보통주 줄은 <b>빼는 낱말이 먼저</b>다 — 「Redeemable warrants, each … for one Class A Ordinary Share」는 보통주 낱말을 싣지만
 * 워런트다. 빼는 낱말: 워런트 · 권리 · 우선주 · 사채 · 혼성 단위. 그 뒤 보통주 낱말(보통주 · 보통 · ADS · 자본주 · 수익권 · MLP
 * 「Common Units」)이 있어야 한다.
 */
public final class ClassLines {

    private static final Pattern SPLIT = Pattern.compile(";|\\r?\\n|(?<=\\bper share\\b)", Pattern.CASE_INSENSITIVE);

    private static final Pattern NOT_COMMON = Pattern.compile(
            "\\bwarrants?\\b|\\brights?\\b|\\bpreferred\\b|\\bpreference\\b|\\bnotes?\\b|\\bdebentures?\\b|\\bbonds?\\b"
                    + "|\\bdue\\s+\\d{4}\\b|\\b(?:corporate|equity|tangible equity|purchase)\\s+units?\\b|\\bcapital securities\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMON = Pattern.compile(
            "\\bcommon\\b|\\bordinary\\s+shares?\\b|\\bamerican\\s+depositary\\s+shares?\\b|\\bADSs?\\b|\\bcapital\\s+stock\\b"
                    + "|\\bbeneficial\\s+interests?\\b|\\bsubordinate\\s+voting\\s+shares?\\b|\\blimited\\s+partner(?:ship)?\\s+interests?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLASS_LETTER = Pattern.compile("\\bClass\\s+([A-Z])\\b", Pattern.CASE_INSENSITIVE);

    private ClassLines() {
    }

    /** 종류 글을 증권 줄로 가른다. 빈 줄은 버린다. */
    public static List<String> split(String description) {
        List<String> out = new ArrayList<>();
        if (description == null) {
            return out;
        }
        for (String part : SPLIT.split(description)) {
            String line = part.strip().replaceAll("\\s+", " ");
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 보통주 줄인가 — 빼는 낱말이 없고 보통주 낱말이 있다. */
    public static boolean isCommon(String line) {
        return !NOT_COMMON.matcher(line).find() && COMMON.matcher(line).find();
    }

    /** 「Class A」의 글자(대문자). 없으면 비운다. */
    public static Optional<String> classLetter(String line) {
        Matcher m = CLASS_LETTER.matcher(line);
        return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }
}
