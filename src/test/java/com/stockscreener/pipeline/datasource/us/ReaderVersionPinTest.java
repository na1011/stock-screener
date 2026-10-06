package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장되는 것을 정하는 규칙을 {@link XbrlInstanceReader#READER_VERSION}에 묶는다(ADR-0173).
 *
 * <p>해석 실패({@code UNREADABLE})와 XBRL 없음({@code NO_XBRL}) 행은 판이 같은 동안 다시 받지 않는다. 그래서 규칙을 바꾸고 판을
 * 안 올리면 고친 규칙이 그 행에 영영 안 닿는다 — 리뷰가 그것을 잡는다고 믿지 않고 여기서 묻는다. 해시 둘을 판마다 박는다.
 * <ul>
 *   <li><b>소스 해시</b> — 판 범위 파일 전체를 javac로 파싱해 다시 찍은 정규형(주석·서식이 빠지고 리터럴은 값 그대로)의 해시.
 *       범위는 파일 단위다(2026-10-06 v7 반증 — 메서드 목록은 종료 상태를 정하는 자리 셋을 빠뜨렸고, 손으로 짠 주석 걷기는
 *       텍스트 블록 · 리터럴 안 공백 · 오버로드에서 샜다).</li>
 *   <li><b>출력 해시</b> — 고정 재료(해석 실패 자리마다 하나 · 특성 · 신원 · 이름 · 폴더)를 먹인 출력과 읽기 구현 · JDK 판.</li>
 * </ul>
 *
 * <p><b>붉으면</b>:
 * <ul>
 *   <li>소스 해시가 붉다 → <b>언제나</b> {@code READER_VERSION}을 올리고 두 해시를 {@link #SOURCE_PINNED}·{@link #PINNED} 끝에
 *       더한다. 리팩터여도 올린다 — 출력이 그대로인 것은 행동이 그대로라는 증거가 아니다(v6 반증 — 출력 해시를 지난 행동 변경이
 *       20개 중 12개 · v7 반증이 재현). 해시 방법(정규형)을 바꾼 것도 같다.</li>
 *   <li>소스 해시는 그대로이고 출력 해시만 붉다 → 재료를 더했으면 {@link #PINNED}의 지금 판 값만 고친다(같은 커밋에 재료 변경이
 *       있어야 한다). 재료가 그대로면 환경(JDK · 의존성)이 행동을 바꾼 것이라 판을 올린다.</li>
 * </ul>
 * 판을 올리는 대가는 저장 행을 다시 받는 것뿐이다(셈 — 사본 545행이면 색인·인스턴스 약 1,053콜).
 * MUST NOT: 박힌 해시를 고쳐 판을 안 올리지 않는다 — 그것이 「판을 안 올리고 규칙을 바꾼 것」이다.
 */
class ReaderVersionPinTest {

    /** 판 1, 2, …의 판 범위 소스 해시. 끝에만 더한다(판 1은 옛 정규형 · 메서드 범위). */
    private static final List<String> SOURCE_PINNED = List.of(
            "d1361d139cdfa74bb695997d9fea491ba9af9f3be6f5cebcd0cc9459f698b18c",
            "c713c64d6a4a6dd9e4e6f04267463b2877dde8ea6eeac50b46ee8847d182423f");

    /**
     * 판 범위 — 저장되는 것(상태 · 원 팩트 · 머리 · 실패 종류)을 정하는 파일 전부, 파일 전체다({@code READER_VERSION} 선언만
     * 뺀다). 받기(이름 목록 · 바이트) → 읽기 → 고르기 · 신원 · 상태 분류 → 저장 형태.
     */
    private static final List<String> SOURCE_SCOPE = List.of(
            "FilingSupplementer.java", "SecFilingClient.java", "SecFilingSupplement.java",
            "SecFilingSupplementStore.java", "SupplementLedger.java", "XbrlInstanceReader.java");

    /** 범위 파일이 코드로 부르는 같은 패키지 형 중 범위 밖인 것과 그 사유 — 셋 다 다시 받는 실패만 낼 수 있다. */
    private static final Map<String, String> OUTSIDE_SCOPE = Map.of(
            "SecEdgarResponse", "비2xx의 실패 종류 · JSON 요건 — 종료 상태(OK · NO_XBRL · UNREADABLE)를 못 만든다",
            "SecEdgarHeaders", "요청 머리 — 틀리면 403이라 다시 받는다",
            "SecTickerCikCache", "CIK 0 채우기 — 틀리면 404라 다시 받는다");

    private static final Path SOURCE_DIR = Path.of("src/main/java/com/stockscreener/pipeline/datasource/us");

    /** 판 1, 2, …의 출력 해시. 끝에만 더한다. */
    private static final List<String> PINNED = List.of(
            "c8e8a6e90b0cf4786efd0fc10bacee5099b4babeb6cdd2f61666f43bb53ee982",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1");

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
    @DisplayName("해석 규칙의 출력이 지금 판에 박힌 해시와 같다 — 환경이나 규칙이 바뀌고 판을 안 올리면 붉다")
    void rulesArePinnedToTheReaderVersion() throws Exception {
        assertThat(PINNED).as("판 번호와 박힌 해시 수가 같아야 한다 — 판을 올리면 해시를 끝에 더한다")
                .hasSize(XbrlInstanceReader.READER_VERSION);
        assertThat(digest(canonicalOutput()))
                .as("판 범위의 출력이 바뀌었다 — 재료를 더했으면 PINNED의 지금 판 값만 고치고, 재료가 그대로면(환경이 바뀜) "
                        + "READER_VERSION을 올리고 새 해시를 끝에 더한다(ADR-0173)")
                .isEqualTo(PINNED.get(XbrlInstanceReader.READER_VERSION - 1));
    }

    @Test
    @DisplayName("판 범위의 소스가 지금 판에 박힌 해시와 같다 — 소스를 한 토큰이라도 바꾸면 판을 올린다")
    void ruleSourcesArePinnedToTheReaderVersion() throws Exception {
        assertThat(SOURCE_PINNED).as("판 번호와 박힌 소스 해시 수가 같아야 한다").hasSize(XbrlInstanceReader.READER_VERSION);
        assertThat(digest(scopeSource()))
                .as("판 범위의 소스가 바뀌었다 — 리팩터여도 READER_VERSION을 올리고 두 해시를 끝에 더한다(ADR-0173)")
                .isEqualTo(SOURCE_PINNED.get(XbrlInstanceReader.READER_VERSION - 1));
    }

    @Test
    @DisplayName("판 범위 파일이 코드로 부르는 같은 패키지 형은 범위 안이거나, 종료 상태를 못 만드는 사유가 적힌 예외다")
    void everyPackageTypeTheScopeCallsIsInScopeOrExcused() throws Exception {
        Set<String> packageTypes;
        try (Stream<Path> files = Files.list(SOURCE_DIR)) {
            packageTypes = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - 5)).collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> called = new TreeSet<>();
        for (String file : SOURCE_SCOPE) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    called.add(node.getName().toString());
                    return null;
                }
            }.scan(parse(SOURCE_DIR.resolve(file)), null);
        }
        called.retainAll(packageTypes);
        SOURCE_SCOPE.forEach(f -> called.remove(f.substring(0, f.length() - 5)));
        assertThat(called).as("범위 밖 형을 새로 부르면 범위에 넣거나, 종료 상태를 못 만드는 사유를 OUTSIDE_SCOPE에 적는다")
                .isEqualTo(new TreeSet<>(OUTSIDE_SCOPE.keySet()));
    }

    @Test
    @DisplayName("정규형은 주석과 서식만 버린다 — 리터럴 안 공백 · 텍스트 블록 · 문자열 안 // 뒤의 코드는 갈린다")
    void canonicalFormDropsOnlyCommentsAndLayout() throws Exception {
        String base = """
                package p;
                /** 문서 */
                class X {
                    // 줄 주석
                    int f(String s) { /* 블록 */ return s.equals("a b // c") ? g(1) : 2; }
                    int f(int i) { return i; }
                    String t = \"""
                        hi \\\""" x
                        \""";
                    int g(int i) { return i; }
                }
                """;
        String commentsOnly = base.replace("/** 문서 */", "/** 다른 문서\n * 줄 */").replace("// 줄 주석", "// 다른 주석")
                .replace("/* 블록 */", "/* 다른 블록 */").replace("    int g(", "\n        int g(");
        assertThat(canonicalSource("X.java", commentsOnly)).as("주석·서식만 바꿈").isEqualTo(canonicalSource("X.java", base));
        for (String changed : List.of(
                base.replace("\"a b // c\"", "\"a  b // c\""),
                base.replace("\"a b // c\") ? g(1)", "\"a b // c\") ? g(2)"),
                base.replace("hi \\\"\"\" x", "hi \\\"\"\" y"),
                base.replace("int f(int i) { return i; }", "int f(int i) { return -i; }"),
                base.replace("int g(int i) { return i; }", "int g(int i) { return i + 1; }"))) {
            assertThat(changed).isNotEqualTo(base);
            assertThat(canonicalSource("X.java", changed)).as("코드 한 토큰").isNotEqualTo(canonicalSource("X.java", base));
        }
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
        // 읽기 구현과 JDK 판 — 소스 밖에서 행동을 바꾸는 둘이다(v7 반증 — StAX 서비스 조회).
        StringBuilder out = new StringBuilder("parser ").append(XbrlInstanceReader.parserImplementation())
                .append(" jdk ").append(Runtime.version().feature()).append('\n');
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

    /** 판 범위 소스 — 파일마다 javac 정규형을 이름 순서로 잇는다. {@code READER_VERSION}의 값만 지운다. */
    private static String scopeSource() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String file : new TreeSet<>(SOURCE_SCOPE)) {
            String code = canonicalSource(SOURCE_DIR.resolve(file));
            if (file.equals("XbrlInstanceReader.java")) {
                Pattern version = Pattern.compile("static final int READER_VERSION = \\d+;");
                assertThat(version.matcher(code).results().count()).as("판 선언이 정규형에 한 번 있어야 한다").isEqualTo(1);
                code = version.matcher(code).replaceAll("static final int READER_VERSION = _;");
            }
            out.append("== ").append(file).append('\n').append(code);
        }
        return out.toString();
    }

    /**
     * javac가 파싱한 트리를 다시 찍은 것 — 줄·블록 주석과 서식은 트리에 없고, 리터럴은 값으로 찍힌다(공백 그대로 · 텍스트 블록은
     * 이스케이프한 한 줄). 문서 주석만 트리 출력에 남으므로({@code /**}·{@code  * …}·{@code  *}{@code /} 줄) 그 줄들을 걷는다 —
     * 리터럴은 늘 한 줄이라 그 꼴의 줄이 코드에서 나올 수 없다.
     */
    private static String canonicalSource(Path file) throws IOException {
        return canonicalSource(file.getFileName().toString(), Files.readString(file));
    }

    private static String canonicalSource(String name, String source) throws IOException {
        StringBuilder out = new StringBuilder();
        boolean inDoc = false;
        for (String line : parse(name, source).toString().split("\n", -1)) {
            String trimmed = line.strip();
            if (inDoc) {
                inDoc = !trimmed.equals("*/");
            } else if (trimmed.equals("/**")) {
                inDoc = true;
            } else {
                out.append(line).append('\n');
            }
        }
        assertThat(inDoc).as("문서 주석이 안 닫혔다 — 트리 출력의 문서 주석 꼴이 바뀌었다: " + name).isFalse();
        return out.toString();
    }

    private static CompilationUnitTree parse(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readString(file));
    }

    private static CompilationUnitTree parse(String name, String source) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask task = (JavacTask) javac.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(unit));
        CompilationUnitTree tree = task.parse().iterator().next();
        assertThat(diagnostics.getDiagnostics()).as("판 범위 소스를 파싱하지 못했다: " + name).isEmpty();
        return tree;
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
