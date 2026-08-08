package com.stockscreener.interaction;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code interaction_log.detail} jsonb와 문자열 맵 사이의 변환.
 *
 * <p>{@code IndicatorBandCodec}과 같은 이유로 존재한다 — 쓰는 쪽({@link InteractionRecorder})과
 * 읽는 쪽({@link InteractionLogReader})이 다른 파일이라, 형식을 각자 알면 언젠가 한쪽만 바뀐다.
 * 그때 증상은 예외가 아니라 <b>상세가 조용히 비는 것</b>이고, 화면은 "그 행위엔 대상이 없었다"로
 * 읽는다.
 *
 * <p>애플리케이션의 {@code ObjectMapper} 빈을 주입받지 않는 것도 같은 이유다. 이것은
 * <b>저장 형식</b>이지 API 형식이 아니라, 웹 응답의 Jackson 설정이 바뀌면 이미 저장된 행과
 * 새로 쓰는 행의 형식이 갈린다.
 */
@Component
public class InteractionDetailCodec {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** 저장용 JSON. 비어 있으면 {@code null}을 준다 — 빈 객체를 저장할 이유가 없다. */
    public String toJson(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(detail);
    }

    /**
     * jsonb 컬럼 값을 되돌린다.
     *
     * <p>파싱 실패를 삼키지 않는다. 이 컬럼을 쓰는 것은 우리 코드뿐이므로 깨진 값은 데이터
     * 문제가 아니라 코드 문제다. 조용히 빈 맵으로 만들면 그 버그가 "대상 없는 행위"로 위장한다.
     */
    public Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
    }
}
