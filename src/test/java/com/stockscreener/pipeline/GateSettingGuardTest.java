package com.stockscreener.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 설정의 출처 판정을 출처마다 고정한다.
 *
 * <p>MUST NOT: 전역 시스템 속성·환경변수를 건드리지 않는다. 시험 JVM은 클래스 사이에서 재사용되므로
 * {@code System.setProperty}가 새면 뒤에 뜨는 컨텍스트 시험이 이 가드에 순서대로 선다(계획 v6 반증 F8).
 * 출처를 직접 꾸민 {@link StandardEnvironment}로만 묻는다.
 */
class GateSettingGuardTest {

    private static final String KEY = GateSettingGuard.KEY;
    private static final String CLASSPATH_YAML =
            "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";

    @Test
    @DisplayName("classpath application.yaml의 기본값은 허용이다 — 운영 jar가 뜨는 길")
    void classpathYamlIsAllowed() {
        GateSettingGuard.Verdict verdict = judge(yaml(false));

        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.value()).isFalse();
        assertThat(verdict.source()).isEqualTo(CLASSPATH_YAML);
    }

    @Test
    @DisplayName("프로파일 yaml(application-bootstrap.yaml)도 classpath면 허용이다")
    void classpathProfileYamlIsAllowed() {
        GateSettingGuard.Verdict verdict = judge(new OriginTrackedMapPropertySource(
                "Config resource 'class path resource [application-bootstrap.yaml]' via location 'optional:classpath:/'",
                Map.of(KEY, true)), yaml(false));

        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.value()).isTrue();
    }

    @Test
    @DisplayName("명령줄 인자는 허용이다 — 관문 날 bootRun과 사본 실행이 켜는 길")
    void commandLineIsAllowed() {
        GateSettingGuard.Verdict verdict = judge(
                new SimpleCommandLinePropertySource("--" + KEY + "=true"), yaml(false));

        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.value()).isTrue();
    }

    @Test
    @DisplayName("IMPORTANT — 환경변수(relaxed binding)로 켜면 기동을 세운다 — .env가 운영 jar로 새는 길")
    void environmentVariableIsRefused() {
        GateSettingGuard.Verdict verdict = judge(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("STOCKSCREENER_GATE_US", "true")),
                yaml(false));

        assertThat(verdict.refusal()).contains("허용되지 않은 출처").contains("systemEnvironment");
    }

    @Test
    @DisplayName("SPRING_APPLICATION_JSON으로 켜면 기동을 세운다")
    void springApplicationJsonIsRefused() {
        assertThat(judge(new MapPropertySource("spring.application.json", Map.of(KEY, true)), yaml(false))
                .refusal()).contains("spring.application.json");
    }

    @Test
    @DisplayName("시스템 속성(JAVA_TOOL_OPTIONS -D)으로 켜면 기동을 세운다")
    void systemPropertyIsRefused() {
        assertThat(judge(new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of(KEY, "true")), yaml(false)).refusal()).contains("systemProperties");
    }

    @Test
    @DisplayName("작업 디렉터리 config/application.yaml로 켜면 기동을 세운다 — classpath가 아니다")
    void fileConfigIsRefused() {
        assertThat(judge(new OriginTrackedMapPropertySource(
                "Config resource 'file [config/application.yaml]' via location 'optional:file:./config/'",
                Map.of(KEY, true)), yaml(false)).refusal()).contains("file [config/application.yaml]");
    }

    @Test
    @DisplayName("옛 이름 US_GATE가 실효값과 다르면 기동을 세운다 — 켰다고 믿는데 꺼진 채 돈다")
    void legacyEnvironmentNameDisagreeingIsRefused() {
        GateSettingGuard.Verdict verdict = judge(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("US_GATE", "true")),
                yaml(false));

        assertThat(verdict.refusal()).contains("US_GATE=true");
    }

    @Test
    @DisplayName("옛 이름 US_GATE가 실효값과 같으면 둔다 — 배포 .env의 US_GATE=false가 운영 기동을 세우면 안 된다")
    void legacyEnvironmentNameAgreeingIsAllowed() {
        GateSettingGuard.Verdict verdict = judge(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("US_GATE", "false")),
                yaml(false));

        assertThat(verdict.refusal()).isNull();
    }

    @Test
    @DisplayName("관문 날 인자로 켰는데 .env에 US_GATE=false가 남아 있으면 선다 — 그 줄을 걷고 띄운다")
    void legacyFalseAgainstCommandLineTrueIsRefused() {
        GateSettingGuard.Verdict verdict = judge(
                new SimpleCommandLinePropertySource("--" + KEY + "=true"),
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("US_GATE", "false")),
                yaml(false));

        assertThat(verdict.refusal()).contains("US_GATE=false");
    }

    @Test
    @DisplayName("어디에도 없으면 기동을 세운다 — yaml 기본값이 빠진 것이다")
    void missingEverywhereIsRefused() {
        assertThat(judge(new MapPropertySource("other", Map.of("x", "y"))).refusal()).contains("어느 출처에도 없다");
    }

    private static OriginTrackedMapPropertySource yaml(boolean value) {
        return new OriginTrackedMapPropertySource(CLASSPATH_YAML, Map.of(KEY, value));
    }

    /** 준 순서가 곧 우선순위다(앞이 이긴다). 실제 시스템 출처는 전부 걷는다. */
    private static GateSettingGuard.Verdict judge(PropertySource<?>... sources) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : sources) {
            propertySources.addLast(source);
        }
        return GateSettingGuard.judge(environment);
    }
}
