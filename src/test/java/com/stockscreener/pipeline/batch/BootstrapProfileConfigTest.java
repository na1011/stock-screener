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
 * 적재 프로파일에서 devtools 재기동이 꺼져 있는지 고정한다.
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
}
