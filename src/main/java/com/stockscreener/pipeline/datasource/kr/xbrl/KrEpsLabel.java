package com.stockscreener.pipeline.datasource.kr.xbrl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 회사 확장 주당이익 개념의 한국어 이름표({@code _lab-ko.xml} 표준 역할 · DART 전체 재무제표 응답의 비표준 계정명과 같은 글)를 종류 · 기본/희석 ·
 * 영업 구분으로 가른다(ADR-0210). 회사 확장 개념 이름은 대개 일련번호({@code udf_IS_<번호>_udf_IS_<부모 번호>_<표준 조상>})라 뜻은 이름표에만 있다.
 *
 * <p>문법 하나를 전 종목에 쓴다(2026-11-13 캐시 이름표 2,210판 · 녹화본 응답 계정명 874종 실측): 종류는 「보통주」 · 우선주 꼴
 * (「우선주」 · 「구형우선주」 · 「1우선주」 · 「1종종류주식」 등) · 둘 다 없음, 기본은 「기본」(기본및희석 · 기본/희석 포함) 또는 희석 낱말이 없는 줄,
 * 영업 구분은 「계속」 · 「중단」 · 그 밖 합계(「계속영업과 중단영업」 · 「중단영업을 포함한」은 합계). 주당 금액이 아닌 줄(배당 · 산정 내역 · 분모
 * 조정 · 각주 · 포괄이익)과 보통주 · 우선주를 함께 말하는 줄은 가르지 않는다({@code null}).
 *
 * <p><b>조상 이름표</b>: 계속 · 중단 구분과 종류를 부모 abstract가 들고 자식은 「기본주당손익」만 적는 판이 있다(NHN 「주당이익-계속영업」 아래 등 ·
 * 캐시 이름표 42종). 개념 이름의 두 번째 뒤 {@code udf_} 번호가 조상이고, 자기 이름표가 말하지 않은 종류 · 영업 구분은 가까운 조상부터 채운다.
 */
public record KrEpsLabel(ShareClass shareClass, String preferredClass, boolean basic, Operations operations) {

    public enum ShareClass {
        COMMON, PREFERRED, NONE
    }

    public enum Operations {
        TOTAL, CONTINUING, DISCONTINUED
    }

    /** 저장 이름표에서 조상 이름표와 자기 이름표를 잇는 글({@code 조상 › … › 자기}). */
    static final String SEPARATOR = " › ";

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NUMBERING = Pattern.compile("^([0-9]+|[가-하]|[Ⅰ-Ⅻ])\\.");
    private static final Pattern AMOUNT = Pattern.compile("(보통|우선)주당기");
    private static final Pattern NOT_PER_SHARE = Pattern.compile(
            "[\\[\\]_]|배당|공시|설명|기술|주석|(에대한|-)주기|주기$|각주|추가정보|조정|효과|가중평균|주식수|분모|분자|계산|산정|내역|합계|포괄");
    private static final Pattern PREFERRED = Pattern.compile("(구형|신형|전환|상환|제?[0-9])?우선주|제?[0-9]*종종류주식|종류주식");
    private static final Pattern PROFIT = Pattern.compile("이익|손익|손실");
    private static final Pattern UDF = Pattern.compile("udf_[A-Z]+_[0-9]+");

    /** 저장 이름표(조상이 있으면 {@link #SEPARATOR}로 이은 글)가 주당이익이면 그 분류, 아니면 {@code null}. */
    public static KrEpsLabel of(String stored) {
        if (stored == null) {
            return null;
        }
        String[] chain = stored.split(Pattern.quote(SEPARATOR));
        String own = normalized(chain[chain.length - 1]);
        if (!own.contains("주당") || !PROFIT.matcher(own).find() || NOT_PER_SHARE.matcher(own).find()) {
            return null;
        }
        Parts parts = Parts.of(own);
        if (parts.mixed()) {
            return null;
        }
        ShareClass shareClass = parts.shareClass();
        String preferredClass = parts.preferredClass();
        Operations operations = parts.operations();
        for (int i = chain.length - 2; i >= 0; i--) {
            Parts ancestor = Parts.of(normalized(chain[i]));
            if (shareClass == null && ancestor.shareClass() != null && !ancestor.mixed()) {
                shareClass = ancestor.shareClass();
                preferredClass = ancestor.preferredClass();
            }
            if (operations == null) {
                operations = ancestor.operations();
            }
        }
        boolean basic = own.contains("기본") || !own.contains("희석");
        return new KrEpsLabel(shareClass == null ? ShareClass.NONE : shareClass, preferredClass, basic,
                operations == null ? Operations.TOTAL : operations);
    }

    /**
     * 그 판 이름표 맵에서 회사 확장 개념 하나의 저장 이름표 — 자기 이름표가 없으면 {@code null}. 개념 이름의 뒤 {@code udf_} 번호마다 그 번호로
     * 시작하는 개념의 이름표를 조상으로 앞에 잇는다(먼 조상이 앞).
     */
    public static String stored(String concept, Map<String, String> labels) {
        String own = labels.get(concept);
        if (own == null) {
            return null;
        }
        Matcher m = UDF.matcher(concept);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group());
        }
        List<String> chain = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 1; i--) {
            String ancestor = ancestorLabel(ids.get(i), labels);
            if (ancestor != null) {
                chain.add(ancestor);
            }
        }
        chain.add(own);
        return String.join(SEPARATOR, chain);
    }

    /** 기본 · 합계 주당이익인가 — 판정이 E로 받는 꼴. */
    public boolean basicTotal() {
        return basic && operations == Operations.TOTAL;
    }

    private static String ancestorLabel(String id, Map<String, String> labels) {
        String best = null;
        String label = null;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(id + "_") && (best == null || key.length() < best.length())) {
                best = key;
                label = e.getValue();
            }
        }
        return label;
    }

    private static String normalized(String label) {
        String n = SPACE.matcher(label).replaceAll("");
        n = NUMBERING.matcher(n).replaceFirst("");
        // 「보통주당기순이익」은 보통주 몫 금액이다 — 그 「주당」은 주당 금액이 아니다(「보통주기본주당기순손실」은 주당 금액이라 안 건드린다).
        return AMOUNT.matcher(n).replaceAll("$1주·당기");
    }

    private record Parts(ShareClass shareClass, String preferredClass, Operations operations, boolean mixed) {

        static Parts of(String n) {
            boolean common = n.contains("보통주");
            Matcher preferred = PREFERRED.matcher(n);
            boolean isPreferred = preferred.find();
            ShareClass shareClass = common && isPreferred ? null : common ? ShareClass.COMMON : isPreferred ? ShareClass.PREFERRED : null;
            Operations operations = n.contains("계속영업과중단영업") || n.contains("계속및중단") || n.contains("중단영업을포함")
                    || n.contains("중단사업을포함") ? Operations.TOTAL
                    : n.contains("계속") ? Operations.CONTINUING
                    : n.contains("중단") ? Operations.DISCONTINUED
                    : null;
            return new Parts(shareClass, isPreferred && !common ? preferred.group() : null, operations, common && isPreferred);
        }
    }
}
