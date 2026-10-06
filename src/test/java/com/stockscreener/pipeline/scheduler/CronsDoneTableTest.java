package com.stockscreener.pipeline.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ops/crons-done.sh}(앱을 내려도 크론이 안 빠지는가)는 크론 표를 따로 든다 — 셸이 {@code @Scheduled}를 못 읽기 때문이다.
 * 표가 갈리면 새 크론이나 바뀐 시각을 판정이 못 보고 「내려도 된다」를 낸다. 그래서 표와 각 크론의 끝 줄을 여기서 묻는다.
 */
class CronsDoneTableTest {

    private static final Pattern ROW = Pattern.compile("^\\s*\"(\\w+) (\\d-\\d) (\\d\\d:\\d\\d:\\d\\d)\"\\s*$", Pattern.MULTILINE);

    @Test
    @DisplayName("ops/crons-done.sh의 크론 표가 @Scheduled와 같고, 크론마다 끝 줄을 찍는다")
    void cronTableMatchesTheScheduledMethods() throws Exception {
        Map<String, String> fromCode = new TreeMap<>();
        for (Method method : PipelineScheduler.class.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled == null) {
                continue;
            }
            String[] f = scheduled.cron().split(" ");
            assertThat(f).as("초 분 시 일 월 요일: " + scheduled.cron()).hasSize(6);
            assertThat(f[3] + f[4]).as("일·월을 거는 크론은 판정 표가 못 나타낸다: " + scheduled.cron()).isEqualTo("**");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
            fromCode.put(method.getName(), days(f[5]) + " %02d:%02d:%02d".formatted(
                    Integer.parseInt(f[2]), Integer.parseInt(f[1]), Integer.parseInt(f[0])));
        }
        Map<String, String> fromScript = new TreeMap<>();
        Matcher row = ROW.matcher(Files.readString(Path.of("ops/crons-done.sh")));
        while (row.find()) {
            fromScript.put(row.group(1), row.group(2) + " " + row.group(3));
        }
        assertThat(fromScript).as("ops/crons-done.sh의 CRONS 표").isEqualTo(fromCode);

        String source = Files.readString(Path.of("src/main/java/com/stockscreener/pipeline/scheduler/PipelineScheduler.java"));
        for (String name : fromCode.keySet()) {
            assertThat(source).as(name + "의 끝 줄 — 판정이 그 줄로 크론마다 끝났는지를 잰다")
                    .contains("log.info(\"크론 발화를 마쳤다. 크론={}\", \"" + name + "\");");
        }
    }

    private static String days(String dayOfWeek) {
        return switch (dayOfWeek) {
            case "MON-FRI" -> "1-5";
            case "SAT" -> "6-6";
            case "SUN" -> "7-7";
            default -> throw new IllegalStateException(
                    "모르는 요일 꼴: " + dayOfWeek + " — 이 시험과 ops/crons-done.sh의 runs_on을 함께 넓힌다");
        };
    }
}
