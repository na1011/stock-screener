#!/usr/bin/env python3
"""rebut18 판 고정 폐포 변이. 사용: mut.py <사본> <변이> <단계> [해시]
변이 e5 = JDK 형(UnaryOperator) 빈 주입 · e1 = @Bean 익명 하위형(ExternalApiClientFactory).
단계 a = 들임(범위 소스가 바뀜) · bump <hash> = 판 8 · c = 범위 밖 본문만 바꿈."""
import sys, pathlib
C = pathlib.Path(sys.argv[1]); M = sys.argv[2]; step = sys.argv[3]
US = C/"src/main/java/com/stockscreener/pipeline/datasource/us"
HTTP = C/"src/main/java/com/stockscreener/pipeline/datasource/http"


def sub(p, old, new, n=1):
    t = p.read_text()
    assert t.count(old) == n, (p, old, t.count(old))
    p.write_text(t.replace(old, new))


if step == "bump":
    h = sys.argv[4]
    sub(US/"XbrlInstanceReader.java", "static final int READER_VERSION = 7;", "static final int READER_VERSION = 8;")
    T = C/"src/test/java/com/stockscreener/pipeline/datasource/us/ReaderVersionPinTest.java"
    sub(T, '"883f9182385814ed3d8665a6f11e8a8527997a254ba81d2a71cd4f2cf1390dfa");',
        '"883f9182385814ed3d8665a6f11e8a8527997a254ba81d2a71cd4f2cf1390dfa",\n            "%s");' % h)
    m = '"d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1");'
    sub(T, m, '"d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",\n            ' + m)
    print("bumped to 8")
    sys.exit()

if M == "e5":
    N = HTTP/"SecNameNormalizer.java"
    if step == "a":
        N.write_text('''package com.stockscreener.pipeline.datasource.http;

import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

/** 공시 폴더 항목 이름을 다듬는다(변이 e5 — 범위 밖 · JDK 형으로 주입). */
@Component
public class SecNameNormalizer implements UnaryOperator<String> {
    @Override
    public String apply(String name) {
        return name;
    }
}
''')
        F = US/"SecFilingClient.java"
        sub(F, "import java.util.function.Supplier;", "import java.util.function.Supplier;\nimport java.util.function.UnaryOperator;")
        sub(F, "    private final Backoff filesBackoff;\n", "    private final Backoff filesBackoff;\n    private final UnaryOperator<String> names;\n")
        sub(F, "    @Autowired\n    public SecFilingClient(ExternalApiClientFactory clientFactory,\n",
            "    public SecFilingClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,\n"
            "                           RateLimiter rateLimiter) {\n"
            "        this(clientFactory, properties, rateLimiter, UnaryOperator.identity());\n"
            "    }\n\n"
            "    @Autowired\n    public SecFilingClient(ExternalApiClientFactory clientFactory,\n")
        sub(F, '''                           @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter,
                properties.getSecEdgar().getBaseUrl(), properties.getSecEdgarFilesBaseUrl(),
                Backoff.standard(), Backoff.standard());''', '''                           @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter,
                           UnaryOperator<String> names) {
        this(clientFactory, properties, rateLimiter,
                properties.getSecEdgar().getBaseUrl(), properties.getSecEdgarFilesBaseUrl(),
                Backoff.standard(), Backoff.standard(), names);''')
        sub(F, '''                    Backoff dataBackoff, Backoff filesBackoff) {
        DataSourceProperties.Source source''', '''                    Backoff dataBackoff, Backoff filesBackoff) {
        this(clientFactory, properties, rateLimiter, dataBaseUrl, filesBaseUrl, dataBackoff, filesBackoff,
                UnaryOperator.identity());
    }

    SecFilingClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                    RateLimiter rateLimiter, String dataBaseUrl, String filesBaseUrl,
                    Backoff dataBackoff, Backoff filesBackoff, UnaryOperator<String> names) {
        this.names = names;
        DataSourceProperties.Source source''')
        sub(F, 'out.add(new FolderItem(name, item.path("size").asString("").trim()));',
            'out.add(new FolderItem(names.apply(name), item.path("size").asString("").trim()));')
    elif step == "c":
        sub(N, "        return name;\n", '        return name.replace("_lab.xml", ".xml");\n')
elif M == "e1":
    N = HTTP/"SecTransportConfig.java"
    if step == "a":
        N.write_text('''package com.stockscreener.pipeline.datasource.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 변이 e1 — 범위가 닿는 형(ExternalApiClientFactory)의 익명 하위형을 빈으로 낸다. */
@Configuration
public class SecTransportConfig {
    @Bean
    @Primary
    public ExternalApiClientFactory primaryClientFactory() {
        return new ExternalApiClientFactory() {
            @Override
            public RestClient create(DataSourceProperties.Source source, String baseUrl, Map<String, String> headers) {
                return super.create(source, baseUrl, headers);
            }
        };
    }
}
''')
    elif step == "c":
        sub(N, "                return super.create(source, baseUrl, headers);\n",
            '                return super.create(source, baseUrl, headers).mutate().defaultHeader("X-Probe", "1").build();\n')
print("ok", M, step)
