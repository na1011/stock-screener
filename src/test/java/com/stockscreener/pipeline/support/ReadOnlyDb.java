package com.stockscreener.pipeline.support;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * 탐침이 운영 DB를 <b>읽기 전용</b>으로 여는 유일한 자리.
 *
 * <p>탐침은 소스가 무엇을 주는지를 재면서 그것을 <b>이미 저장된 것과 대조</b>해야 할 때가
 * 있다(무엇이 새로 오는가 · 무엇이 이미 있는가). 그때 쓰는 접속이며, 쓰기는 어느 탐침에도
 * 없다.
 *
 * <p>IMPORTANT — {@code readOnlyMode=always}가 있어야 서버가 실제로 거부한다. 드라이버
 * 기본값 {@code transaction}은 autoCommit이 꺼져 있을 때만 {@code BEGIN READ ONLY}를 내므로,
 * autoCommit이 켜진 기본 상태에서는 <b>아무것도 막지 않는다.</b> 접속 롤이 superuser라
 * 이 한 줄이 없으면 탐침이 운영 데이터를 지울 수 있고, <b>막히지 않았다는 사실은 사고가
 * 나기 전까지 드러나지 않는다.</b>
 *
 * <p>그래서 이 메서드를 한 곳에 둔다 — 탐침마다 접속을 열면 그중 하나가 저 한 줄을
 * 빠뜨려도 아무 증상이 없다.
 */
public final class ReadOnlyDb {

    private ReadOnlyDb() {
    }

    public static Connection open() throws Exception {
        String url = System.getenv().getOrDefault(
                "DB_URL", "jdbc:postgresql://localhost:5432/stock_screener");
        String user = System.getenv().getOrDefault("DB_USERNAME", System.getProperty("user.name"));
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        Connection connection = DriverManager.getConnection(
                url + (url.contains("?") ? "&" : "?") + "readOnlyMode=always", user, password);
        connection.setReadOnly(true);
        return connection;
    }
}
