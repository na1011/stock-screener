package com.stockscreener.pipeline.lock;

import org.springframework.core.env.Environment;

import java.util.regex.Pattern;

/**
 * 이 JVM이 DB에 대는 쓰기 신원 — 연결의 {@code application_name}이고 가드 트리거가 보유자와 대조하는 값이다(ADR-0175).
 *
 * <ul>
 *   <li>{@code ss:j:<pid>:<시작초>} — 혼자 쥐는 JVM(웹 앱 · 세션 밖 bootRun · 시험)</li>
 *   <li>{@code ss:s:<id>:j:<pid>:<시작초>} — {@code ops/write-session.sh exec}가 띄워 세션에 합류한 bootRun</li>
 * </ul>
 *
 * <p>세션은 환경변수 {@link #SESSION_ENV}로만 받는다. 인자({@code --write-session=})로는 못 준다 — bootRun의 앱 인자는
 * {@code --args="…"} 문자열 안이라 래퍼가 덧붙일 자리가 없다(잠금 v3 실측: {@code Unknown command-line option}). 래퍼가 그
 * 자식에게만 환경변수를 주므로 범위가 그 명령 하나로 묶인다.
 *
 * <p>MUST: 세션 id는 {@code [a-z0-9-]{1,24}}다. {@code application_name}은 63바이트에서 NOTICE만 내고 잘리고 비ASCII는
 * 이스케이프된다 — 길거나 낯선 id면 세션 부분은 맞는데 JVM 부분이 조용히 틀린다(잠금 v3). 게이트가 왕복 값도 단언한다.
 *
 * @param rawSession 환경변수의 값 그대로(없으면 {@code null}). 꼴이 틀려도 여기서는 던지지 않는다 — 데이터소스를 만드는 중에
 *                   던지면 기동이 엉뚱한 자리에서 죽는다. 거절은 게이트가 종료코드로 한다
 */
public record WriteIdentity(ProcessIdentity process, String rawSession) {

    public static final String SESSION_ENV = "SS_WRITE_SESSION";

    static final Pattern SESSION_ID = Pattern.compile("[a-z0-9-]{1,24}");

    static WriteIdentity resolve(Environment environment) {
        String session = environment.getProperty(SESSION_ENV);
        return new WriteIdentity(ProcessIdentity.current(), session == null || session.isEmpty() ? null : session);
    }

    boolean inSession() {
        return rawSession != null;
    }

    boolean sessionValid() {
        return rawSession != null && SESSION_ID.matcher(rawSession).matches();
    }

    /** 세션 id. 꼴이 틀렸으면 세션이 없는 것으로 친다 — 그 JVM은 게이트가 세운다. */
    String sessionId() {
        return sessionValid() ? rawSession : null;
    }

    public String applicationName() {
        String jvm = "j:" + process.pid() + ":" + process.startEpochSecond();
        return sessionId() == null ? "ss:" + jvm : "ss:s:" + sessionId() + ":" + jvm;
    }
}
