import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Spring 7.0.8의 실제 ReschedulingRunnable·CronTrigger를 가상 단조 시계 위에서 돌린다.
 * 수면 = 벽시계만 뛰고 단조 시계는 안 간다(macOS mach_absolute_time). 풀 1(스레드 하나 · 기한 순 · 같으면 등록 순).
 */
public class CronSleepSimV7 {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static long mono = 0;                 // 가상 단조 나노
    static Instant wallAtMono0;           // mono=0일 때 벽시계
    static long sleptNanos = 0;           // 지금까지 잔 시간
    static List<long[]> sleeps = new ArrayList<>(); // {벽시계 시작 epochSec, 길이초}
    static int sleepIdx = 0;

    static Instant wall() { return wallAtMono0.plusNanos(mono + sleptNanos); }

    /** 단조 시계를 d만큼 미는데, 그 사이에 벽시계 수면 시작이 끼면 거기서 벽시계만 뛴다. */
    static void advance(long d) {
        long target = mono + d;
        while (sleepIdx < sleeps.size()) {
            long[] s = sleeps.get(sleepIdx);
            long startMono = (s[0] * 1_000_000_000L) - (wallAtMono0.getEpochSecond() * 1_000_000_000L + wallAtMono0.getNano()) - sleptNanos;
            if (startMono > target) break;
            if (startMono >= mono) { mono = startMono; }
            sleptNanos += s[1] * 1_000_000_000L; sleepIdx++;
        }
        mono = target;
    }

    static final class F implements ScheduledFuture<Object> {
        final Runnable r; final long deadline; final long seq; volatile boolean cancelled;
        F(Runnable r, long deadline, long seq) { this.r = r; this.deadline = deadline; this.seq = seq; }
        public long getDelay(TimeUnit u) { return u.convert(deadline - mono, TimeUnit.NANOSECONDS); }
        public int compareTo(Delayed o) { F f = (F) o; return deadline != f.deadline ? Long.compare(deadline, f.deadline) : Long.compare(seq, f.seq); }
        public boolean cancel(boolean b) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return false; }
        public Object get() { throw new UnsupportedOperationException(); }
        public Object get(long t, TimeUnit u) { throw new UnsupportedOperationException(); }
    }
    static final PriorityQueue<F> queue = new PriorityQueue<>();
    static long seq = 0;

    static final class FakeExec extends AbstractExecutorService implements ScheduledExecutorService {
        public ScheduledFuture<?> schedule(Runnable r, long delay, TimeUnit u) { F f = new F(r, mono + u.toNanos(delay), seq++); queue.add(f); return f; }
        public <V> ScheduledFuture<V> schedule(Callable<V> c, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long a, long b, TimeUnit u) { throw new UnsupportedOperationException(); }
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r, long a, long b, TimeUnit u) { throw new UnsupportedOperationException(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
        public void execute(Runnable r) { throw new UnsupportedOperationException(); }
    }

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd(E) HH:mm:ss", Locale.KOREAN);
    static String w(Instant i) { return FMT.format(i.atZone(KST)); }
    static final List<String[]> log = new ArrayList<>(); // {이름, 시작, 끝}

    public static void main(String[] args) {
        String scenario = args[0];
        Clock clock = new Clock() {
            public ZoneId getZone() { return KST; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return wall(); }
        };
        Instant start; Instant end;
        // v7: 12:30 = 단계 → 무거운 잡(한 메서드) · 13:30 = 일일 갱신(워치리스트 판정 포함).
        // 시나리오 A(대조): 수면 없음. B: 10-07(수) 단계가 13:10에 끝난 뒤 13:20에 덮개를 닫고 70분(4,200초) 잠.
        // C: 같은 날 13:35(일일 갱신 도중)부터 70분 잠. D: B와 같되 잠이 50분(3,000초) — 60분 미만이면 안 뒤집힌다는 대조.
        start = LocalDateTime.of(2026, 10, 6, 18, 0).atZone(KST).toInstant();
        end = LocalDateTime.of(2026, 10, 9, 0, 0).atZone(KST).toInstant();
        switch (scenario) {
            case "A" -> {}
            case "B" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 7, 13, 20, 0).atZone(KST).toEpochSecond(), 4200});
            case "C" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 7, 13, 35, 0).atZone(KST).toEpochSecond(), 4200});
            case "D" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 7, 13, 20, 0).atZone(KST).toEpochSecond(), 3000});
            default -> throw new IllegalArgumentException(scenario);
        }
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        // PipelineScheduler의 일곱 크론과 실측 소요(무거운 잡은 연체 없음 → 즉시 끝).
        Object[][] crons = {
            {"12:30 단계 → 무거운 잡", "0 30 12 * * MON-FRI", 40 * 60},
            {"13:30 일일 갱신(워치리스트)", "0 30 13 * * MON-FRI", 13 * 60 + 36},
            {"20:00 리밸런싱", "0 0 20 * * SAT", 30},
            {"14:00 기업 행위 사슬", "0 0 14 * * MON-FRI", 13 * 60},
            {"14:18 화해 입력 유도", "0 18 14 * * MON-FRI", 4},
            {"14:20 지표 화해", "0 20 14 * * MON-FRI", 8 * 60},
            {"15:30 점검", "0 30 15 * * MON-FRI", 1},
        };
        for (Object[] c : crons) {
            String name = (String) c[0]; long dur = ((Integer) c[2]) * 1_000_000_000L;
            sched.schedule(() -> { Instant s = wall(); advance(dur); log.add(new String[]{name, w(s), w(wall())}); },
                    new CronTrigger((String) c[1], KST));
        }
        while (!queue.isEmpty()) {
            F f = queue.poll();
            if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
        }
        System.out.println("== 시나리오 " + scenario + " · 기동 " + w(start) + " · 수면 " + sleeps.stream().map(s -> w(Instant.ofEpochSecond(s[0])) + " +" + s[1] + "s").toList());
        for (String[] l : log) System.out.printf("  %-40s 시작 %s  끝 %s%n", l[0], l[1], l[2]);
    }
}
