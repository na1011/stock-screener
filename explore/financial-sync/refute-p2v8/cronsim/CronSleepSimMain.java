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
public class CronSleepSimMain {
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

    static void reset() { mono = 0; sleptNanos = 0; sleeps.clear(); sleepIdx = 0; queue.clear(); seq = 0; log.clear(); }

    /** 하루 사슬을 돌려 화요일의 시작 시각을 이름별로 돌려준다. */
    static Map<String, Instant> run(LocalDateTime sleepAt, long sleepSec, int methodMin, boolean print) {
        reset();
        Clock clock = new Clock() {
            public ZoneId getZone() { return KST; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return wall(); }
        };
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();
        Instant end = LocalDateTime.of(2026, 10, 6, 23, 0).atZone(KST).toInstant();
        if (sleepAt != null) sleeps.add(new long[]{sleepAt.atZone(KST).toEpochSecond(), sleepSec});
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        // v8 결정 1: 13:30 메서드 하나(단계 → 기저 유도 → 일일 갱신 → 무거운 잡) · 14:15 크론 없음.
        Object[][] crons = {
            {"U1330", "0 30 13 * * MON-FRI", 24 * 60},
            {"M1330", "0 15 14 * * MON-FRI", methodMin * 60},
            {"R2000", "0 0 20 * * SAT", 30},
            {"C1400", "0 0 14 * * MON-FRI", 24 * 60},
            {"D1418", "0 18 14 * * MON-FRI", 4},
            {"X1420", "0 20 14 * * MON-FRI", 12 * 60},
            {"F1530", "0 30 15 * * MON-FRI", 1},
        };
        Map<String, Instant> tue = new LinkedHashMap<>();
        for (Object[] c : crons) {
            String name = (String) c[0]; long dur = ((Integer) c[2]) * 1_000_000_000L;
            sched.schedule(() -> { Instant s = wall(); advance(dur);
                    log.add(new String[]{name, w(s), w(wall())});
                    if (s.atZone(KST).toLocalDate().equals(LocalDate.of(2026, 10, 6))) tue.putIfAbsent(name, s); },
                    new CronTrigger((String) c[1], KST));
        }
        while (!queue.isEmpty()) {
            F f = queue.poll();
            if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
        }
        if (print) { for (String[] l : log) System.out.printf("  %-6s 시작 %s  끝 %s%n", l[0], l[1], l[2]); }
        return tue;
    }

    public static void main(String[] args) {
        int methodMin = Integer.parseInt(args[0]);
        System.out.println("== main 크론(13:30 갱신 24분 · 14:15 무거운 잡 " + methodMin + "분 · 사슬 24분 · 유도 4초 · 화해 12분 · 점검 1초) · 월 10-05 10:00 기동");
        System.out.println("-- 대조: 수면 없음");
        run(null, 0, methodMin, true);
        System.out.println("-- 예: 월 수면 시작 15:15 · 60분");
        run(LocalDateTime.of(2026, 10, 5, 15, 15), 3600, methodMin, true);
        System.out.println("-- 스윕: 월 수면 시작(13:00~19:00 · 5분) × 길이(10·20·35·55·90·120분) → 화요일 순서가 「무거운 잡(M) → 유도 → 화해」(사슬<M은 main의 정상 순서라 뺀다)에서 어긋난 칸");
        int bad = 0, total = 0;
        for (int m = 13 * 60; m <= 19 * 60; m += 5) {
            for (int len : new int[]{10, 20, 35, 55, 90, 120}) {
                total++;
                Map<String, Instant> t = run(LocalDateTime.of(2026, 10, 5, m / 60, m % 60), len * 60L, methodMin, false);
                Instant M = t.get("M1330"), C = t.get("C1400"), D = t.get("D1418"), X = t.get("X1420");
                List<String> inv = new ArrayList<>();
                
                if (D != null && M != null && D.isBefore(M)) inv.add("유도<메서드");
                if (X != null && M != null && X.isBefore(M)) inv.add("화해<메서드");
                if (!inv.isEmpty()) { bad++; System.out.printf("  수면 %02d:%02d +%d분 → %s (화 메서드 %s · 화해 %s)%n", m / 60, m % 60, len, inv, w(M), w(X)); }
            }
        }
        System.out.println("-- 어긋난 칸 " + bad + " / " + total);
    }
}
