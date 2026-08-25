package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 프로파일이 devtools 재기동을 끄고 포트를 비우는지 고정한다.
 *
 * <p>지우면 <b>아무 증상 없이</b> 되돌아간다. 재기동이 켜지면 적재 중 아무 gradle 태스크가
 * {@code build/classes}를 건드리는 순간 {@code BootstrapRunner}가 처음부터 다시 가는데,
 * 로그를 세지 않으면 화면 어디에도 단서가 없다. 실제로 한 번의 백필이 5회 재실행됐다.
 *
 * <p>파일을 직접 읽는 이유는 devtools가 {@code developmentOnly}라 <b>테스트 classpath에
 * 없기 때문이다.</b> 컨텍스트를 띄워 프로퍼티를 묻는 방식으로는 "설정이 있는가"와 "라이브러리가
 * 없어서 무시됐는가"가 구분되지 않는다.
 */
class BootstrapProfileConfigTest {

    @Test
    @DisplayName("bootstrap 프로파일 문서가 devtools 재기동을 끈다")
    void bootstrapProfileDisablesDevtoolsRestart() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        PropertySource<?> bootstrapDocument = documents.stream()
                .filter(d -> "bootstrap".equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElse(null);

        assertThat(bootstrapDocument)
                .as("application.yaml에 bootstrap 프로파일 문서가 있어야 한다")
                .isNotNull();
        assertThat(bootstrapDocument.getProperty("spring.devtools.restart.enabled"))
                .as("적재가 도는 동안 재기동되면 러너가 처음부터 다시 간다")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("bootstrap 프로파일 문서가 포트를 비운다 — 충돌하면 러너가 시작조차 안 하고 gradle은 초록이다")
    void bootstrapProfilePinsEphemeralPort() throws IOException {
        PropertySource<?> bootstrapDocument = bootstrapDocument();

        assertThat(bootstrapDocument.getProperty("server.port"))
                .as("운영 앱이 8080에 뜬 채로 적재·판정을 돌리는 것이 정상 사용 조건이다."
                        + " 충돌하면 컨텍스트가 죽는데 그때 러너는 아예 시작되지 않아"
                        + " BUILD SUCCESSFUL과 종료코드 0이 난다")
                .isEqualTo(0);
    }

    /** 같은 파일에 문서가 여럿이라 프로파일로 골라야 한다. 컨텍스트는 띄우지 않는다 — 위 javadoc 참조. */
    private static PropertySource<?> bootstrapDocument() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        PropertySource<?> document = documents.stream()
                .filter(d -> "bootstrap".equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElse(null);

        assertThat(document)
                .as("application.yaml에 bootstrap 프로파일 문서가 있어야 한다")
                .isNotNull();
        return document;
    }
}
