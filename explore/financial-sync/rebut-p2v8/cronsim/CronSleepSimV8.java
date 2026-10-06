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
public class CronSleepSimV8 {
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
        // v8: 13:30 한 메서드(단계 → 기저 유도 → 일일 갱신 → 무거운 잡). 인자: 시나리오 · 메서드 분(월~수) · 메서드 분(목 = 무거운 잡 날)
        start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();   // 월 10:00 기동
        end = LocalDateTime.of(2026, 10, 9, 23, 0).atZone(KST).toInstant();     // 금 23:00까지
        long methodMin = Long.parseLong(args[1]);
        long heavyDayMethodMin = Long.parseLong(args[2]);
        switch (scenario) {
            // 월: 메서드 13:30~(13:30+m) 끝난 직후 · 사슬이 도는 중에 2시간 덮개 수면
            case "after-method" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 5, 14, 10, 0).atZone(KST).toEpochSecond(), 7200});
            // 월: 같은 자리 55분(50분 문턱 위)
            case "after-method-55" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 5, 14, 10, 0).atZone(KST).toEpochSecond(), 3300});
            // 월: 같은 자리 45분(문턱 아래 · 14:00만 역전되는가)
            case "after-method-45" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 5, 14, 10, 0).atZone(KST).toEpochSecond(), 2700});
            // 목(무거운 잡 날): 메서드가 끝나고 사슬이 도는 중 2시간 → 금
            case "heavy-day" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 8, 13, 30, 0).plusMinutes(heavyDayMethodMin + 5).atZone(KST).toEpochSecond(), 7200});
            // 대조군: 그날 크론이 다 끝난 뒤(18:55) 같은 길이
            case "night" -> sleeps.add(new long[]{LocalDateTime.of(2026, 10, 5, 18, 55, 0).atZone(KST).toEpochSecond(), 7200});
            default -> {}
        }
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        Object[][] crons = {
            {"13:30 메서드(단계·기저·갱신·워치리스트·무거운 잡)", "0 30 13 * * MON-FRI", -1},
            {"20:00 리밸런싱", "0 0 20 * * SAT", 30},
            {"14:00 기업 행위 사슬", "0 0 14 * * MON-FRI", 16 * 60},
            {"14:18 화해 입력 유도", "0 18 14 * * MON-FRI", 4},
            {"14:20 지표 화해", "0 20 14 * * MON-FRI", 7 * 60},
            {"15:30 점검", "0 30 15 * * MON-FRI", 1},
        };
        for (Object[] c : crons) {
            String name = (String) c[0]; int fixed = (Integer) c[2];
            sched.schedule(() -> {
                Instant s = wall();
                long secs = fixed >= 0 ? fixed
                        : (s.atZone(KST).getDayOfWeek() == DayOfWeek.THURSDAY ? heavyDayMethodMin : methodMin) * 60;
                advance(secs * 1_000_000_000L);
                log.add(new String[]{name, w(s), w(wall())});
            }, new CronTrigger((String) c[1], KST));
        }
        while (!queue.isEmpty()) {
            F f = queue.poll();
            if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
        }
        System.out.println("== 시나리오 " + scenario + " · 기동 " + w(start) + " · 수면 " + sleeps.stream().map(s -> w(Instant.ofEpochSecond(s[0])) + " +" + s[1] + "s").toList());
        for (String[] l : log) System.out.printf("  %-44s 시작 %s  끝 %s%n", l[0], l[1], l[2]);
    }
}
