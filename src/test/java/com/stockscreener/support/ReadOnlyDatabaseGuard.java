package com.stockscreener.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 시점 재현 하네스가 <b>운영 DB에 한 글자도 쓰지 못하게</b> 기동 시 확인한다(P17, ADR-0071).
 *
 * <p>{@link TestDatabaseGuard}의 짝이고 방향이 반대다. 저쪽은 "테스트가 운영 DB를 건드리지
 * 않는가"를 묻고, 이쪽은 "운영 DB를 <b>읽기만</b> 하는가"를 묻는다. 백테스트가 필요로 하는
 * 시세·재무가 운영 DB에만 있어서 그리로 붙는 것이 옳고, 그렇다면 막을 것은 접속이 아니라
 * <b>쓰기</b>다.
 *
 * <h2>왜 실제로 INSERT를 던지는가</h2>
 *
 * <p><b>설정을 읽어 확인하면 아무것도 확인되지 않는다.</b> {@code hikari.read-only: true}는
 * {@code Connection.setReadOnly(true)}를 부를 뿐이고, pgjdbc 기본 모드
 * ({@code readOnlyMode=transaction})에서 그 호출은 <b>autoCommit이 꺼져 있을 때만</b>
 * {@code BEGIN READ ONLY}를 낸다. {@code JdbcTemplate}은 기본이 autocommit이므로 그 설정만
 * 있으면 <b>아무 SQL도 나가지 않고 쓰기가 그대로 통과한다.</b> 접속 롤이 superuser이고
 * 서버의 {@code default_transaction_read_only}가 off라 마지막 방어선도 없다.
 *
 * <p>그래서 <b>돌려 본다.</b> 실패해야 할 것이 실패하는지는 실행 말고 알 방법이 없다 —
 * 검증 장치가 돌지 않으면 그것은 검사가 아니라 기록이다.
 *
 * <p>깨지면 처방은 URL의 {@code readOnlyMode=always}이고, 그것으로도 안 되면 전용 롤
 * ({@code default_transaction_read_only=on} + {@code GRANT SELECT})로 올린다.
 */
@TestConfiguration
public class ReadOnlyDatabaseGuard {

    /** PostgreSQL이 읽기 전용 트랜잭션에서 쓰기를 거부할 때의 SQLSTATE. */
    private static final String READ_ONLY_SQL_STATE = "25006";

    /**
     * 존재하지 않아도 되는 테이블에 쓰지 않는다 — 그러면 실패 원인이 "읽기 전용"인지
     * "테이블이 없음"인지 구분되지 않고, 후자여도 단언은 통과한다. 실재하는 테이블을 쓰되
     * <b>제약을 반드시 위반하는 값</b>을 넣어, 만에 하나 쓰기가 통과하더라도 행이 남지 않게 한다.
     */
    private static final String FORBIDDEN_WRITE =
            "INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value, updated_at) "
                    + "VALUES (-1, DATE '1900-01-01', '__READONLY_GUARD__', 0, now())";

    @Bean
    public InitializingBean assertReadOnlyProductionDatabase(DataSource dataSource,
                                                             Environment environment) {
        return () -> {
            String url = urlOf(dataSource);

            // 운영 데이터가 목적이다. 테스트 DB를 가리키면 전 테이블이 0행이라 하네스가
            // "통과 0종목"을 내는데, 그것은 게이트 탓으로 읽히고 어떤 에러도 나지 않는다.
            if (url.contains("_test")) {
                throw new IllegalStateException(
                        "시점 재현 하네스가 테스트 DB를 가리키고 있다: " + url
                                + " — 그 DB는 비어 있어 '통과 0'이 게이트 탓으로 오독된다.");
            }

            // Flyway가 켜져 있으면 운영 스키마를 건드릴 수 있는 자동 경로가 열려 있는 것이다.
            // `application-test.yaml`을 함께 활성화했을 때 clean까지 켜지는 경로를 여기서 막는다.
            if (environment.getProperty("spring.flyway.enabled", Boolean.class, Boolean.TRUE)) {
                throw new IllegalStateException(
                        "운영 DB에 Flyway가 켜져 있다 — backtest 프로파일과 test 프로파일을"
                                + " 함께 활성화하지 않았는지 확인하라(그쪽은 clean까지 켠다).");
            }

            assertWriteIsRejected(dataSource, url);
        };
    }

    /**
     * MUST: 결과를 <b>표준 출력에 남긴다.</b> 이 펜스가 돌았다는 것을 실행 로그로 확인한 뒤에야
     * "이것이 성패다"라고 적을 수 있다. 조용히 통과하면 다음 세션은 펜스가 있는지도 모른다.
     */
    private static void assertWriteIsRejected(DataSource dataSource, String url) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try {
            jdbcTemplate.update(FORBIDDEN_WRITE);
        } catch (org.springframework.dao.DataAccessException e) {
            String sqlState = sqlStateOf(e);
            if (!READ_ONLY_SQL_STATE.equals(sqlState)) {
                throw new IllegalStateException(
                        "쓰기가 거부되긴 했으나 이유가 읽기 전용이 아니다(SQLSTATE " + sqlState
                                + "). 제약 위반이나 테이블 부재로 막힌 것이라면 이 펜스는"
                                + " 아무것도 보장하지 않는다: " + url, e);
            }
            System.out.println("[ReadOnlyDatabaseGuard] " + url
                    + " — 쓰기가 SQLSTATE " + sqlState + "로 거부됐다. 읽기 전용이 실제로 걸려 있다.");
            return;
        }
        throw new IllegalStateException(
                "운영 DB에 쓰기가 통과했다: " + url
                        + " — hikari.read-only만으로는 autocommit 경로를 막지 못한다."
                        + " JDBC URL에 readOnlyMode=always가 있는지 확인하라.");
    }

    private static String sqlStateOf(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        return "(없음)";
    }

    private static String urlOf(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "운영 DB 접속에 실패했다. 로컬 PostgreSQL이 구동 중인지 확인하라.", e);
        }
    }
}
