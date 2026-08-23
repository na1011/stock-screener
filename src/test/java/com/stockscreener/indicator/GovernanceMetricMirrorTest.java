package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0081의 {@code premise} 펜스가 손으로 적은 {@link GovernanceMetric} 목록과 어긋나지 않는가.
 *
 * <p>SQL이 Java enum을 못 읽어 사본이 불가피하다. 그 사본이 갈리는 방향 하나가 <b>조용하다</b> —
 * enum에서 상수를 <b>빼거나 이름을 바꿨는데</b> SQL 목록에 옛 이름이 남아 있으면, 펜스가
 * 「모르는 키가 없다」로 <b>침묵한다.</b> 정작 그 상황이 펜스가 잡으라고 만들어진 것이다.
 * (반대 방향 — 상수를 더하고 SQL을 안 고친 것 — 은 펜스가 시끄럽게 틀리므로 드러난다.)
 *
 * <p>{@code .claude/rules/doc-facts.md}가 <i>「등록하지 않은 사본은 갈려도 아무도 모른다」</i>로
 * 이 자리를 못박는다. {@code check-doc-staleness.sh}의 거울 표는 <b>파일 둘의 같은 문장</b>을
 * 대조하는 형태라 여기에 안 맞는다 — Java 상수와 SQL 문자열은 표기가 달라서다.
 */
class GovernanceMetricMirrorTest {

    private static final Path ADR = Path.of("docs/adr/ADR-0081-governance-axis.md");

    /** 펜스의 {@code NOT IN (...)} 목록. 두 번 나오지만 같아야 하므로 첫 것을 본다. */
    private static final Pattern IN_LIST =
            Pattern.compile("metric_key NOT IN \\(([^)]+)\\)");

    @Test
    @DisplayName("ADR-0081의 premise SQL이 GovernanceMetric 전체와 정확히 같다")
    void premiseListMatchesTheEnum() throws IOException {
        String adr = Files.readString(ADR, StandardCharsets.UTF_8);
        Matcher matcher = IN_LIST.matcher(adr);
        assertThat(matcher.find())
                .as("ADR-0081에서 metric_key 허용목록을 찾지 못했다 — 펜스를 지웠으면 이 테스트도 "
                        + "함께 지운다. 남겨 두면 실패할 수 없는 펜스가 된다")
                .isTrue();

        Set<String> inSql = Arrays.stream(matcher.group(1).split(","))
                .map(token -> token.trim().replace("'", ""))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> inEnum = Arrays.stream(GovernanceMetric.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(inSql)
                .as("ADR-0081의 premise 목록과 GovernanceMetric이 갈렸다. 상수를 빼거나 이름을 "
                        + "바꾼 쪽이면 **펜스가 조용해진다** — 그 침묵이 정확히 이 펜스가 잡으라고 "
                        + "만들어진 상황이다")
                .containsExactlyInAnyOrderElementsOf(inEnum);

        // 펜스가 두 번 쓰는 같은 목록이 서로 다르면 SQL이 조용히 다른 것을 센다.
        assertThat(matcher.find()).as("펜스가 목록을 두 번 쓴다").isTrue();
        Set<String> second = Arrays.stream(matcher.group(1).split(","))
                .map(token -> token.trim().replace("'", ""))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(second)
                .as("같은 펜스 안의 두 목록이 갈렸다 — 세는 것과 이름을 대는 것이 달라진다")
                .containsExactlyInAnyOrderElementsOf(inSql);
    }
}
