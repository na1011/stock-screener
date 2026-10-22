package com.stockscreener.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 재적재 관문 설정({@code stockscreener.gate.us} · {@code stockscreener.gate.kr})이 <b>허용된 두 출처</b>에서만 왔는지
 * 기동 때 본다. 관문은 시장마다 하나다(2026-10-04 사용자 결정 「시장별 관문」) — 두 키가 같은 규칙을 탄다.
 *
 * <h2>왜 필요한가</h2>
 * <p>규칙은 「켜는 길은 하나 — 코드 기본값을 뒤집은 jar, 관문 날 bootRun과 사본 실행은 인자」다
 * ({@code CLAUDE.md} 「재적재·백필」). 그런데 Spring은 같은 키를 열 군데 넘는 출처에서 받는다 —
 * 환경변수(relaxed binding {@code STOCKSCREENER_GATE_US}) · {@code SPRING_APPLICATION_JSON} ·
 * {@code JAVA_TOOL_OPTIONS -D} · 작업 디렉터리 {@code config/application.yaml} ·
 * {@code SPRING_CONFIG_ADDITIONAL_LOCATION}. 그중 하나로 새면 꺼진 jar가 조용히 켜진 채 돌고,
 * <b>제품 상태가 「전부 끔」과 「전부 켬」 둘뿐</b>이라는 전제가 깨진다(계획 v5 반증 — Boot 4.1.0 소스).
 * 금지 목록으로 막으면 목록 밖 출처가 늘 남으므로 <b>허용 목록</b>으로 묻는다.
 *
 * <h2>무엇을 보는가</h2>
 * <ul>
 *   <li>실효값을 정한 출처 — 이름 붙은 출처를 순서대로 훑어 그 키를 처음 가진 것. 그것이 명령줄 인자이거나
 *       classpath 안의 {@code application*.yaml}이 아니면 <b>기동을 세운다.</b></li>
 *   <li>환경변수 {@code US_GATE} — 예전에 yaml이 이 이름을 바인딩했다. 지금은 아무 데도 안 닿으므로, 있고
 *       실효값과 <b>다르면</b> 그 사람은 켰다고(껐다고) 믿는 것이라 기동을 세운다. 같으면 둔다 — 배포
 *       {@code .env}의 {@code US_GATE=false}가 운영 기동을 세우면 안 된다.</li>
 * </ul>
 *
 * <p>MUST: 실효값과 출처를 매 기동 INFO로 남긴다 — 배포 뒤 「관문 설정이 무엇으로 떴나」를 답하는 유일한 줄이다.
 */
@Component
public class GateSettingGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(GateSettingGuard.class);

    static final String KEY = "stockscreener.gate.us";

    /** 국내 관문(F2b가 첫 묶음). 미국 키와 같은 규칙으로 본다 — 옛 환경변수 이름은 미국에만 있었다. */
    static final String KR_KEY = "stockscreener.gate.kr";

    /** 예전 yaml이 바인딩하던 환경변수 이름. 지금은 아무 데도 안 닿는다. */
    static final String LEGACY_ENV = "US_GATE";

    private final ConfigurableEnvironment environment;

    public GateSettingGuard(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        for (String key : List.of(KEY, KR_KEY)) {
            Verdict verdict = judge(environment, key);
            log.info("관문 설정 {}={} (출처: {})", key, verdict.value(), verdict.source());
            if (verdict.refusal() != null) {
                throw new IllegalStateException(verdict.refusal());
            }
        }
    }

    /**
     * @param value   실효값. 출처가 없으면 {@code null}
     * @param source  실효값을 정한 출처의 이름
     * @param refusal 기동을 세울 사유. 허용이면 {@code null}
     */
    record Verdict(Boolean value, String source, String refusal) {
    }

    static Verdict judge(ConfigurableEnvironment environment) {
        return judge(environment, KEY);
    }

    static Verdict judge(ConfigurableEnvironment environment, String key) {
        PropertySource<?> origin = null;
        for (PropertySource<?> source : environment.getPropertySources()) {
            // 붙여 둔 집합 출처는 모든 출처를 다시 보여 줄 뿐이라 「어디서 왔나」를 답하지 못한다.
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            // MUST: 바인딩 규칙으로도 묻는다 — 값은 Boot의 relaxed binding(대소문자·비영숫자 무시 · 환경변수 정준형)으로
            // 풀리는데 원시 이름 일치만 보면 `Stockscreener_Gate_Us`·`-Dstockscreener.gate.US` 같은 철자가 값을 켜면서
            // 출처는 classpath yaml로 판정돼 허용됐다(2026-10-14 관문 설정 분리 v2 반증 · Boot 4.1.0 소스로 실측).
            if (source.containsProperty(key) || binds(source, key)) {
                origin = source;
                break;
            }
        }
        if (origin == null) {
            return new Verdict(null, "없음", key + "가 어느 출처에도 없다 — application.yaml의 기본값(false)이"
                    + " 빠졌다. 기본값이 없으면 관문 설정을 읽는 어댑터가 기동에서 죽거나 엉뚱한 값을 받는다.");
        }
        Boolean value = environment.getProperty(key, Boolean.class);
        String source = origin.getName();
        if (!isCommandLine(origin) && !isClasspathApplicationYaml(origin)) {
            return new Verdict(value, source, key + "=" + value + "가 허용되지 않은 출처에서 왔다: " + source
                    + " — 켜는 길은 기본값을 뒤집은 jar(classpath application.yaml)이고, 관문 날 bootRun과 사본"
                    + " 실행만 명령줄 인자(--" + key + "=true)로 켠다. 환경변수·SPRING_APPLICATION_JSON·"
                    + "시스템 속성·작업 디렉터리 config 파일은 쓰지 않는다(CLAUDE.md 「재적재·백필」).");
        }
        Object legacy = KEY.equals(key) ? legacyEnvironmentValue(environment) : null;
        if (legacy != null && Boolean.parseBoolean(String.valueOf(legacy)) != Boolean.TRUE.equals(value)) {
            return new Verdict(value, source, "환경변수 " + LEGACY_ENV + "=" + legacy + "가 있는데 실효값은 " + value
                    + "다(출처: " + source + ") — 이 이름은 더 이상 아무 데도 안 닿는다. 그 줄을 .env에서 걷는다"
                    + "(켜려면 관문 날 명령줄 인자 --" + KEY + "=true · 운영은 기본값을 뒤집은 jar).");
        }
        return new Verdict(value, source, null);
    }

    /** 그 출처가 Boot의 바인딩 규칙으로 이 키를 내는가 — 값을 정하는 해석기({@code getProperty})와 같은 규칙이다. */
    private static boolean binds(PropertySource<?> source, String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
        for (ConfigurationPropertySource adapted : ConfigurationPropertySources.from(source)) {
            if (adapted.getConfigurationProperty(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommandLine(PropertySource<?> source) {
        return CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME.equals(source.getName());
    }

    /**
     * classpath 안의 {@code application.yaml}·{@code application-<profile>.yaml}. Boot가 붙이는 이름이
     * {@code Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'}이다.
     * 작업 디렉터리 파일은 {@code file [...]}로 이름이 갈려 여기서 빠진다.
     */
    private static boolean isClasspathApplicationYaml(PropertySource<?> source) {
        return source instanceof OriginTrackedMapPropertySource
                && source.getName().matches(".*class path resource \\[application(-[A-Za-z0-9_-]+)?\\.ya?ml].*");
    }

    private static Object legacyEnvironmentValue(ConfigurableEnvironment environment) {
        PropertySource<?> systemEnvironment = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return systemEnvironment == null ? null : systemEnvironment.getProperty(LEGACY_ENV);
    }
}
