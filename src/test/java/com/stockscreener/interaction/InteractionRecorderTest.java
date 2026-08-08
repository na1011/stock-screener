package com.stockscreener.interaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 기록 실패가 요청을 깨뜨리지 않되 삼켜지지도 않는가.
 *
 * <p>설계 §5의 판정 한 줄에 그대로 대응한다. 이 둘은 서로를 배반하기 쉬운 요구다 — 던지지
 * 않게 만들다 보면 조용해지고, 조용하지 않게 만들다 보면 요청이 깨진다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 확인하려는 것이 <b>예외 처리의 모양</b>뿐이라 DB도
 * 웹 계층도 필요 없고, 실패했을 때 DB 문제로 오해되지 않아야 한다.
 */
class InteractionRecorderTest {

    private JdbcTemplate jdbcTemplate;
    private InteractionRecorder recorder;
    private ListAppender<ILoggingEvent> logs;
    private Logger recorderLogger;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        // 코덱은 가짜로 바꾸지 않는다. 저장 형식이 실제로 만들어지는지가 이 경로의 일부다.
        recorder = new InteractionRecorder(jdbcTemplate, new InteractionDetailCodec());

        logs = new ListAppender<>();
        logs.start();
        recorderLogger = (Logger) LoggerFactory.getLogger(InteractionRecorder.class);
        recorderLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        recorderLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("DB가 죽어도 던지지 않는다 — 로그를 못 남겼다고 스크리닝이 500이 되어서는 안 된다")
    void databaseFailureDoesNotPropagate() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("연결 없음"));

        assertThatCode(() -> recorder.record(
                InteractionAction.SCREENING_RUN, 1L, "POST", "/api/screening", 200, 12, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IMPORTANT — 실패가 ERROR 로그로 남는다. 이 한 줄이 '기록 중'과 '조용히 안 됨'을 가른다")
    void databaseFailureIsLoggedAtError() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("연결 없음"));

        recorder.record(InteractionAction.SCREEN_CREATE, 7L, "POST", "/api/screens", 201, 3,
                Map.of("id", "42"));

        // 조회 화면은 "기록이 되고 있다"와 "조용히 안 되고 있다"를 구분하지 못한다 —
        // 둘 다 빈 목록으로 보인다. 그래서 로그가 유일한 신호다.
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("SCREEN_CREATE", "/api/screens");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
    }

    @Test
    @DisplayName("정상 기록에는 아무 로그도 남지 않는다 — 남으면 실패 신호가 묻힌다")
    void successfulRecordIsQuiet() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        recorder.record(InteractionAction.LOGIN, 1L, null, null, null, null, null);

        assertThat(logs.list).isEmpty();
    }
}
