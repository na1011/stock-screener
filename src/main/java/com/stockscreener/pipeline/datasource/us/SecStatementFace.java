package com.stockscreener.pipeline.datasource.us;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>공시 하나의 재무제표 본문에 실린 개념</b>(재고 「부문 합계가 영업이익 자리에 앉음」). companyfacts는 사실이 어느 표에 실렸는지를 안 준다 — 그래서
 * 부문 주석의 합계 줄({@code OperatingIncomeLoss} · 축 없음)이 손익계산서 소계처럼 들어왔다(SHW · PPG · SLB · EXP: 그 개념이 부문 주석 역할에만 있다 ·
 * 대조군 MSFT는 손익계산서 역할에 있다 · 2026-11-01 탐침). 원장은 공시 폴더에 그 구조를 준다.
 *
 * <p>재료(같은 답 · 2026-11-01 대조): 과거분은 SEC Financial Statement Data Sets의 {@code pre.txt}(분기 ZIP 하나가 그 분기 전 제출자 —
 * {@link #dataSetConcept}), 그 데이터셋이 아직 안 나온 최근 공시는 공시 폴더에서:
 * <ul>
 *   <li>{@code MetaLinks.json}(SEC 렌더러 · 2019년 무렵부터) — 보고서마다 {@code groupType}({@code statement} · {@code disclosure} · …)과
 *       역할, 개념마다 실린 역할({@code presentation}).</li>
 *   <li>그 앞: 표시 링크베이스({@code _pre.xml} · 없으면 링크베이스를 품은 {@code .xsd})와 본문 역할 판정 — {@code FilingSummary.xml}의
 *       {@code MenuCategory = Statements}, 그것이 없는 옛 판은 SEC 규약의 역할 정의({@code "124000 - Statement - Statement of Income"} ·
 *       표시 링크의 {@code xlink:title} 또는 스키마 {@code roleType}의 {@code definition}).</li>
 * </ul>
 * 본문 역할에 실린 개념 이름({@code us-gaap_OperatingIncomeLoss} 꼴)을 낸다. 본문 역할을 하나도 못 찾으면 빈 집합이다 — 「모른다」로 읽는다.
 */
public final class SecStatementFace {

    private static final Pattern REPORT = Pattern.compile("<Report\\b.*?</Report>", Pattern.DOTALL);
    private static final Pattern MENU = Pattern.compile("<MenuCategory>\\s*([^<]+?)\\s*</MenuCategory>");
    private static final Pattern ROLE = Pattern.compile("<Role>\\s*([^<]+?)\\s*</Role>");
    private static final Pattern LINK = Pattern.compile("<(?:link:)?presentationLink\\b[^>]*?xlink:role=\"([^\"]+)\"(.*?)</(?:link:)?presentationLink>",
            Pattern.DOTALL);
    private static final Pattern LOC = Pattern.compile("<(?:link:)?loc\\b[^>]*?xlink:href=\"[^\"#]*#([^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile("xlink:title=\"([^\"]*)\"");
    private static final Pattern ROLE_TYPE = Pattern.compile(
            "<(?:link:)?roleType\\b[^>]*?roleURI=\"([^\"]+)\".*?<(?:link:)?definition>([^<]*)</(?:link:)?definition>", Pattern.DOTALL);
    private static final Pattern STATEMENT_DEFINITION = Pattern.compile("\\s-\\s*(?:Statement|Document)\\s*-\\s", Pattern.CASE_INSENSITIVE);

    /** 데이터셋의 재무제표 분류 — 본문. */
    public static final Set<String> STATEMENTS = Set.of("IS", "BS", "CF", "EQ", "CI", "UN", "SI");
    /** 손익을 싣는 분류. */
    public static final Set<String> INCOME_STATEMENTS = Set.of("IS", "CI", "UN");
    /** 손익계산서의 마지막 줄 — 이것이 본문에 있어야 그 재료가 손익계산서를 실었다고 본다. */
    public static final Set<String> FINAL_INCOME = Set.of("NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic");

    /**
     * 데이터셋의 한 줄이 손익계산서의 마지막 줄인가(IS의 순이익 · 주당이익 · CI · UN의 주당이익) — 그런 줄이 하나도 없는 공시의 재료는
     * 손익 열의 부재를 판정할 수 없다: INTU FY2024 10-K는 BS · EQ · CI(기타포괄손익)뿐 · PEP 2018 3분기는 분류 없는 줄뿐 · CCL FY2019 10-K는
     * IS 한 줄(손상)뿐이었다.
     */
    public static boolean finalIncomeLine(String stmt, String tag) {
        if (tag == null) {
            return false;
        }
        // 기타포괄손익표(CI)는 순이익 줄로 시작한다 — 그것만으로 손익계산서가 있다고 보지 않는다(INTU FY2024 10-K: 데이터셋에 IS 없이 CI만).
        // 손익 · 포괄손익 합본(CI · UN)은 주당이익 줄이 있을 때만 손익계산서다.
        return ("IS".equals(stmt) && (FINAL_INCOME.contains(tag) || tag.contains("EarningsPerShare")))
                || (("CI".equals(stmt) || "UN".equals(stmt)) && tag.contains("EarningsPerShare"));
    }

    /** 본문 개념 집합이 손익계산서의 마지막 줄을 싣는가(공시 폴더 재료 — 표지만 있는 8-K는 dei 개념뿐이다). */
    public static boolean carriesFinalIncome(Set<String> concepts) {
        for (String c : concepts) {
            int cut = c.indexOf('_');
            String local = cut < 0 ? c : c.substring(cut + 1);
            if ((c.startsWith("us-gaap_") || c.startsWith("ifrs-full_"))
                    && (FINAL_INCOME.contains(local) || local.contains("EarningsPerShare") || local.equals("BasicEarningsLossPerShare"))) {
                return true;
            }
        }
        return false;
    }

    private SecStatementFace() {
    }

    /**
     * SEC Financial Statement Data Sets의 {@code pre.txt} 한 줄의 개념 이름 — {@code version}이 표준 택소노미({@code us-gaap/2023} ·
     * {@code ifrs/2023})면 그 접두({@code us-gaap_} · {@code ifrs-full_}), 회사 확장(접수번호)이면 {@code null}. 재무제표 본문 줄
     * ({@code stmt} = IS · BS · CF · EQ · CI · UN · SI)만 센다 — 표지({@code CP})와 분류 없는 상세 줄({@code stmt} 빈칸 · PEP 2018 3분기 10-Q는
     * 줄 전부가 그 꼴이다)은 뺀다.
     */
    public static String dataSetConcept(String version, String tag, String stmt) {
        if (!STATEMENTS.contains(stmt) || tag == null || tag.isBlank()) {
            return null;
        }
        if (version.startsWith("us-gaap/")) {
            return "us-gaap_" + tag;
        }
        if (version.startsWith("ifrs/")) {
            return "ifrs-full_" + tag;
        }
        if (version.startsWith("dei/") || version.startsWith("srt/")) {
            return version.substring(0, version.indexOf('/')) + "_" + tag;
        }
        return null;
    }

    /**
     * {@code MetaLinks.json}에서 — 그 인스턴스의 {@code statement} · {@code document} 보고서 역할에 실린 개념. {@code groupType}은 제출자의 역할
     * 정의에서 오고 제출자가 손익계산서를 {@code Document}로 정의하기도 한다(NRG FY2019 10-K — 데이터셋은 그 역할을 IS로 분류) — 본문을 놓쳐
     * 영업이익을 잘못 버리지 않게 {@code document}도 센다(표지의 dei 개념이 섞이지만 판정 개념과 안 겹친다).
     */
    public static Set<String> fromMetaLinks(JsonNode metaLinks) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, JsonNode> instance : metaLinks.path("instance").properties()) {
            Set<String> statementRoles = new HashSet<>();
            for (Map.Entry<String, JsonNode> report : instance.getValue().path("report").properties()) {
                String group = report.getValue().path("groupType").asString("");
                if ("statement".equalsIgnoreCase(group) || "document".equalsIgnoreCase(group)) {
                    statementRoles.add(report.getValue().path("role").asString(""));
                }
            }
            for (Map.Entry<String, JsonNode> tag : instance.getValue().path("tag").properties()) {
                for (JsonNode role : tag.getValue().path("presentation")) {
                    if (statementRoles.contains(role.asString(""))) {
                        out.add(tag.getKey());
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 표시 링크베이스에서 — 본문 역할은 {@code FilingSummary.xml}의 {@code MenuCategory}가 정하고, 그 분류가 없는 옛 판은 역할 정의(표시 링크
     * {@code xlink:title} · {@code schema}의 {@code roleType})가 정한다.
     *
     * @param schema 그 공시의 {@code .xsd}(역할 정의 · 링크베이스를 품기도 한다) — 없으면 {@code null}
     */
    public static Set<String> fromLinkbase(String filingSummary, String presentation, String schema) {
        Set<String> statementRoles = new HashSet<>();
        boolean categorised = false;
        Matcher report = REPORT.matcher(filingSummary);
        while (report.find()) {
            Matcher menu = MENU.matcher(report.group());
            Matcher role = ROLE.matcher(report.group());
            if (menu.find() && role.find()) {
                categorised = true;
                if ("Statements".equalsIgnoreCase(menu.group(1))) {
                    statementRoles.add(role.group(1));
                }
            }
        }
        if (!categorised && schema != null) {
            Matcher type = ROLE_TYPE.matcher(schema);
            while (type.find()) {
                if (STATEMENT_DEFINITION.matcher(" " + type.group(2) + " ").find()) {
                    statementRoles.add(type.group(1));
                }
            }
        }
        Set<String> out = new TreeSet<>();
        Matcher link = LINK.matcher(presentation);
        while (link.find()) {
            String header = presentation.substring(link.start(), Math.min(link.end(), presentation.indexOf('>', link.start()) + 1));
            Matcher title = TITLE.matcher(header);
            boolean statement = statementRoles.contains(link.group(1))
                    || (!categorised && title.find() && STATEMENT_DEFINITION.matcher(" " + title.group(1) + " ").find());
            if (!statement) {
                continue;
            }
            Matcher loc = LOC.matcher(link.group(2));
            while (loc.find()) {
                out.add(loc.group(1));
            }
        }
        return out;
    }
}
