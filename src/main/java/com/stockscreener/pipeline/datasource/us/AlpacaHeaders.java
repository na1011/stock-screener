package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;

import java.util.Map;

/**
 * Alpaca 인증 헤더 둘. 두 어댑터가 같은 이름을 쓰게 한 곳에 둔다.
 *
 * <p>이름이 갈리면 증상이 <b>401 하나</b>뿐이고, 그 401은 "키가 틀렸다"로 읽혀 원인이
 * 헤더 이름이라는 것이 드러나지 않는다.
 *
 * <p>MUST NOT: 이 맵을 로그에 찍지 않는다. 시크릿이 값으로 들어온다.
 */
final class AlpacaHeaders {

    private AlpacaHeaders() {
    }

    static Map<String, String> of(DataSourceProperties.Source source) {
        return Map.of(
                "APCA-API-KEY-ID", source.getApiKey(),
                "APCA-API-SECRET-KEY", source.getSecretKey());
    }
}
