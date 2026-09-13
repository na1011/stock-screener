package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>연결에 묻는다</b>는 계약을 잠근다. 설정 문자열을 읽어 대조하면 그 문자열이 실제로 어디에
 * 붙었는지는 아무것도 확인되지 않는다(ADR-0084 §규칙).
 *
 * <p>순서(시더보다 앞선다)는 여기서 못 잰다 — 그것은 {@code InitializingBean}이라는 타입이
 * 보장하는 것이고, 실제 확인은 bootstrap 잡을 한 번 돌리는 것이다. <b>그 한계를 적어 둔다.</b>
 */
class BootstrapDatabaseGuardTest {

    @Test
    @DisplayName("연결된 데이터베이스가 기대와 같으면 통과한다")
    void passesWhenTheConnectedDatabaseMatches() {
        assertThatCode(() -> guard("stock_screener", "stock_screener").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("연결된 데이터베이스가 기대와 다르면 기동을 세운다")
    void stopsStartupWhenTheConnectedDatabaseDiffers() {
        assertThatThrownBy(() -> guard("stock_screener_scratch", "stock_screener").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_screener_scratch")
                .hasMessageContaining("stock_screener");
    }

    @Test
    @DisplayName("IMPORTANT — DB_URL만 바꾸고 BOOTSTRAP_DB를 안 바꾸면 선다")
    void stopsWhenOnlyTheUrlWasSwitched() {
        // 막으려는 사고가 이것이다. 기대값에 기본값이 있으므로 「선언 안 함」도 단언이다.
        assertThatThrownBy(() -> guard("stock_screener_scratch", "stock_screener").afterPropertiesSet())
                .hasMessageContaining("카탈로그 시더가 기동만으로 쓰므로");
    }

    @Test
    @DisplayName("반대로 BOOTSTRAP_DB만 바꾸고 DB_URL을 안 바꿔도 선다")
    void stopsWhenOnlyTheExpectationWasSwitched() {
        assertThatThrownBy(() -> guard("stock_screener", "stock_screener_scratch").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("카탈로그가 null이어도 삼키지 않는다")
    void nullCatalogIsNotSilentlyAccepted() {
        assertThatThrownBy(() -> guard(null, "stock_screener").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThat(true).isTrue();
    }

    private static BootstrapDatabaseGuard guard(String connectedCatalog, String expected) {
        return new BootstrapDatabaseGuard(dataSourceReturning(connectedCatalog), expected);
    }

    /** {@code getCatalog()}만 답하는 최소 스텁. 드라이버를 띄우지 않고 계약만 잰다. */
    private static DataSource dataSourceReturning(String catalog) {
        Connection connection = (Connection) Proxy.newProxyInstance(
                BootstrapDatabaseGuardTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCatalog" -> catalog;
                    case "close" -> null;
                    case "toString" -> "stub-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(
                BootstrapDatabaseGuardTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "toString" -> "stub-datasource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
