package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표적 드라이런 DR1(0콜) — DART 받기 분류 고침이 <b>정상 응답의 값을 한 칸도 안 바꾸는가</b>(ADR-0174).
 *
 * <p>저장한 원응답(디렉터리 {@code DART_REPLAY_DIR} · {@code <ticker>_<year>_<report>_<endpoint>.json})을 옛 경로와 새 경로에
 * 넣어 비교한다.
 * <ul>
 *   <li>옛 경로 = 옛 {@code toEntity(JsonNode)}가 하던 것 — Spring Jackson 변환기가 응답 바이트를 그대로 Jackson에 넘긴다
 *       ({@code readTree(byte[])}) → 옛 {@code requireList}({@code path("list")} · 013·014는 NOT_FOUND).</li>
 *   <li>새 경로 = {@link DartResponse#read}(content-type 꼴 셋) → {@link DartResponse#requireList}.</li>
 *   <li>매퍼까지 — 두 트리의 행을 {@link DartAccountMapper}·{@link DartFullAccountMapper}의 모든 후보 집합과 합계 함수에 먹여
 *       값 칸을 비교한다.</li>
 * </ul>
 * 재료가 없으면 건너뛴다(외부로 받지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "DART_REPLAY_DIR", matches = ".+")
class DartReadReplayTest {

    private static final ObjectMapper OLD = new ObjectMapper();
    private static final List<String> CONTENT_TYPES =
            java.util.Arrays.asList("application/json;charset=UTF-8", "application/json", null);

    @Test
    @DisplayName("DR1 — 저장 원응답의 트리·list·매퍼 값이 옛 판독과 새 판독(content-type 셋)에서 같다 · 013·014는 둘 다 「데이터 없음」")
    void storedResponsesReadTheSame() throws Exception {
        List<Path> files;
        try (Stream<Path> listing = Files.list(Path.of(System.getenv("DART_REPLAY_DIR")))) {
            files = listing.filter(p -> p.getFileName().toString().contains("fnlttSinglAcnt")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        int trees = 0;
        int lists = 0;
        int noData = 0;
        int mapperCells = 0;
        List<String> mismatches = new ArrayList<>();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            JsonNode oldTree = OLD.readTree(bytes);
            String status = oldTree.path("status").asString("");
            boolean oldNoData = "013".equals(status) || "014".equals(status);
            for (String contentType : CONTENT_TYPES) {
                HttpHeaders headers = new HttpHeaders();
                if (contentType != null) {
                    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                }
                ResponseEntity<byte[]> response = new ResponseEntity<>(bytes, headers, HttpStatusCode.valueOf(200));
                JsonNode newTree = DartResponse.read(response, file.getFileName().toString());
                if (!oldTree.equals(newTree)) {
                    mismatches.add("tree " + file.getFileName() + " " + contentType);
                    continue;
                }
                trees++;
                JsonNode newList;
                try {
                    newList = DartResponse.requireList(newTree, "replay");
                } catch (DataSourceException e) {
                    if (oldNoData && DartResponse.isNoData(e)) {
                        noData++;
                    } else {
                        mismatches.add("list " + file.getFileName() + " " + contentType + " " + e.getMessage());
                    }
                    continue;
                }
                if (oldNoData || !oldTree.path("list").equals(newList)) {
                    mismatches.add("list " + file.getFileName() + " " + contentType);
                    continue;
                }
                lists++;
                List<String> oldCells = mapperCells(oldTree.path("list"));
                List<String> newCells = mapperCells(newList);
                if (!oldCells.equals(newCells)) {
                    mismatches.add("mapper " + file.getFileName() + " " + contentType);
                }
                mapperCells += newCells.size();
            }
        }
        System.out.printf("DR1 files=%d trees=%d lists=%d noData=%d mapperCells=%d mismatches=%d%n",
                files.size(), trees, lists, noData, mapperCells, mismatches.size());
        assertThat(files).as("재료가 비면 잰 것이 없다").isNotEmpty();
        assertThat(mismatches).isEmpty();
        assertThat(trees).isEqualTo(files.size() * CONTENT_TYPES.size());
    }

    /** 두 매퍼의 모든 후보 집합(이름 · account_id)과 합계 함수를 그 행에 먹인 값 칸. */
    private static List<String> mapperCells(JsonNode list) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        list.forEach(rows::add);
        List<String> cells = new ArrayList<>();
        for (Set<String> names : candidateSets(DartAccountMapper.class)) {
            cells.add(String.valueOf(DartAccountMapper.find(rows, names, false)));
            cells.add(String.valueOf(DartAccountMapper.find(rows, names, true)));
        }
        List<JsonNode> balance = DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_BALANCE);
        List<JsonNode> income = DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_INCOME,
                DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
        List<JsonNode> cashFlow = DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_CASH_FLOW);
        for (Set<String> ids : candidateSets(DartFullAccountMapper.class)) {
            for (List<JsonNode> section : List.of(rows, balance, income, cashFlow)) {
                cells.add(String.valueOf(DartFullAccountMapper.find(section, ids, Set.of())));
            }
        }
        BigDecimal equity = DartFullAccountMapper.controllingEquity(balance);
        cells.add(String.valueOf(equity));
        cells.add(String.valueOf(DartFullAccountMapper.controllingIncome(income, true)));
        cells.add(String.valueOf(DartFullAccountMapper.controllingIncome(income, false)));
        cells.add(String.valueOf(DartFullAccountMapper.hasNoncontrollingIncome(income)));
        cells.add(String.valueOf(DartFullAccountMapper.operatingCashFlow(cashFlow)));
        return cells;
    }

    @SuppressWarnings("unchecked")
    private static List<Set<String>> candidateSets(Class<?> mapper) throws IllegalAccessException {
        List<Set<String>> sets = new ArrayList<>();
        for (Field field : mapper.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Set.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                sets.add((Set<String>) field.get(null));
            }
        }
        return sets;
    }
}
