package com.stockscreener.interaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 인터랙션 한 건을 남긴다.
 *
 * <p>IMPORTANT — <b>기록 실패가 요청을 깨뜨리지 않되 삼켜지지도 않는다.</b> 로그를 남기지
 * 못했다고 스크리닝이 500이 되어서는 안 되고, 반대로 아무 흔적 없이 사라져서도 안 된다.
 * 그래서 {@link DataAccessException}만 잡아 ERROR로 남긴다 — 좁게 잡는 이유는 그 밖의 예외가
 * 우리 코드의 버그이고, 그것까지 삼키면 <b>기록이 안 되는 상태가 정상처럼 보이기</b> 때문이다.
 *
 * <p>DB가 죽어 기록이 실패하는 것은 실제로 일어난다. 세션이 살아 있는 채로 {@code app_user}
 * 행이 지워지면 FK 위반도 난다. 둘 다 요청을 깨뜨릴 이유가 없는 사건이다.
 */
@Service
public class InteractionRecorder {

    private static final Logger log = LoggerFactory.getLogger(InteractionRecorder.class);

    /** {@code path} 컬럼 상한. 넘치면 잘라 넣는다 — 기록 자체가 실패하는 편이 더 나쁘다. */
    private static final int MAX_PATH_LENGTH = 200;

    /**
     * 애플리케이션의 {@code ObjectMapper} 빈을 주입받지 않는다.
     *
     * <p>{@code IndicatorBandCodec}과 같은 이유다 — 이것은 <b>저장 형식</b>이지 API 형식이
     * 아니다. 웹 응답의 Jackson 설정을 누가 바꾸면 이미 저장된 행과 새로 쓰는 행의 형식이
     * 갈리는데, 그 어긋남은 로그를 조회할 때에야 드러난다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final JdbcTemplate jdbcTemplate;

    public InteractionRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 인터랙션을 남긴다. 실패해도 던지지 않는다.
     *
     * @param userId     특정하지 못했으면 {@code null}. 행위 자체는 남는다.
     * @param detail     URI 템플릿 변수와 화이트리스트 쿼리 파라미터. 비면 {@code null}로 저장된다.
     */
    public void record(InteractionAction action, Long userId, String httpMethod, String path,
                       Integer statusCode, Integer durationMs, Map<String, String> detail) {

        try {
            jdbcTemplate.update("""
                    INSERT INTO interaction_log
                        (user_id, action, http_method, path, status_code, duration_ms, detail, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), now())
                    """,
                    userId, action.name(), httpMethod, truncate(path),
                    statusCode, durationMs, detailJson(detail));

        } catch (DataAccessException e) {
            // 삼키지 않는다. 이 한 줄이 "기록이 되고 있다"와 "조용히 안 되고 있다"를 가르는
            // 유일한 신호다 — 조회 화면은 둘을 구분하지 못한다(둘 다 빈 목록으로 보인다).
            log.error("인터랙션 기록에 실패했다. action={} userId={} path={} — 요청 자체는 정상 처리됐다.",
                    action, userId, path, e);
        }
    }

    private String detailJson(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(detail);
    }

    private static String truncate(String path) {
        if (path == null || path.length() <= MAX_PATH_LENGTH) {
            return path;
        }
        return path.substring(0, MAX_PATH_LENGTH);
    }
}
