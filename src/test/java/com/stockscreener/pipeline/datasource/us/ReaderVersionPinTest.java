package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * <p><b>붉으면 판을 올린다 — 언제나.</b> 두 목록({@link #SOURCE_PINNED}·{@link #PINNED})은 덧붙이기만 한다: 소스든 재료든 환경
 * (JDK · 의존성)이든 지금 판 값과 다르면 {@code READER_VERSION}을 올리고 두 해시를 끝에 더한다. 리팩터여도 올린다 — 출력이
 * 그대로인 것은 행동이 그대로라는 증거가 아니다(v6 반증 — 출력 해시를 지난 행동 변경이 20개 중 12개 · v7 반증이 재현).
 * 재료만 더했어도 올린다 — 지금 판 값을 고치는 갈래를 두면 「재료를 바꿨다」를 사람이 판정하게 되고 그것을 기계가 못 막는다
 * (v8 반증). 덧붙이기만 했는지는 {@link #pinnedListsOnlyGrow}가 git 이력으로 묻는다. 판을 올리는 대가는 저장 행을 다시
 * 받는 것뿐이다(셈 — 사본 545행이면 색인·인스턴스 약 1,053콜).
 */
class ReaderVersionPinTest {

    /** 판 1, 2, …의 판 범위 소스 해시. 끝에만 더한다(판 1은 옛 정규형 · 메서드 범위 · 판 2는 형 종류 없는 정규형). */
    private static final List<String> SOURCE_PINNED = List.of(
            "d1361d139cdfa74bb695997d9fea491ba9af9f3be6f5cebcd0cc9459f698b18c",
            "c713c64d6a4a6dd9e4e6f04267463b2877dde8ea6eeac50b46ee8847d182423f",
            "e44657c52f69e940e9a083e0d76801aea8f561cb218e67be3509d1761c002bb7",
            "7d27b748e3ffb6c804a9fb0c963e3b0d1a241b1676d74277d1a46bb919a92efc",
            "0e1038c9921de8acebeefdc8cd55aaa8b6e197c021a63d9cd91267f3d518af40",
            "e8bf8893ee9c89efc789f6258e79ba09aa2403adc897a424e5232be3e1294550",
            "883f9182385814ed3d8665a6f11e8a8527997a254ba81d2a71cd4f2cf1390dfa",
            "40730ea9623554de8a283f275345861cc5fb6a82d94477df2027fd2a17c24d5a");

    /**
     * 판 범위 — 저장되는 것(상태 · 원 팩트 · 머리 · 실패 종류)을 정하는 파일 전부, 파일 전체다({@code READER_VERSION} 선언만
     * 뺀다). 받기(이름 목록 · 크기 · 바이트 · 색인 JSON) → 읽기 → 고르기 · 신원 · 상태 분류 → 저장 형태.
     */
    private static final List<String> SOURCE_SCOPE = List.of(
            "FilingSupplementer.java", "SecEdgarResponse.java", "SecFilingClient.java", "SecFilingSupplement.java",
            "SecFilingSupplementStore.java", "SupplementLedger.java", "XbrlInstanceReader.java");

    /**
     * 범위 파일이 쓰는 프로젝트 형({@code com.stockscreener.*}) 중 범위 밖인 것과 그 사유 — 종료 상태(OK · NO_XBRL ·
     * UNREADABLE · COVERED)나 저장 내용을 못 바꾼다는 근거다. javac 속성 분석으로 센다(static import · 완전한 이름 포함).
     */
    private static final Map<String, String> OUTSIDE_SCOPE = Map.of(
            "com.stockscreener.pipeline.datasource.DataSourceException",
            "실패 운반체 — 종료(UNREADABLE)는 범위 안이 정하고 이 형의 종류는 전부 다시 받는다(Kind에 UNREADABLE 없음 · 아래 시험)",
            "com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey", "빈 등록 조건 — 키가 없으면 보충 자체가 안 돈다",
            "com.stockscreener.pipeline.datasource.http.DataSourceProperties",
            "주소 · 머리 · 속도 설정 — 틀리면 받기 실패이고 받은 본문은 색인 크기로 맞춘다",
            "com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory",
            "전송 — 끊긴 본문 · 오류 본문은 색인 크기 대조가 받기 실패로 돌리고, 깨진 색인 JSON은 MALFORMED다",
            "com.stockscreener.pipeline.datasource.http.RateLimiter", "호출 간격",
            "com.stockscreener.pipeline.datasource.us.SecEdgarHeaders",
            "요청 머리 — 틀리면 403이거나 본문 인코딩이 달라지는데 받은 본문은 색인 크기로 맞춘다",
            "com.stockscreener.pipeline.datasource.us.SecTickerCikCache", "CIK 0 채우기 — 틀리면 공시 목록 404라 다시 받는다");

    private static final Path SOURCE_DIR = Path.of("src/main/java/com/stockscreener/pipeline/datasource/us");

    /** 판 1, 2, …의 출력 해시. 끝에만 더한다. */
    private static final List<String> PINNED = List.of(
            "c8e8a6e90b0cf4786efd0fc10bacee5099b4babeb6cdd2f61666f43bb53ee982",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",
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
    @DisplayName("해석 규칙의 출력이 지금 판에 박힌 해시와 같다 — 재료 · 환경 · 규칙이 바뀌고 판을 안 올리면 붉다")
    void rulesArePinnedToTheReaderVersion() throws Exception {
        assertThat(PINNED).as("판 번호와 박힌 해시 수가 같아야 한다 — 판을 올리면 해시를 끝에 더한다")
                .hasSize(XbrlInstanceReader.READER_VERSION);
        assertThat(digest(canonicalOutput()))
                .as("판 범위의 출력이 바뀌었다(재료 · 환경 · 규칙) — READER_VERSION을 올리고 두 해시를 끝에 더한다(ADR-0173)")
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
    @DisplayName("판 범위 파일이 쓰는 프로젝트 형은 범위 안이거나, 저장을 못 바꾸는 사유가 적힌 예외다 — javac 속성 분석으로 센다")
    void everyProjectTypeTheScopeUsesIsInScopeOrExcused() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Set<String> used = new TreeSet<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-classpath", System.getProperty("java.class.path")), null,
                    files.getJavaFileObjectsFromPaths(SOURCE_SCOPE.stream().map(SOURCE_DIR::resolve).toList()));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            assertThat(diagnostics.getDiagnostics()).as("판 범위 소스를 속성 분석하지 못했다")
                    .noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        record();
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        record();
                        return super.visitMemberSelect(node, unused);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        record();
                        return super.visitNewClass(node, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                        record();
                        return super.visitMemberReference(node, unused);
                    }

                    private void record() {
                        TypeElement top = topLevel(trees.getElement(getCurrentPath()));
                        if (top != null) {
                            used.add(top.getQualifiedName().toString());
                        }
                    }
                }.scan(unit, null);
            }
        }
        String scopePackage = "com.stockscreener.pipeline.datasource.us.";
        used.removeIf(name -> !name.startsWith("com.stockscreener."));
        SOURCE_SCOPE.forEach(f -> used.remove(scopePackage + f.substring(0, f.length() - 5)));
        assertThat(used).as("범위 밖 프로젝트 형을 새로 쓰면 범위에 넣거나, 저장을 못 바꾸는 사유를 OUTSIDE_SCOPE에 적는다")
                .isEqualTo(new TreeSet<>(OUTSIDE_SCOPE.keySet()));
    }

    @Test
    @DisplayName("판 범위의 형을 범위 밖에서 상속·구현하지 않는다 — 주입되는 구현은 javac 참조 분석에 안 보인다")
    void scopeTypesAreSubtypedOnlyInScope() throws Exception {
        // 파싱 트리로 묻는다(주석에 안 걸린다 · 어노테이션 처리기 없이 선다). v10 반증의 변이 다섯 — 하위 인터페이스 · 범위 클래스
        // 상속 + @Primary · @Bean이 범위 형의 익명 하위 클래스를 돌려줌 · 보충 · 받기 상속 — 이 다 이 꼴이다.
        Set<String> scopeTypes = new TreeSet<>();
        SOURCE_SCOPE.forEach(f -> scopeTypes.add(f.substring(0, f.length() - 5)));
        Set<String> scopeInterfaces = Set.of("SupplementLedger", "FilingSupplementer");
        Set<String> offenders = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            files = walk.filter(f -> f.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            if (file.getParent().equals(SOURCE_DIR) && SOURCE_SCOPE.contains(file.getFileName().toString())) {
                continue;
            }
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    List<Tree> supers = new ArrayList<>(node.getImplementsClause());
                    if (node.getExtendsClause() != null) {
                        supers.add(node.getExtendsClause());
                    }
                    supers.forEach(t -> flag(t, scopeTypes, "상속·구현"));
                    return super.visitClass(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (node.getClassBody() != null) {
                        flag(node.getIdentifier(), scopeTypes, "익명 하위 클래스");
                    }
                    return super.visitNewClass(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if (node.getReturnType() != null) {
                        flag(node.getReturnType(), scopeInterfaces, "범위 인터페이스를 돌려주는 메서드");
                    }
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getInitializer() instanceof LambdaExpressionTree
                            || node.getInitializer() instanceof MemberReferenceTree) {
                        flag(node.getType(), scopeInterfaces, "범위 인터페이스 람다");
                    }
                    return super.visitVariable(node, unused);
                }

                private void flag(Tree type, Set<String> names, String how) {
                    String text = type.toString();
                    int generic = text.indexOf('<');
                    String raw = generic < 0 ? text : text.substring(0, generic);
                    String simple = raw.substring(raw.lastIndexOf('.') + 1).strip();
                    if (names.contains(simple)) {
                        offenders.add(file + " — " + how + " " + simple);
                    }
                }
            }.scan(parse(file), null);
        }
        assertThat(offenders).as("범위 밖에서 받기·저장·보충을 바꾸면 판 고정이 그 행동을 못 본다 — 그 파일을 범위에 넣는다").isEmpty();
    }

    @Test
    @DisplayName("스프링 빈은 판 범위 인터페이스를 받지도 내지도 않는다 — 운영 배선은 범위 클래스를 구체형으로 잇는다")
    void noBeanConsumesOrProducesAScopeInterface() throws Exception {
        // 구문 검사(위)는 범위 인터페이스를 범위 밖에서 구현하는 람다 · 조건식 람다 · Proxy · 생성자 인자 람다를 못 본다(v11 반증 —
        // 네 꼴이 지났다). 그 구현이 운영에 닿는 길은 주입뿐이므로 길을 닫는다: 빈의 생성자 · @Autowired 필드 · @Bean 메서드가
        // 범위 인터페이스를 받거나 내면 붉다. 그러면 범위 형을 바꿀 길은 범위 클래스의 하위 클래스뿐이고 위 검사가 그것을 막는다.
        Set<String> scopeInterfaces = Set.of("SupplementLedger", "FilingSupplementer");
        Path classes = Path.of(SecFilingClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> offenders = new TreeSet<>();
        int beans = 0;
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                List<Class<?>> taken = new ArrayList<>();
                if (AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                    beans++;
                    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                        if (constructor.isAnnotationPresent(Autowired.class) || type.getDeclaredConstructors().length == 1) {
                            taken.addAll(List.of(constructor.getParameterTypes()));
                        }
                    }
                    for (Field field : type.getDeclaredFields()) {
                        if (field.isAnnotationPresent(Autowired.class)) {
                            taken.add(field.getType());
                        }
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (AnnotatedElementUtils.hasAnnotation(method, Bean.class) || method.isAnnotationPresent(Autowired.class)) {
                        taken.add(method.getReturnType());
                        taken.addAll(List.of(method.getParameterTypes()));
                    }
                }
                for (Class<?> t : taken) {
                    if (t.getPackageName().equals(SecFilingClient.class.getPackageName()) && scopeInterfaces.contains(t.getSimpleName())) {
                        offenders.add(name + " — " + t.getSimpleName());
                    }
                }
            }
        }
        assertThat(beans).as("빈을 하나도 못 찾았다면 이 검사가 아무것도 안 본 것이다").isGreaterThan(50);
        assertThat(offenders).as("범위 인터페이스를 빈 배선에 쓰면 범위 밖 구현이 주입될 수 있다 — 범위 클래스를 구체형으로 받는다").isEmpty();
    }

    /** 범위 빈의 주입 자리에 와도 되는 범위 밖 형 — 범위 형과 {@link #OUTSIDE_SCOPE} 말고는 이것뿐이다. */
    private static final Map<String, String> INJECTABLE_LIBRARY = Map.of(
            "org.springframework.jdbc.core.JdbcTemplate", "원장 표의 읽기·쓰기 통로 — 저장 형태는 범위 안 SQL이 정한다");

    @Test
    @DisplayName("범위 빈의 주입 자리 형은 범위 형 · 사유가 적힌 범위 밖 형 · 허용 라이브러리뿐이다 — JDK 함수형을 받으면 그 구현이 폐포 밖이다")
    void scopeBeansInjectOnlyKnownTypes() throws Exception {
        // v14 반증 e5: 범위 클라이언트의 운영 생성자가 UnaryOperator<String>을 받게 하고 그 구현(새 빈)의 본문만 바꾸자, 폐포는 JDK
        // 형에서 멈추고 배선 검사는 범위 인터페이스 둘만 봐 전부 초록인데 같은 색인이 OK → UNREADABLE이 됐다.
        Set<String> offenders = new TreeSet<>();
        for (String file : SOURCE_SCOPE) {
            Class<?> type = Class.forName("com.stockscreener.pipeline.datasource.us." + file.substring(0, file.length() - 5));
            if (!AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                continue;
            }
            List<Class<?>> taken = new ArrayList<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Autowired.class) || type.getDeclaredConstructors().length == 1) {
                    taken.addAll(List.of(constructor.getParameterTypes()));
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    taken.add(field.getType());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Autowired.class)) {
                    taken.addAll(List.of(method.getParameterTypes()));
                }
            }
            for (Class<?> t : taken) {
                String name = t.getName();
                Class<?> top = t;
                while (top.getEnclosingClass() != null) {
                    top = top.getEnclosingClass();
                }
                boolean inScope = top.getPackageName().equals(SecFilingClient.class.getPackageName())
                        && SOURCE_SCOPE.contains(top.getSimpleName() + ".java");
                if (!inScope && !OUTSIDE_SCOPE.containsKey(top.getName()) && !INJECTABLE_LIBRARY.containsKey(name)) {
                    offenders.add(type.getSimpleName() + " — " + name);
                }
            }
        }
        assertThat(offenders).as("범위 빈이 모르는 형을 주입받으면 그 구현이 판 고정 밖에서 저장을 바꾼다 — 범위에 넣거나 사유를 적는다")
                .isEmpty();
    }

    @Test
    @DisplayName("범위 소스의 주입 주석은 운영 생성자 위 @Autowired · 매개변수 @Qualifier뿐이다 — 필드 · 메서드 주입은 주입 자리 검사 밖이다")
    void scopeSourcesInjectOnlyThroughTheConstructor() throws Exception {
        // v15 반증 e6: JDK 형 빈을 @Resource 필드로 받게 하자 주입 자리 검사(@Autowired 필드·메서드만 셈)를 지나 본문만 바꾼
        // 변이가 초록이었다(Spring이 실제로 넣는 것을 컨텍스트로 확인). 손 목록을 늘리지 않고 성질로 묻는다 — 주입 계열 주석이
        // 생성자 위 @Autowired와 그 매개변수의 @Qualifier 말고 어디에도 없다.
        Set<String> injectionPackages = Set.of("org.springframework.beans.factory.annotation", "jakarta.annotation",
                "jakarta.inject", "javax.inject", "javax.annotation");
        Set<String> offenders = new TreeSet<>();
        for (String file : SOURCE_SCOPE) {
            CompilationUnitTree unit = parse(SOURCE_DIR.resolve(file));
            Map<String, String> imports = new java.util.HashMap<>();
            unit.getImports().forEach(i -> {
                String name = i.getQualifiedIdentifier().toString();
                imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            });
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitAnnotation(com.sun.source.tree.AnnotationTree node, Void unused) {
                    String written = node.getAnnotationType().toString();
                    String qualified = written.contains(".") ? written : imports.getOrDefault(written, written);
                    String pkg = qualified.contains(".") ? qualified.substring(0, qualified.lastIndexOf('.')) : "";
                    if (injectionPackages.contains(pkg)) {
                        Tree parent = getCurrentPath().getParentPath().getParentPath().getLeaf();
                        boolean constructorAutowired = qualified.endsWith(".Autowired") && parent instanceof MethodTree method
                                && method.getName().contentEquals("<init>");
                        boolean parameterQualifier = qualified.endsWith(".Qualifier") && parent instanceof VariableTree
                                && getCurrentPath().getParentPath().getParentPath().getParentPath().getLeaf() instanceof MethodTree ctor
                                && ctor.getName().contentEquals("<init>");
                        if (!constructorAutowired && !parameterQualifier) {
                            offenders.add(file + " — @" + qualified);
                        }
                    }
                    return super.visitAnnotation(node, unused);
                }
            }.scan(unit, null);
        }
        assertThat(offenders).as("필드·메서드·다른 주입 주석은 범위 빈의 입력을 주입 자리 검사 밖에서 바꾼다").isEmpty();
    }

    @Test
    @DisplayName("범위 소스는 리플렉션 · ServiceLoader · 동적 클래스 로딩을 쓰지 않는다 — 폐포가 그 대상을 정적으로 못 본다")
    void scopeSourcesDoNotLoadCodeDynamically() throws Exception {
        Set<String> offenders = new TreeSet<>();
        Set<String> bannedTypes = Set.of("java.util.ServiceLoader", "java.lang.ClassLoader");
        Set<String> bannedPackages = Set.of("java.lang.reflect", "java.lang.invoke");
        Set<String> bannedClassMethods = Set.of("forName", "getMethod", "getDeclaredMethod", "getConstructor",
                "getDeclaredConstructor", "getField", "getDeclaredField", "newInstance");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-classpath", System.getProperty("java.class.path")), null,
                    files.getJavaFileObjectsFromPaths(SOURCE_SCOPE.stream().map(SOURCE_DIR::resolve).toList()));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        check();
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        check();
                        return super.visitMemberSelect(node, unused);
                    }

                    private void check() {
                        Element element = trees.getElement(getCurrentPath());
                        TypeElement top = topLevel(element);
                        if (element == null || top == null) {
                            return;
                        }
                        String topName = top.getQualifiedName().toString();
                        String pkg = topName.contains(".") ? topName.substring(0, topName.lastIndexOf('.')) : "";
                        boolean banned = bannedTypes.contains(topName) || bannedPackages.contains(pkg)
                                || (topName.equals("java.lang.Class") && element.getKind() == ElementKind.METHOD
                                && bannedClassMethods.contains(element.getSimpleName().toString()));
                        if (banned) {
                            offenders.add(unit.getSourceFile().getName() + " — " + topName + "#" + element.getSimpleName());
                        }
                    }
                }.scan(unit, null);
            }
        }
        assertThat(offenders).as("동적으로 들인 코드는 판 고정 폐포가 못 본다(v14 반증 처방 c)").isEmpty();
    }

    @Test
    @DisplayName("받기 실패의 종류에 UNREADABLE이 없다 — 있으면 받기 실패가 다시 받지 않는 종료 상태로 저장된다")
    void fetchFailureKindsNeverCollideWithTheTerminalKind() {
        assertThat(java.util.Arrays.stream(DataSourceException.Kind.values()).map(Enum::name))
                .doesNotContain(SecFilingSupplement.UNREADABLE);
    }

    @Test
    @DisplayName("박힌 해시는 덧붙이기만 한다 — 판 번호가 같은 채 값이 바뀐 커밋이 없다(v8부터 · git 메인라인 이력)")
    void pinnedListsOnlyGrow() throws Exception {
        // git 저장소가 아니면(git archive 사본) 이력이 없어 물을 수 없다 — 건너뛴 것으로 드러나게 둔다.
        org.junit.jupiter.api.Assumptions.assumeTrue(isGitWorkTree(), "git 작업 트리가 아니다 — 덧붙이기 검사를 못 한다");
        String path = "src/test/java/com/stockscreener/pipeline/datasource/us/ReaderVersionPinTest.java";
        // --first-parent: 메인라인만 걷는다. 날짜 순으로 두 가지를 섞으면 따로 판을 올린 두 가지를 merge한 뒤 영영 붉다
        // (v9 반증 — 같은 트리를 한 줄 이력으로 두면 초록 · merge면 붉음 · --first-parent면 초록).
        List<String> commits = new ArrayList<>(git("log", "--first-parent", "--format=%H", APPEND_ONLY_SINCE + "^..HEAD",
                "--", path).lines().filter(l -> !l.isBlank()).toList());
        java.util.Collections.reverse(commits);
        assertThat(commits).as("덧붙이기 검사의 시작 커밋이 이력에 있어야 한다").isNotEmpty();
        List<String> versions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        // main에 merge 커밋으로 합친 뒤에는 그 merge가 메인라인의 첫 커밋이고 첫 부모에 이 파일이 없다 — 그러면 둘째 부모(합친
        // 가지 끝)의 판을 기준선으로 둔다. 안 그러면 merge 안에서 생긴 제자리 수정이 어느 쪽에서도 안 보인다(v10 반증).
        String first = commits.get(0);
        if (!gitOk("cat-file", "-e", first + "^1:" + path) && gitOk("cat-file", "-e", first + "^2:" + path)) {
            versions.add(git("show", first + "^2:" + path));
            labels.add(first + "^2");
        }
        for (String commit : commits) {
            versions.add(git("show", commit + ":" + path));
            labels.add(commit);
        }
        versions.add(Files.readString(Path.of(path)));
        labels.add("작업 트리");
        for (int i = 1; i < versions.size(); i++) {
            for (String list : List.of("SOURCE_PINNED", "PINNED")) {
                List<String> before = pinned(versions.get(i - 1), list);
                List<String> after = pinned(versions.get(i), list);
                String where = labels.get(i);
                assertThat(after).as(list + "은 덧붙이기만 한다 — 지금 판 값을 고치지 말고 판을 올린다: " + where)
                        .startsWith(before.toArray(String[]::new));
            }
        }
    }

    /**
     * 덧붙이기 검사를 시작하는 커밋 — 이 앞은 배포 전이라 지금 판 값을 고치던 시기다(v8 · 2026-10-06). MUST: 이 브랜치는 main에
     * merge 커밋으로 합친다 — 리베이스·체리픽은 이 앞의 커밋 사본(제자리 수정 · 선언 없음)을 범위에 넣어 이 시험이 영구 붉다.
     */
    private static final String APPEND_ONLY_SINCE = "066c325a";

    private static List<String> pinned(String source, String list) {
        Matcher m = Pattern.compile("\\b" + list + " = List\\.of\\(([^;]*?)\\);", Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as(list + " 선언을 못 찾았다").isTrue();
        return Pattern.compile("\"([0-9a-fA-Z]+)\"").matcher(m.group(1)).results().map(r -> r.group(1)).toList();
    }

    private static boolean isGitWorkTree() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 && out.equals("true");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static boolean gitOk(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + " → " + out).isZero();
        return out;
    }

    private static TypeElement topLevel(Element element) {
        TypeElement top = null;
        for (Element e = element; e != null && e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
            if (e instanceof TypeElement type) {
                top = type;
            }
        }
        return top;
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
        // 트리 출력만으로는 같은 글이다(성분 0 레코드 · 내부 클래스 — 둘 다 「class R { }」) — 형 종류가 가른다.
        assertThat(canonicalSource("Y.java", "package p;\nclass Y {\n    record R() { }\n}\n")).as("레코드와 내부 클래스")
                .isNotEqualTo(canonicalSource("Y.java", "package p;\nclass Y {\n    class R { }\n}\n"));
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
        out.append(outsideMemberSource());
        return out.toString();
    }

    /**
     * 판 범위가 닿는 범위 밖 프로젝트 멤버의 정규형 — 범위 소스에서 시작해, 그 멤버의 선언이 다시 부르는 프로젝트 멤버까지 닫는다.
     *
     * <p>면제({@link #OUTSIDE_SCOPE})를 형 단위로만 주면 면제된 형에 새 멤버를 만들어 규칙을 옮긴 뒤 그것만 바꿔도 소스 해시가
     * 그대로였다(v11 반증 — {@code isLinkbase} 본문을 {@code SecTickerCikCache}로 옮기고 도우미만 바꾸자 고정 시험이 전부 초록인데
     * {@code pickInstance}의 결과가 종료 상태에서 OK로 바뀌었다). 그래서 닿는 멤버의 선언 전체(본문 · 초기값)를 싣는다. 형을
     * 참조하면 그 형의 머리(종류 · 수식어 · 상위 형 · 열거 상수 · 레코드 성분 · 초기화 블록)를 싣는다 — 다른 멤버는 닿을 때만.
     * <b>디스패치도 따라간다</b>(v12 반증 — 규칙을 면제 형의 {@code Predicate} 구현 클래스로 옮긴 뒤 그 {@code test}만 바꾸면 폐포가
     * 머리와 생성자만 실어 전부 초록이었다): 도달한 생성자의 클래스는 <b>클래스 전체</b>를 싣고, 도달한 프로젝트 형의 범위 밖 하위형
     * (구현 · 상속 — 주입되는 구현 {@code RateLimiter} → {@code TokenBucketRateLimiter} 포함)도 클래스 전체를 싣는다. 이름 없는
     * 하위형(익명 · 지역)은 이름만 싣는다 — 늘거나 바뀌면 해시가 갈린다.
     */
    private static String outsideMemberSource() throws IOException {
        Path main = Path.of("src/main/java");
        Set<String> scopeTypes = new TreeSet<>();
        SOURCE_SCOPE.forEach(f -> scopeTypes.add("com.stockscreener.pipeline.datasource.us." + f.substring(0, f.length() - 5)));
        Set<Path> files = new TreeSet<>(SOURCE_SCOPE.stream().map(SOURCE_DIR::resolve).toList());
        for (int round = 0; ; round++) {
            assertThat(round).as("범위 밖 멤버 폐포가 수렴하지 않는다").isLessThan(20);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            Map<String, String> out = new TreeMap<>();
            Set<Path> missing = new TreeSet<>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                JavacTask task = (JavacTask) javac.getTask(null, fm, diagnostics,
                        List.of("-proc:none", "-classpath", System.getProperty("java.class.path")), null,
                        fm.getJavaFileObjectsFromPaths(files));
                Iterable<? extends CompilationUnitTree> units = task.parse();
                task.analyze();
                assertThat(diagnostics.getDiagnostics()).as("범위 밖 멤버 폐포를 속성 분석하지 못했다")
                        .noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
                Trees trees = Trees.instance(task);
                Deque<Element> work = new ArrayDeque<>();
                for (CompilationUnitTree unit : units) {
                    if (scopeTypes.contains(topLevelName(unit))) {
                        collectReferences(trees, new TreePath(unit), work);
                    }
                }
                Set<String> seen = new TreeSet<>();
                Set<String> wholeSeen = new TreeSet<>();
                while (!work.isEmpty()) {
                    Element element = work.pop();
                    TypeElement top = topLevel(element);
                    String topName = top == null ? "" : top.getQualifiedName().toString();
                    if (!topName.startsWith("com.stockscreener.") || scopeTypes.contains(topName)) {
                        continue;
                    }
                    String key = memberKey(element);
                    if (!seen.add(key)) {
                        continue;
                    }
                    Path file = main.resolve(topName.replace('.', '/') + ".java");
                    if (!files.contains(file)) {
                        missing.add(file);
                        continue;
                    }
                    TypeElement owner = element instanceof TypeElement type ? type
                            : (TypeElement) element.getEnclosingElement();
                    if (!(element instanceof TypeElement)) {
                        work.push(owner);
                    }
                    // 디스패치: 만든 클래스 · 범위 밖 하위형은 클래스 전체를 싣는다.
                    List<TypeElement> whole = new ArrayList<>();
                    if (element.getKind() == ElementKind.CONSTRUCTOR) {
                        whole.add(owner);
                    }
                    for (String subtype : projectSubtypes(task.getElements().getBinaryName(owner).toString())) {
                        TypeElement sub = task.getElements().getTypeElement(subtype.replace('$', '.'));
                        if (sub == null || sub.getNestingKind().isNested() && sub.getSimpleName().isEmpty()) {
                            // 이름 없는 하위형(익명 · 지역)은 본문을 따로 못 집으므로 감싼 최상위 클래스를 통째로 싣는다 — 이름만 실으면
                            // 그 본문만 바꾼 변이가 초록이었다(v14 반증 e1 · @Bean @Primary 익명 하위형).
                            out.put("하위형 " + subtype, "이름 없음 — 감싼 최상위 클래스 전체로");
                            int dollar = subtype.indexOf('$');
                            TypeElement enclosing = dollar < 0 ? null
                                    : task.getElements().getTypeElement(subtype.substring(0, dollar));
                            if (enclosing != null) {
                                whole.add(enclosing);
                            }
                            continue;
                        }
                        whole.add(sub);
                    }
                    // 통째로 싣는 클래스의 프로젝트 상위 클래스 사슬도 통째로 — 상속받은 구현이 하위형 밖에 있을 수 있다(v14 반증 처방 d).
                    for (int i = 0; i < whole.size(); i++) {
                        javax.lang.model.type.TypeMirror sup = whole.get(i).getSuperclass();
                        if (sup instanceof javax.lang.model.type.DeclaredType declared
                                && declared.asElement() instanceof TypeElement superType
                                && superType.getQualifiedName().toString().startsWith("com.stockscreener.")
                                && !whole.contains(superType)) {
                            whole.add(superType);
                        }
                    }
                    for (TypeElement type : whole) {
                        String name = type.getQualifiedName().toString();
                        TypeElement typeTop = topLevel(type);
                        String typeTopName = typeTop == null ? "" : typeTop.getQualifiedName().toString();
                        if (scopeTypes.contains(typeTopName) || !wholeSeen.add(name)) {
                            continue;
                        }
                        Path typeFile = main.resolve(typeTopName.replace('.', '/') + ".java");
                        if (!files.contains(typeFile)) {
                            missing.add(typeFile);
                            continue;
                        }
                        TreePath typePath = trees.getPath(type);
                        out.put(name + " 전체", withoutDocComments(typePath.getLeaf().toString()));
                        collectReferences(trees, typePath, work);
                    }
                    TreePath path = trees.getPath(element);
                    if (path == null) {
                        out.put(key, "합성 " + element.getKind());
                        continue;
                    }
                    if (element instanceof TypeElement type && path.getLeaf() instanceof ClassTree declaration) {
                        out.put(key, typeHeader(trees, path, type, declaration));
                        for (Tree member : declaration.getMembers()) {
                            if (member instanceof BlockTree || isConstantOrComponent(trees, new TreePath(path, member))) {
                                collectReferences(trees, new TreePath(path, member), work);
                            }
                        }
                        if (declaration.getExtendsClause() != null) {
                            collectReferences(trees, new TreePath(path, declaration.getExtendsClause()), work);
                        }
                        for (Tree implemented : declaration.getImplementsClause()) {
                            collectReferences(trees, new TreePath(path, implemented), work);
                        }
                    } else {
                        out.put(key, withoutDocComments(path.getLeaf().toString()));
                        collectReferences(trees, path, work);
                    }
                }
            }
            if (missing.isEmpty()) {
                StringBuilder text = new StringBuilder("== 범위 밖 멤버\n");
                out.forEach((key, value) -> text.append("-- ").append(key).append('\n').append(value).append('\n'));
                return text.toString();
            }
            files.addAll(missing);
        }
    }

    /** 운영 클래스 중 그 형의 하위형(자기 빼고 · 범위 밖 판정은 부르는 쪽) — 이진 이름. 클래스 파일을 한 번 읽어 둔다. */
    private static List<String> projectSubtypes(String binaryName) {
        try {
            Class<?> type = Class.forName(binaryName, false, ReaderVersionPinTest.class.getClassLoader());
            List<String> out = new ArrayList<>();
            for (Class<?> candidate : mainClasses()) {
                if (candidate != type && type.isAssignableFrom(candidate)) {
                    out.add(candidate.getName());
                }
            }
            out.sort(null);
            return out;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("폐포가 닿은 형을 클래스로 못 읽었다: " + binaryName, e);
        }
    }

    private static List<Class<?>> mainClassCache;

    private static synchronized List<Class<?>> mainClasses() {
        if (mainClassCache != null) {
            return mainClassCache;
        }
        try {
            Path classes = Path.of(SecFilingClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<Class<?>> out = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                    String name = classes.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                    out.add(Class.forName(name, false, ReaderVersionPinTest.class.getClassLoader()));
                }
            }
            mainClassCache = out;
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("운영 클래스 목록을 못 읽었다", e);
        }
    }

    private static void collectReferences(Trees trees, TreePath root, Deque<Element> work) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                add();
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                add();
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                add();
                return super.visitNewClass(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                add();
                return super.visitMemberReference(node, unused);
            }

            private void add() {
                Element element = trees.getElement(getCurrentPath());
                if (element != null && element.getKind() != ElementKind.PACKAGE
                        && element.getKind() != ElementKind.LOCAL_VARIABLE && element.getKind() != ElementKind.PARAMETER
                        && element.getKind() != ElementKind.EXCEPTION_PARAMETER
                        && element.getKind() != ElementKind.RESOURCE_VARIABLE
                        && element.getKind() != ElementKind.TYPE_PARAMETER
                        && element.getKind() != ElementKind.BINDING_VARIABLE) {
                    work.push(element);
                }
            }
        }.scan(root, null);
    }

    private static boolean isConstantOrComponent(Trees trees, TreePath member) {
        Element element = trees.getElement(member);
        return element != null && (element.getKind() == ElementKind.ENUM_CONSTANT
                || (element.getKind() == ElementKind.FIELD && element.getEnclosingElement().getKind() == ElementKind.RECORD
                && !element.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)));
    }

    private static String typeHeader(Trees trees, TreePath path, TypeElement type, ClassTree declaration) {
        StringBuilder header = new StringBuilder();
        header.append(declaration.getModifiers().toString().strip()).append(' ').append(declaration.getKind()).append(' ')
                .append(type.getQualifiedName()).append(declaration.getTypeParameters())
                .append(" extends ").append(declaration.getExtendsClause())
                .append(" implements ").append(declaration.getImplementsClause()).append('\n');
        for (Tree member : declaration.getMembers()) {
            if (member instanceof BlockTree || isConstantOrComponent(trees, new TreePath(path, member))) {
                header.append(withoutDocComments(member.toString())).append('\n');
            }
        }
        return header.toString();
    }

    private static String memberKey(Element element) {
        if (element instanceof TypeElement type) {
            return type.getQualifiedName() + " 형";
        }
        Element owner = element.getEnclosingElement();
        String ownerName = owner instanceof TypeElement type ? type.getQualifiedName().toString() : String.valueOf(owner);
        return ownerName + "#" + (element instanceof ExecutableElement ? element.toString() : element.getSimpleName().toString())
                + " " + element.getKind();
    }

    private static String topLevelName(CompilationUnitTree unit) {
        String file = Path.of(unit.getSourceFile().toUri()).getFileName().toString();
        return unit.getPackageName() + "." + file.substring(0, file.length() - 5);
    }

    private static String withoutDocComments(String printed) {
        StringBuilder out = new StringBuilder();
        boolean inDoc = false;
        for (String line : printed.split("\n", -1)) {
            String trimmed = line.strip();
            if (inDoc) {
                inDoc = !trimmed.equals("*/");
            } else if (trimmed.equals("/**")) {
                inDoc = true;
            } else {
                out.append(line).append('\n');
            }
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
        // 트리 출력은 레코드를 class로 찍는다 — 형 종류를 따로 싣는다(v8 반증: 명시 정규 생성자 레코드와 같은 생성자의
        // 내부 클래스가 같은 글로 찍혔다).
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                out.append("kind ").append(node.getSimpleName()).append(' ').append(node.getKind()).append('\n');
                return super.visitClass(node, unused);
            }
        }.scan(parse(name, source), null);
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
