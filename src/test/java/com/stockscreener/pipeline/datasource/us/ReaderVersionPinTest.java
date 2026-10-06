package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해석 규칙의 출력을 {@link XbrlInstanceReader#READER_VERSION}에 묶는다(ADR-0173).
 *
 * <p>해석 실패({@code UNREADABLE}) 행은 판이 같은 동안 다시 받지 않는다. 그래서 규칙을 바꾸고 판을 안 올리면 고친 규칙이 그 행에
 * 영영 안 닿는다 — 리뷰가 그것을 잡는다고 믿지 않고 여기서 묻는다. 판의 범위 전부(받은 바이트의 해석
 * {@link SecFilingSupplement#interpret} — 읽기 · 신원 · 고르기 · 해석 실패 분류 · 후보 파일 규칙 · 인스턴스 고르기)에 고정 재료를
 * 먹여 출력의 해시를 판마다 박는다.
 *
 * <p><b>재료는 해석 실패의 자리마다 하나다</b> — {@code XbrlInstanceReader}의 {@code throw malformed(} 수와 재료 수가 같아야 하고,
 * 재료마다 그 자리에 닿는지 묻는다. 자리를 더하면 이 시험이 먼저 붉다(2026-10-06 v4 반증 — 재료가 인스턴스 하나뿐이라 같은
 * 정밀도 충돌·수가 아닌 값을 바꾼 변이가 살아남았다).
 *
 * <p><b>출력 해시와 소스 해시를 함께 박는다.</b> 재료는 손으로 고른 목록이라 끝내 모든 분기를 덮지 못한다(2026-10-06 v6
 * 반증 — 재료 밖 변이 12개가 살았다 · 실물 영향은 0). 그래서 판 범위 메서드의 소스(주석을 걷은 정규형)를 판마다 박아,
 * 소스를 건드리면 붉게 한다 — 목록을 늘리는 경쟁을 끝내고 「판 올림 / 행동 불변 재고정」을 같은 커밋에서 사람이 고르게 한다.
 *
 * <p><b>붉으면</b>:
 * <ul>
 *   <li>규칙(행동)을 바꿨다 → {@code READER_VERSION}을 올리고 두 해시를 {@link #PINNED}·{@link #SOURCE_PINNED} 끝에 더한다.</li>
 *   <li>리팩터라 행동이 그대로다 → {@link #SOURCE_PINNED}의 지금 판 값만 새 해시로 고치고 커밋 메시지에 「행동 불변
 *       재고정」과 근거(출력 해시가 그대로)를 적는다.</li>
 *   <li>재료만 더했다 → {@link #PINNED}의 지금 판 값만 고친다 — 소스 해시가 그대로인 것이 규칙 불변의 증거다.</li>
 * </ul>
 * MUST NOT: 소스 해시와 출력 해시를 한 커밋에서 같이 고치면서 판을 안 올리지 않는다 — 그것이 「판을 안 올리고 규칙을 바꾼 것」이다.
 */
class ReaderVersionPinTest {

    /** 판 1, 2, …의 판 범위 소스 해시(주석을 걷은 정규형). */
    private static final List<String> SOURCE_PINNED = List.of(
            "d1361d139cdfa74bb695997d9fea491ba9af9f3be6f5cebcd0cc9459f698b18c");

    /** 판 범위 — 파일과, 그 파일에서 범위인 메서드의 머리(없으면 파일 전체 · {@code READER_VERSION} 선언 줄은 뺀다). */
    private static final Map<String, List<String>> SOURCE_SCOPE = Map.of(
            "XbrlInstanceReader.java", List.of(),
            "SecFilingClient.java", List.of("static boolean isInstanceCandidate(", "static boolean isXbrlArtifact(",
                    "private static boolean isLinkbase("),
            "SecFilingSupplement.java", List.of("static String pickInstance(", "static Fetched interpret("));

    private static final Path SOURCE_DIR = Path.of("src/main/java/com/stockscreener/pipeline/datasource/us");

    /** 판 1, 2, …의 출력 해시. 끝에만 더한다. */
    private static final List<String> PINNED = List.of(
            "c8e8a6e90b0cf4786efd0fc10bacee5099b4babeb6cdd2f61666f43bb53ee982");

    private static final Path READER_SOURCE =
            Path.of("src/main/java/com/stockscreener/pipeline/datasource/us/XbrlInstanceReader.java");

    /** 해석 실패 자리마다 재료 하나 — 키는 그 자리의 메시지 머리다(그 자리에 닿았는지 묻는다). */
    private static final Map<String, String> MALFORMED = new LinkedHashMap<>();
    /** 실패가 아닌 특성 재료. */
    private static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        MALFORMED.put("인스턴스를 읽지 못했다", "<xbrl xmlns=\"http://www.xbrl.org/2003/instance\"><context id=\"d\">");
        MALFORMED.put("팩트가 없는 컨텍스트", doc("", """
                <gaap:Revenues contextRef="nope" unitRef="usd" decimals="-6">1</gaap:Revenues>"""));
        MALFORMED.put("팩트가 없는 단위", doc("", """
                <gaap:Revenues contextRef="q" unitRef="nounit" decimals="-6">1</gaap:Revenues>"""));
        MALFORMED.put("수가 아닌 값", doc("", """
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">abc</gaap:Revenues>"""));
        MALFORMED.put("문서 종류", doc("", "").replace(">10-Q</dei:DocumentType>", ">8-K</dei:DocumentType>"));
        MALFORMED.put("같은 정밀도에 값이 둘", doc("", """
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11139000000</gaap:Revenues>"""));
        MALFORMED.put("날짜가 아니다", doc("""
                <context id="bad"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                  <period><startDate>2026-13-40</startDate><endDate>2026-06-30</endDate></period></context>""", """
                <gaap:Revenues contextRef="bad" unitRef="usd" decimals="-6">1</gaap:Revenues>"""));

        FEATURES.put("unitRef 있는 dei · INF · decimals 없음", doc("", """
                <dei:EntityCommonStockSharesOutstanding contextRef="i" unitRef="shares" decimals="INF">123456</dei:EntityCommonStockSharesOutstanding>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="INF">11138000000</gaap:Revenues>
                <gaap:NetIncomeLoss contextRef="q" unitRef="usd">900000000</gaap:NetIncomeLoss>"""));
        FEATURES.put("decimals 없음과 -6이 같은 키 — 없음은 INF로 읽어 이긴다", doc("", """
                <gaap:Revenues contextRef="q" unitRef="usd">11138123456</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>"""));
        FEATURES.put("scenario 컨텍스트", doc("""
                <context id="sc"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                  <period><startDate>2026-04-01</startDate><endDate>2026-06-30</endDate></period>
                  <scenario><xbrldi:explicitMember dimension="gaap:StatementScenarioAxis">gaap:ScenarioPreviouslyReportedMember</xbrldi:explicitMember></scenario></context>""", """
                <gaap:Revenues contextRef="sc" unitRef="usd" decimals="-6">10000000000</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>"""));
        FEATURES.put("차원 멤버 없는 scenario — 그래도 축이다", doc("""
                <context id="sc2"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                  <period><startDate>2026-04-01</startDate><endDate>2026-06-30</endDate></period>
                  <scenario><gaap:RestatementFlag>true</gaap:RestatementFlag></scenario></context>""", """
                <gaap:Revenues contextRef="sc2" unitRef="usd" decimals="-6">10000000000</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>"""));
        // 고르기 키 구성요소마다 그 요소만 다른 쌍(2026-10-06 v5 반증 — 키에서 시작·끝·단위를 뺀 변이가 실제 인스턴스
        // 112·137·2개를 바꾸는데 재료가 못 잡았다).
        FEATURES.put("같은 끝 · 시작만 다름(3개월 · 6개월)", doc("", """
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>
                <gaap:Revenues contextRef="d" unitRef="usd" decimals="-6">22000000000</gaap:Revenues>"""));
        FEATURES.put("같은 개념 · 두 시점", doc("""
                <context id="i0"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                  <period><instant>2025-12-31</instant></period></context>""", """
                <gaap:Goodwill contextRef="i" unitRef="usd" decimals="-6">24035000000</gaap:Goodwill>
                <gaap:Goodwill contextRef="i0" unitRef="usd" decimals="-6">23000000000</gaap:Goodwill>"""));
        FEATURES.put("같은 기간 · 두 단위", doc("", """
                <unit id="eur"><measure>iso4217:EUR</measure></unit>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="eur" decimals="-6">10200000000</gaap:Revenues>"""));
        // 건너뛰는 조건마다 건너뛰는 재료(네임스페이스 · 차원 · 기간).
        FEATURES.put("IFRS 네임스페이스", doc("", """
                <ifrs:Revenue xmlns:ifrs="http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full" contextRef="q" unitRef="usd" decimals="-6">5</ifrs:Revenue>"""));
        FEATURES.put("차원 붙은 dei", doc("""
                <context id="cls"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier>
                  <segment><xbrldi:explicitMember dimension="gaap:StatementClassOfStockAxis">gaap:CommonClassAMember</xbrldi:explicitMember></segment></entity>
                  <period><instant>2026-06-30</instant></period></context>""", """
                <dei:EntityCommonStockSharesOutstanding contextRef="cls" unitRef="shares" decimals="INF">100</dei:EntityCommonStockSharesOutstanding>"""));
        FEATURES.put("xbrldi 없는 segment", doc("""
                <context id="sp"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier>
                  <segment><gaap:SomeTag>x</gaap:SomeTag></segment></entity>
                  <period><startDate>2026-04-01</startDate><endDate>2026-06-30</endDate></period></context>""", """
                <gaap:Revenues contextRef="sp" unitRef="usd" decimals="-6">7</gaap:Revenues>"""));
        FEATURES.put("공백 든 값 · 날짜 · 문서 종류", doc("""
                <context id="ws"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                  <period><instant> 2026-03-31 </instant></period></context>""", """
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6"> 11138000000 </gaap:Revenues>
                <gaap:Goodwill contextRef="ws" unitRef="usd" decimals="-6">1</gaap:Goodwill>""")
                .replace(">10-Q</dei:DocumentType>", "> 10-Q </dei:DocumentType>"));
        FEATURES.put("자기 컨텍스트 0 · 문서 종류 다름", doc("", "")
                .replace("0000001800", "0000009999").replace(">10-Q</dei:DocumentType>", ">8-K</dei:DocumentType>"));
        FEATURES.put("CIK 아닌 식별자", doc("""
                <context id="ticker"><entity><identifier scheme="http://example.com/ticker">ABT</identifier></entity>
                  <period><startDate>2026-04-01</startDate><endDate>2026-06-30</endDate></period></context>""", """
                <gaap:Revenues contextRef="ticker" unitRef="usd" decimals="-6">5</gaap:Revenues>
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>"""));
        FEATURES.put("문서 종류 없음", doc("", """
                <gaap:Revenues contextRef="q" unitRef="usd" decimals="-6">11138000000</gaap:Revenues>""")
                .replace("<dei:DocumentType contextRef=\"d\">10-Q</dei:DocumentType>", ""));
    }

    private static final List<String> NAMES = List.of(
            "fix-20251231_htm.xml", "fix-20251231.xml", "fix-20251231_lab.xml", "fix-20251231_cal.xml",
            "fix-20251231_def.xml", "fix-20251231_pre.xml", "fix-20251231_ref.xml", "FilingSummary.xml",
            "filingsummary.xml", "fix-20251231.xsd", "MetaLinks.json", "R1.htm", "Financial_Report.xlsx",
            "0000000001-26-000020-index.html", "fix-20251231_htm.XML");

    private static final List<List<String>> FOLDERS = List.of(
            List.of("fix-20251231_htm.xml", "fix-20251231_lab.xml", "FilingSummary.xml", "fix-20251231.xsd"),
            List.of("R1.htm", "Financial_Report.xlsx"),
            List.of("fix-20251231.xsd", "fix-20251231_lab.xml"),
            List.of("a_htm.xml", "b_htm.xml", "fix-20251231.xsd"),
            List.of("a_htm.xml", "b_htm.xml"),
            List.of("x_htm.xml", "x.xml", "x.xsd"),
            List.of());

    @Test
    @DisplayName("해석 규칙의 출력이 지금 판에 박힌 해시와 같다 — 규칙을 바꾸고 판을 안 올리면 붉다")
    void rulesArePinnedToTheReaderVersion() throws Exception {
        assertThat(PINNED).as("판 번호와 박힌 해시 수가 같아야 한다 — 판을 올리면 해시를 끝에 더한다")
                .hasSize(XbrlInstanceReader.READER_VERSION);
        assertThat(PINNED).as("판마다 출력이 달라야 한다 — 같으면 판을 올릴 일이 아니었다").doesNotHaveDuplicates();
        assertThat(digest(canonicalOutput()))
                .as("해석 규칙의 출력이 바뀌었다 — READER_VERSION을 올리고 새 해시를 PINNED 끝에 더한다(ADR-0173)")
                .isEqualTo(PINNED.get(XbrlInstanceReader.READER_VERSION - 1));
    }

    @Test
    @DisplayName("판 범위의 소스가 지금 판에 박힌 해시와 같다 — 소스를 건드리면 판 올림과 행동 불변 재고정 중 하나를 고른다")
    void ruleSourcesArePinnedToTheReaderVersion() throws Exception {
        assertThat(SOURCE_PINNED).as("판 번호와 박힌 소스 해시 수가 같아야 한다").hasSize(XbrlInstanceReader.READER_VERSION);
        assertThat(digest(scopeSource()))
                .as("판 범위의 소스가 바뀌었다 — 행동이 바뀌었으면 READER_VERSION을 올리고 두 해시를 끝에 더한다. 리팩터면 "
                        + "SOURCE_PINNED의 지금 판 값만 고치고 커밋에 「행동 불변 재고정」을 적는다(ADR-0173)")
                .isEqualTo(SOURCE_PINNED.get(XbrlInstanceReader.READER_VERSION - 1));
    }

    @Test
    @DisplayName("해석 실패의 자리마다 재료가 하나씩 있고 그 자리에 닿는다 — 자리를 더하면 재료부터 더한다")
    void everyMalformedSiteHasAFixtureThatReachesIt() throws Exception {
        Matcher sites = Pattern.compile("throw malformed\\(").matcher(Files.readString(READER_SOURCE));
        int count = 0;
        while (sites.find()) {
            count++;
        }
        assertThat(MALFORMED).as("XbrlInstanceReader의 throw malformed( 자리 수").hasSize(count);
        for (Map.Entry<String, String> fixture : MALFORMED.entrySet()) {
            assertThat(interpretOutcome(fixture.getValue())).as(fixture.getKey())
                    .startsWith("UnreadableFiling ").contains(fixture.getKey());
        }
    }

    private static String canonicalOutput() throws Exception {
        StringBuilder out = new StringBuilder();
        String mini;
        try (InputStream in = new ClassPathResource("fixtures/sec-edgar/instance-mini.xml").getInputStream()) {
            mini = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (long cik : new long[] {1800, 1868941, 320193}) {
            out.append("mini cik=").append(cik).append(' ').append(stripMessage(interpretOutcome(mini, cik))).append('\n');
        }
        for (Map.Entry<String, String> fixture : MALFORMED.entrySet()) {
            out.append("malformed ").append(fixture.getKey()).append(' ')
                    .append(stripMessage(interpretOutcome(fixture.getValue()))).append('\n');
        }
        for (Map.Entry<String, String> fixture : FEATURES.entrySet()) {
            out.append("feature ").append(fixture.getKey()).append(' ')
                    .append(stripMessage(interpretOutcome(fixture.getValue()))).append('\n');
        }
        List<String> forms = new ArrayList<>(SecFilingSupplement.SUPPLEMENT_FORMS);
        forms.sort(null);
        List<String> documentTypes = new ArrayList<>(forms);
        // 보충 폼 밖의 문서 종류 — 전환 보고서(T)와 다른 공시(2026-10-06 v5 반증).
        documentTypes.addAll(List.of("10-KT", "10-QT", "20-FT", "8-K", "S-1", "DEF 14A"));
        documentTypes.add(null);
        for (String form : forms) {
            for (String documentType : documentTypes) {
                XbrlInstanceReader.Instance instance = new XbrlInstanceReader.Instance(documentType, null, null, 1,
                        List.of());
                out.append("identity ").append(form).append(" doc=").append(documentType).append(' ')
                        .append(stripMessage(outcome(() -> {
                            XbrlInstanceReader.requireIdentity(instance, form, "pin");
                            return "ok";
                        }))).append('\n');
            }
        }
        for (String name : NAMES) {
            out.append("name ").append(name).append(" candidate=").append(SecFilingClient.isInstanceCandidate(name))
                    .append(" artifact=").append(SecFilingClient.isXbrlArtifact(name)).append('\n');
        }
        for (List<String> folder : FOLDERS) {
            out.append("folder ").append(folder).append(" -> ")
                    .append(stripMessage(outcome(() -> String.valueOf(SecFilingSupplement.pickInstance(folder, "pin")))))
                    .append('\n');
        }
        return out.toString();
    }

    private static String interpretOutcome(String xml) {
        return interpretOutcome(xml, 1800);
    }

    /** 해석의 결과 — 인스턴스의 머리 · 원 팩트 · 고른 팩트, 또는 실패의 종류와 메시지. */
    private static String interpretOutcome(String xml, long cik) {
        return outcome(() -> {
            SecFilingSupplement.Fetched fetched = SecFilingSupplement.interpret(xml.getBytes(StandardCharsets.UTF_8),
                    cik, "10-Q", "x_htm.xml", "pin");
            XbrlInstanceReader.Instance instance = fetched.instance();
            StringBuilder out = new StringBuilder("doc=" + instance.documentType() + " fy=" + instance.fiscalYearFocus()
                    + " fp=" + instance.fiscalPeriodFocus() + " own=" + instance.ownContexts());
            instance.facts().stream().map(String::valueOf).sorted().forEach(f -> out.append(" | raw ").append(f));
            fetched.chosen().stream().map(String::valueOf).sorted().forEach(f -> out.append(" | chosen ").append(f));
            return out.toString();
        });
    }

    /** 메시지는 문구라 해시에 안 넣는다(문구를 고친 것은 규칙을 바꾼 것이 아니다). 종류만 남긴다. */
    private static String stripMessage(String outcome) {
        int cut = outcome.indexOf(" :: ");
        return cut < 0 ? outcome : outcome.substring(0, cut);
    }

    private interface Body {
        Object get() throws Exception;
    }

    private static String outcome(Body body) {
        try {
            return String.valueOf(body.get());
        } catch (SecFilingSupplement.UnreadableFiling e) {
            return "UnreadableFiling " + (e.getCause() instanceof DataSourceException d ? d.kind() : "-")
                    + " :: " + e.getMessage();
        } catch (DataSourceException e) {
            return "DataSourceException " + e.kind() + " :: " + e.getMessage();
        } catch (Exception e) {
            return "throws " + e.getClass().getSimpleName() + " :: " + e.getMessage();
        }
    }

    /** {@code instance-mini.xml}과 같은 머리(컨텍스트 d·q·i · 단위 usd·shares)에 컨텍스트와 본문을 더한다. */
    private static String doc(String extraContexts, String body) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <xbrl xmlns="http://www.xbrl.org/2003/instance"
                      xmlns:dei="http://xbrl.sec.gov/dei/2025"
                      xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                      xmlns:gaap="http://fasb.org/us-gaap/2026"
                      xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance">
                  <context id="d"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                    <period><startDate>2026-01-01</startDate><endDate>2026-06-30</endDate></period></context>
                  <context id="q"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                    <period><startDate>2026-04-01</startDate><endDate>2026-06-30</endDate></period></context>
                  <context id="i"><entity><identifier scheme="http://www.sec.gov/CIK">0000001800</identifier></entity>
                    <period><instant>2026-06-30</instant></period></context>
                %s
                  <unit id="usd"><measure>iso4217:USD</measure></unit>
                  <unit id="shares"><measure>xbrli:shares</measure></unit>
                  <dei:DocumentType contextRef="d">10-Q</dei:DocumentType>
                  <dei:DocumentFiscalYearFocus contextRef="d">2026</dei:DocumentFiscalYearFocus>
                  <dei:DocumentFiscalPeriodFocus contextRef="d">Q2</dei:DocumentFiscalPeriodFocus>
                %s
                </xbrl>
                """.formatted(extraContexts, body);
    }

    /** 판 범위 소스 — 파일 순서·메서드 순서를 고정하고, 주석을 걷고 공백을 접는다. */
    private static String scopeSource() throws Exception {
        StringBuilder out = new StringBuilder();
        for (String file : new java.util.TreeSet<>(SOURCE_SCOPE.keySet())) {
            String code = stripComments(Files.readString(SOURCE_DIR.resolve(file)));
            List<String> heads = SOURCE_SCOPE.get(file);
            if (heads.isEmpty()) {
                code = code.replaceAll("static final int READER_VERSION = \\d+;", "");
                out.append("== ").append(file).append('\n').append(collapse(code)).append('\n');
                continue;
            }
            for (String head : heads) {
                int at = code.indexOf(head);
                assertThat(at).as("판 범위 메서드를 못 찾았다 — 이름을 바꿨으면 SOURCE_SCOPE도 고친다: " + file + " " + head)
                        .isGreaterThanOrEqualTo(0);
                out.append("== ").append(file).append(' ').append(head).append('\n')
                        .append(collapse(methodAt(code, at))).append('\n');
            }
        }
        return out.toString();
    }

    /** 머리부터 짝이 맞는 닫는 중괄호까지 — 주석은 이미 걷혔고 문자열·문자 리터럴 안의 중괄호는 안 센다. */
    private static String methodAt(String code, int start) {
        int depth = 0;
        boolean seenBody = false;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(code, i);
                continue;
            }
            if (c == '{') {
                depth++;
                seenBody = true;
            } else if (c == '}') {
                depth--;
                if (seenBody && depth == 0) {
                    return code.substring(start, i + 1);
                }
            }
        }
        throw new IllegalStateException("메서드 끝을 못 찾았다: " + code.substring(start, Math.min(code.length(), start + 80)));
    }

    /** 리터럴의 끝 인덱스. 텍스트 블록({@code """})·문자열·문자 리터럴과 이스케이프를 안다. */
    private static int skipLiteral(String code, int i) {
        char quote = code.charAt(i);
        if (quote == '"' && code.startsWith("\"\"\"", i)) {
            int end = code.indexOf("\"\"\"", i + 3);
            return end < 0 ? code.length() - 1 : end + 2;
        }
        for (int j = i + 1; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j;
            }
        }
        return code.length() - 1;
    }

    /** 줄 주석·블록 주석을 걷는다 — 문자열 안의 {@code //}(URL)는 남긴다. */
    private static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipLiteral(code, i);
                out.append(code, i, end + 1);
                i = end;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end - 1;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String collapse(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
