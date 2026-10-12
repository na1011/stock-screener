package com.stockscreener.pipeline.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 카탈로그 시더(지표 · 프리셋)가 <b>차이를 쓸 수 있는 JVM인가</b>(ADR-0175 · 잠금 v5 4').
 *
 * <p>시더는 기동만으로 카탈로그를 코드 선언에 맞춘다. 그런데 가드가 켜진 DB(운영)에 <b>배포된 jar가 아닌 JVM</b> — 다른 체크아웃의
 * bootRun, 개발용 웹 bootRun — 이 붙으면 그 작업 트리의 카탈로그를 운영에 쓴다. 프리셋 표는 보호 제외 표라 트리거가 이것을 못
 * 막는다 — 여기가 유일한 방어다. 정본은 지금 도는(배포된) jar다.
 *
 * <p>규칙: 차이가 없으면 시더는 문장을 하나도 안 낸다(가드는 문장 단위라 0행 문장도 거절된다). 차이가 있으면
 * <b>가드가 꺼진 DB(사본·시험) 또는 배포된 jar</b>만 쓴다 — 아니면 {@link CatalogDriftException}으로 기동을 세운다.
 *
 * <p>배포된 jar의 판정은 클래스 경로다 — {@code ops/run-app.sh}가 {@code java -jar <배포 폴더>/app.jar}로 띄우므로 그 JVM의
 * {@code java.class.path}가 정확히 그 파일이다. bootRun은 빌드 디렉터리의 클래스 경로라 갈린다.
 */
@Component
public class CatalogWriteGate {

    private static final Logger log = LoggerFactory.getLogger(CatalogWriteGate.class);

    /** {@code ops/lib.sh}의 {@code DEPLOY}와 같다. */
    static final Path DEPLOYED_JAR = Path.of(System.getProperty("user.home"),
            "Library", "Application Support", "stock-screener", "app.jar");

    private final JdbcTemplate jdbcTemplate;

    public CatalogWriteGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 시험·단위 경로용 — 언제나 쓴다(가드가 없는 DB와 같다). */
    public static CatalogWriteGate permissive() {
        return new CatalogWriteGate(null);
    }

    /**
     * 차이를 쓰기 전에 부른다. 못 쓰면 던진다.
     *
     * @param catalog     어느 카탈로그인가(로그·문구용)
     * @param differences 무엇이 다른가 — 사람이 읽을 한 줄씩
     */
    public void requireWritable(String catalog, List<String> differences) {
        if (jdbcTemplate == null || !guardOn() || isDeployedJar()) {
            return;
        }
        throw new CatalogDriftException(catalog + " 카탈로그가 이 DB와 다르다(" + differences.size() + "건: "
                + String.join(" · ", differences.subList(0, Math.min(8, differences.size())))
                + (differences.size() > 8 ? " …" : "") + ") — 가드가 켜진 DB에는 배포된 jar만 카탈로그를 쓴다. "
                + "이 JVM은 배포된 jar가 아니다(" + System.getProperty("java.class.path", "?").split(":")[0] + "). "
                + "같은 커밋을 배포하거나, 다른 체크아웃의 카탈로그를 운영에 쓰려던 것이 아니면 그 체크아웃을 배포된 커밋으로 맞춘다.");
    }

    /**
     * 이 JVM의 쓰기에 가드가 걸리는가 — 트리거와 <b>같은 함수</b>({@code public.write_guard_applies()} · V66)에 묻는다. 신원 범위
     * 접두가 있는 행(시험 JVM)은 그 접두의 연결에만 걸린다 — 따로 적던 시절에는 남의 범위 행 하나에 이 JVM의 시더가 거절됐다.
     * 이 질의는 앱 풀의 연결로 가므로 그 연결의 {@code application_name}(이 JVM의 신원)으로 판정된다. 함수가 없으면(V66 전) 꺼짐이다.
     */
    private boolean guardOn() {
        if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regprocedure('public.write_guard_applies()') IS NOT NULL", Boolean.class))) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT public.write_guard_applies()", Boolean.class));
    }

    static boolean isDeployedJar() {
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.contains(java.io.File.pathSeparator)) {
            return false;
        }
        try {
            return Files.exists(DEPLOYED_JAR) && Files.isSameFile(Path.of(classPath), DEPLOYED_JAR);
        } catch (java.io.IOException e) {
            log.warn("클래스 경로 {}가 배포된 jar인지 못 가렸다 — 아닌 것으로 친다.", classPath, e);
            return false;
        }
    }

    /**
     * 시더 러너가 거절을 받았을 때 — 기동을 세운다.
     *
     * <p>MUST: {@code System.exit(1)}이다(ADR-0175). 러너에서 던지면 devtools가 {@code restartedMain}에서 돌려 gradle이 종료코드
     * 0을 낸다 — 거절이 성공으로 읽힌다. 시험·백테스트 프로파일만 던진다.
     */
    public static void refuseBoot(org.springframework.core.env.Environment environment, String catalog,
                                  CatalogDriftException e) {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test | backtest"))) {
            throw e;
        }
        log.error("{} 카탈로그를 쓰지 않고 기동을 세운다: {}", catalog, e.getMessage());
        System.exit(1);
    }

    /** 가드가 켜진 DB에서 배포된 jar가 아닌 JVM이 카탈로그 차이를 만났다. */
    public static class CatalogDriftException extends RuntimeException {
        public CatalogDriftException(String message) {
            super(message);
        }
    }
}
