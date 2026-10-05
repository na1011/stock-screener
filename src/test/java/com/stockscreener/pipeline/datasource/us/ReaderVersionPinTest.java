package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해석 규칙의 출력을 {@link XbrlInstanceReader#READER_VERSION}에 묶는다(ADR-0173).
 *
 * <p>해석 실패({@code UNREADABLE}) 행은 판이 같은 동안 다시 받지 않는다. 그래서 규칙을 바꾸고 판을 안 올리면 고친 규칙이 그 행에
 * 영영 안 닿는다 — 리뷰가 그것을 잡는다고 믿지 않고 여기서 묻는다. 고정 재료(인스턴스 하나 · 파일 이름 목록 · 폴더 목록)를
 * 판의 범위 전부(읽기 · 고르기 · 신원 · 후보 파일 규칙 · 인스턴스 고르기)에 먹여 출력의 해시를 판마다 박는다.
 *
 * <p><b>규칙을 바꿔 이 시험이 붉으면</b>: {@code READER_VERSION}을 올리고 새 해시를 {@link #PINNED} 끝에 더한다.
 * MUST NOT: 이미 박힌 해시를 고치지 않는다 — 그것이 「판을 안 올리고 규칙을 바꾼 것」이다.
 */
class ReaderVersionPinTest {

    /** 판 1, 2, …의 출력 해시. 끝에만 더한다. */
    private static final List<String> PINNED = List.of(
            "433820a20d5bb050946f0ab3a93fa358b3c6193380c286320cc0eefd7b1b6982");

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

    private static String canonicalOutput() throws Exception {
        StringBuilder out = new StringBuilder();
        for (long cik : new long[] {1800, 1868941, 320193}) {
            XbrlInstanceReader.Instance instance;
            try (InputStream in = new ClassPathResource("fixtures/sec-edgar/instance-mini.xml").getInputStream()) {
                instance = XbrlInstanceReader.read(in, cik, "pin");
            }
            out.append("cik=").append(cik).append(" doc=").append(instance.documentType())
                    .append(" fy=").append(instance.fiscalYearFocus()).append(" fp=").append(instance.fiscalPeriodFocus())
                    .append(" own=").append(instance.ownContexts()).append('\n');
            instance.facts().stream().map(String::valueOf).sorted().forEach(f -> out.append(" raw ").append(f).append('\n'));
            out.append(" choose ").append(outcome(() -> XbrlInstanceReader.choose(instance.facts(), "pin").stream()
                    .map(String::valueOf).sorted().toList())).append('\n');
            for (String form : List.of("10-Q", "10-Q/A", "10-K", "6-K")) {
                out.append(" identity ").append(form).append(' ').append(outcome(() -> {
                    XbrlInstanceReader.requireIdentity(instance, form, "pin");
                    return "ok";
                })).append('\n');
            }
        }
        for (String name : NAMES) {
            out.append("name ").append(name).append(" candidate=").append(SecFilingClient.isInstanceCandidate(name))
                    .append(" artifact=").append(SecFilingClient.isXbrlArtifact(name)).append('\n');
        }
        for (List<String> folder : FOLDERS) {
            out.append("folder ").append(folder).append(" -> ")
                    .append(outcome(() -> String.valueOf(SecFilingSupplement.pickInstance(folder, "pin")))).append('\n');
        }
        return out.toString();
    }

    private interface Body {
        Object get() throws Exception;
    }

    /** 결과 또는 실패의 종류 — 메시지는 문구라 넣지 않는다(문구를 고친 것은 규칙을 바꾼 것이 아니다). */
    private static String outcome(Body body) {
        try {
            return String.valueOf(body.get());
        } catch (DataSourceException e) {
            return "throws DataSourceException " + e.kind();
        } catch (Exception e) {
            return "throws " + e.getClass().getSimpleName();
        }
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
