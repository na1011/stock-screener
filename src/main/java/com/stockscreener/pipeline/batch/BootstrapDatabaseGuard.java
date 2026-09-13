package com.stockscreener.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * bootstrap 기동이 <b>의도한 데이터베이스에 붙었는지</b>를 기동 시점에 단언한다.
 *
 * <p>이것이 없으면 「읽기 전용 잡」이라는 말이 <b>잡에만 참이고 실행 전체에는 거짓</b>이다
 * (ADR-0084 §결과). bootstrap 프로파일은 datasource를 안 덮어 기본값인 운영 DB에 그대로 붙고,
 * 카탈로그 시더 둘({@code IndicatorCatalogInitializer} · {@code PresetCatalogInitializer})이
 * {@code @Profile} 없이 {@code @Component}라 <b>잡이 무엇이든 기동만으로</b>
 * {@code indicator}·{@code screen}·{@code condition_group}·{@code screen_condition}·
 * {@code screen_condition_threshold}에 쓴다.
 *
 * <p><b>MUST: {@link InitializingBean}으로 둔다.</b> 컨텍스트 refresh 중에 돌아야 시더보다 앞선다 —
 * 시더는 {@code ApplicationRunner}라 refresh가 <b>전부 끝난 뒤에</b> 돈다. {@code @Order}로는
 * 이 순서를 못 만든다(그 애너테이션은 러너끼리의 순서만 정한다). 선례가
 * {@code TestDatabaseGuard}·{@code ReadOnlyDatabaseGuard} 둘이고 형태가 같다.
 *
 * <p><b>MUST: 설정이 아니라 연결에 묻는다.</b> {@code spring.datasource.url}을 읽어 대조하면
 * 그 문자열이 실제로 어디에 붙었는지는 아무것도 확인되지 않는다 — {@code ReadOnlyDatabaseGuard}의
 * javadoc이 같은 함정을 적어 뒀다. 여기서는 {@link Connection#getCatalog()}가 답한다.
 *
 * <p><b>MUST NOT: 「선언했을 때만 대조」로 만들지 않는다.</b> 그러면 잊고 안 선언한 실행이
 * 지금과 똑같이 운영 DB에 붙는데, 그것이 정확히 막으려던 사고다. 기대값에 <b>기본값</b>을 줘서
 * 언제나 대조한다 — 아무것도 안 주면 「운영을 의도했다」를 단언하는 것이라 기존 명령이 그대로
 * 돌고, 임시 DB를 쓸 때는 {@code DB_URL}과 {@code BOOTSTRAP_DB}를 <b>둘 다</b> 줘야 한다.
 * <b>한쪽만 주면 기동이 선다.</b>
 *
 * <p><b>가드가 서도 gradle은 `BUILD SUCCESSFUL`과 종료코드 0을 낸다</b>(2026-09-14 실측).
 * 예외가 {@code restartedMain} 스레드에서 나고 그 뒤 컨텍스트가 접히기 때문이며, ADR-0084 §결과가
 * 같은 형태를 이미 적어 뒀다(<i>"컨텍스트가 뜨기 전에 죽으면 러너가 아예 안 돌고 gradle은
 * 종료코드 0을 낸다"</i>). <b>지금은 결함이 아니라 사실이다</b> — bootstrap 실행의 종료코드로
 * 갈래를 타는 스크립트가 하나도 없다(훅들은 명령 문자열만 본다). <b>그것이 생기는 날 결함이
 * 된다</b> — 그때는 여기서 던지지 말고 {@code BootstrapRunner}처럼 {@code System.exit(1)}로 나간다.
 * 막으려던 것(시더의 쓰기)은 지금도 막힌다 — <b>컨텍스트가 안 뜨므로 러너도 시더도 안 돈다.</b>
 *
 * <p><b>이 가드는 운영 앱 기동 경로를 안 건드린다</b>({@code @Profile("bootstrap")}). 그래서
 * 배포가 이것 때문에 실패하지 않고, 반대로 <b>확인하려면 bootstrap 잡을 한 번 돌려야 한다</b> —
 * {@code ops/status.sh}는 이 가드에 대해 아무것도 답하지 않는다.
 */
@Component
@Profile("bootstrap")
class BootstrapDatabaseGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDatabaseGuard.class);

    private final DataSource dataSource;
    private final String expectedDatabase;

    BootstrapDatabaseGuard(DataSource dataSource,
                           @Value("${pipeline.bootstrap.expected-database}") String expectedDatabase) {
        this.dataSource = dataSource;
        this.expectedDatabase = expectedDatabase;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String actual = connectedDatabase();
        if (expectedDatabase.equals(actual)) {
            log.info("bootstrap이 데이터베이스 {}에 붙었다.", actual);
            return;
        }
        throw new IllegalStateException(
                "bootstrap이 기대와 다른 데이터베이스에 붙었다: 기대 '" + expectedDatabase
                        + "' · 실제 '" + actual + "' — 카탈로그 시더가 기동만으로 쓰므로 여기서 멈춘다."
                        + " 임시 DB로 돌리려면 DB_URL과 BOOTSTRAP_DB를 둘 다 그 이름으로 맞춘다.");
    }

    private String connectedDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            return catalog == null ? "" : catalog;
        }
    }
}
