package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장해 둔 {@code fnlttSinglAcntAll} 응답 <b>전량</b>을 매퍼에 먹여 지배주주 자본을 찍는다.
 * DB도 배치도 외부 호출도 없다 — 미국의 {@code SecEdgarFactsSweepTest}에 대응하는 국내 도구다.
 *
 * <p><b>왜 필요한가</b>: 픽스처 셋짜리 단위시험은 <b>세 갈래가 도는지</b>만 답하고
 * <b>전 종목에서 몇 %가 차는지</b>는 못 답한다. 적재 수용 기준이 「88% 이상」이라
 * 그 수를 재적재 전에 내야 하는데, 그러지 않으면 <b>DART 13,700콜을 태운 뒤에야</b> 안다.
 *
 * <p><b>MUST: 내 손 계산이 아니라 매퍼를 태운다</b>({@code data-pipeline.md} §순서 ② —
 * <i>"코드가 실제로 타는 조건을 그대로 재현해 센다"</i>). 같은 수를 스크립트로 내면
 * {@code sections(list, "BS")} 필터나 {@code account_id} 완전일치 같은 매퍼 고유의 갈래가 빠진다.
 *
 * <pre>REPLAY_KR_DIR=&lt;탐침이 남긴 디렉터리&gt; ./gradlew test --tests "*DartControllingEquitySweepTest*"</pre>
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_KR_DIR", matches = ".+")
class DartControllingEquitySweepTest {

    @Test
    @DisplayName("[sweep] 저장한 응답 전량에서 지배주주 자본 커버리지와 항등식을 찍는다")
    void sweepControllingEquity() throws Exception {
        Path dir = Path.of(System.getenv("REPLAY_KR_DIR"));
        List<Path> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("픽스처가 없으면 이 시험은 아무것도 안 잰다").isNotEmpty();

        ObjectMapper mapper = new ObjectMapper();
        int answered = 0, direct = 0, derived = 0, none = 0, identityOk = 0, identityChecked = 0;
        Set<String> names = new LinkedHashSet<>();
        List<String> nullTickers = new ArrayList<>();
        List<String> identityBroken = new ArrayList<>();

        for (Path file : files) {
            String ticker = file.getFileName().toString().split("-")[0];
            JsonNode body = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode list = body.get("list");
            if (list == null || list.isEmpty()) {
                continue;
            }
            answered++;
            List<JsonNode> bs = DartFullAccountMapper.sections(list, "BS");

            BigDecimal direktTag = DartFullAccountMapper.find(
                    bs, DartFullAccountMapper.ID_TOTAL_EQUITY_CONTROLLING, null);
            BigDecimal value = DartFullAccountMapper.controllingEquity(bs);
            BigDecimal total = DartFullAccountMapper.find(
                    bs, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY);
            BigDecimal nci = DartFullAccountMapper.find(
                    bs, DartFullAccountMapper.ID_NONCONTROLLING_INTERESTS, null);

            if (direktTag != null) {
                direct++;
                for (JsonNode row : bs) {
                    if (DartFullAccountMapper.ID_TOTAL_EQUITY_CONTROLLING
                            .contains(row.path("account_id").asString(""))) {
                        names.add(row.path("account_nm").asString("?"));
                    }
                }
            } else if (value != null) {
                derived++;
            } else {
                none++;
                nullTickers.add(ticker);
            }

            if (value != null && nci != null && total != null && total.signum() != 0) {
                identityChecked++;
                BigDecimal diff = total.subtract(value.add(nci)).abs()
                        .divide(total.abs(), 12, RoundingMode.HALF_UP);
                if (diff.compareTo(new BigDecimal("0.0001")) <= 0) {
                    identityOk++;
                } else {
                    identityBroken.add(ticker + " " + diff.toPlainString());
                }
            }
        }

        int filled = direct + derived;
        System.out.println("\n===== 국내 지배주주 자본 스윕(0콜) =====");
        System.out.printf("응답 %d / 파일 %d%n", answered, files.size());
        System.out.printf("직접 태그 %d · 유도(총계−비지배) %d · 못 만듦 %d%n", direct, derived, none);
        System.out.printf("**커버리지 %d / %d = %.1f%%**%n", filled, answered, 100.0 * filled / answered);
        System.out.printf("항등식 「자본총계 = 지배주주 + 비지배」 %d / %d%n", identityOk, identityChecked);
        if (!identityBroken.isEmpty()) {
            System.out.println("  깨진 것: " + identityBroken);
        }
        System.out.println("못 만든 종목: " + nullTickers);
        System.out.printf("계정명 %d가지%n", names.size());

        assertThat(filled).as("한 종목도 못 만들면 매퍼가 응답을 못 읽는 것이다").isPositive();
        // 수용 기준 6 — ADR-0159 §검증이 「88% 이상」을 요구한다. 적재 전에 여기서 답한다.
        assertThat(100.0 * filled / answered)
                .as("커버리지가 88%% 미만이다 — 적재해도 수용 기준을 못 넘는다")
                .isGreaterThanOrEqualTo(88.0);
    }
}
