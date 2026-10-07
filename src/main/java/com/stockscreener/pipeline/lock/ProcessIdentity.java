package com.stockscreener.pipeline.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * 같은 기계의 프로세스 하나를 가리키는 신원 — pid와 <b>프로세스 시작 epoch 초</b>(ADR-0175).
 *
 * <p><b>사망 술어 = pid 부재 ∨ 시작 초 불일치</b>다. pid는 재사용되므로 시작 초가 같아야 같은 프로세스이고, 재부팅 뒤 새
 * 프로세스는 시작 초가 반드시 뒤다. 초 절사인 이유는 셸({@code LC_ALL=C ps -o lstart})이 초까지만 주기 때문이다 — Java
 * {@link ProcessHandle}의 시작 시각을 초로 자르면 둘이 같다(20/20 실측 · 잠금 v3).
 *
 * <p>MUST NOT: 부팅 시각({@code kern.boottime})을 술어에 넣지 않는다. 같은 부팅 안에서도 수면·깨움으로 몇 초 움직여
 * (10-05 기록 18:03:39.28 → 10-07 18:03:42.54 · 같은 부팅) <b>산 보유자를 죽었다고 판정했다</b> — 위험한 방향으로만 틀린다.
 */
public record ProcessIdentity(long pid, long startEpochSecond) {

    private static final Logger log = LoggerFactory.getLogger(ProcessIdentity.class);

    private static final ProcessIdentity CURRENT = of(ProcessHandle.current())
            .orElseThrow(() -> new IllegalStateException(
                    "이 JVM의 프로세스 시작 시각을 OS가 주지 않는다 — 쓰기 잠금의 신원을 만들 수 없다"));

    public static ProcessIdentity current() {
        return CURRENT;
    }

    static Optional<ProcessIdentity> of(ProcessHandle handle) {
        return handle.info().startInstant().map(start -> new ProcessIdentity(handle.pid(), start.getEpochSecond()));
    }

    /**
     * 이 신원의 프로세스가 아직 살아 있는가.
     *
     * <p>pid가 살아 있는데 OS가 시작 시각을 안 주면 <b>산 것으로 친다</b> — 판정할 수 없을 때 빼앗는 쪽으로 기울면 산 보유자를
     * 죽였을 때의 결함(두 JVM이 함께 씀)이 다시 생긴다. 이 갈래는 WARN으로 남긴다.
     */
    public static boolean isAlive(long pid, long startEpochSecond) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        Optional<Instant> start = handle.get().info().startInstant();
        if (start.isEmpty()) {
            log.warn("pid {}의 시작 시각을 OS가 주지 않는다 — 산 것으로 친다(빼앗지 않는다).", pid);
            return true;
        }
        return start.get().getEpochSecond() == startEpochSecond;
    }

    public boolean isAlive() {
        return isAlive(pid, startEpochSecond);
    }

    /** 세션 구성원 표기({@code jvm:<pid>:<시작초>} · {@code exec:<pid>:<시작초>})의 뒤쪽. */
    String memberSuffix() {
        return pid + ":" + startEpochSecond;
    }
}
