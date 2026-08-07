package com.stockscreener.indicator;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@link IndicatorBand} 목록과 {@code indicator.bands} jsonb 사이의 변환.
 *
 * <p>쓰는 쪽({@link IndicatorCatalogSynchronizer})과 읽는 쪽({@link IndicatorCatalog})이 서로
 * 다른 파일이라, 형식을 각자 알면 언젠가 한쪽만 바뀐다. 그때 증상은 예외가 아니라
 * <b>구간이 조용히 사라지는 것</b>이다 — 화면은 "이 지표엔 구간이 없다"로 읽고 직접 입력만
 * 보여주므로 아무도 잘못됐다는 것을 모른다. 그래서 형식을 아는 곳을 하나로 둔다.
 */
@Component
public class IndicatorBandCodec {

    private static final TypeReference<List<IndicatorBand>> LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public IndicatorBandCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 저장용 JSON 배열 문자열. 구간이 없으면 {@code []}. */
    public String toJson(List<IndicatorBand> bands) {
        return objectMapper.writeValueAsString(bands == null ? List.of() : bands);
    }

    /**
     * jsonb 컬럼 값을 되돌린다.
     *
     * <p>파싱 실패를 삼키지 않는다. 이 컬럼을 쓰는 것은 기동 시의 투영뿐이므로, 깨진 값은
     * 데이터 문제가 아니라 코드 문제다. 조용히 빈 목록으로 만들면 그 버그가 "구간 없는 지표"로
     * 위장한다.
     */
    public List<IndicatorBand> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, LIST_TYPE);
    }
}
