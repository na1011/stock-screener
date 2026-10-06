import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * v9 결정 1 꼴: 평일 13:30 메서드 하나(①~⑨) + 토 20:00 리밸런싱 — Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler를
 * 가상 단조 시계 위에서(수면 = 벽시계만 뜀 · 단조 정지 · 풀 1). refute-p2v8 CronSleepSimV8을 v9 꼴로 고침.
 * 세는 것: 메서드가 (a) KST 12:30 전에 시작(미국 색인·DART 반영 전 — 단계가 보류로 접힘) (b) 같은 KST 날짜에 두 번
 * (c) 주말에 시작 (d) 평일인데 그 날짜에 한 번도 안 시작(그날 판정 D가 「보류」가 아니라 「안 돎」 → 따라잡기 대상 아님)
 * (e) 리밸런싱과 메서드의 겹침(풀 1이라 원리적으로 0이어야 함 — 대조).
 */
public class CronSleepSimV9 {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static long mono = 0; static Instant wallAtMono0; static long sleptNanos = 0;
    static List<long[]> sleeps = new ArrayList<>(); static int sleepIdx = 0;
    static Instant wall() { return wallAtMono0.plusNanos(mono + sleptNanos); }
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
        public boolean isCancelled() { return cancelled; } public boolean isDone() { return false; }
        public Object get() { throw new UnsupportedOperationException(); }
        public Object get(long t, TimeUnit u) { throw new UnsupportedOperationException(); }
    }
    static final PriorityQueue<F> queue = new PriorityQueue<>(); static long seq = 0;
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
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd(E) HH:mm", Locale.KOREAN);
    static String w(Instant i) { return FMT.format(i.atZone(KST)); }
    static final List<Object[]> log = new ArrayList<>(); // {이름, 시작, 끝}
    static void reset() { mono = 0; sleptNanos = 0; sleeps.clear(); sleepIdx = 0; queue.clear(); seq = 0; log.clear(); }

    /** 2026-10-05(월) 10:00 기동 → 10-18(일) 23:00까지. 메서드 소요: 화(G 배정) 150분 · 나머지 평일 100분. 리밸런싱 30분. */
    static void run(LocalDateTime sleepAt, long sleepSec, boolean print) {
        reset();
        Clock clock = new Clock() { public ZoneId getZone() { return KST; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return wall(); } };
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();
        Instant end = LocalDateTime.of(2026, 10, 18, 23, 0).atZone(KST).toInstant();
        if (sleepAt != null) sleeps.add(new long[]{sleepAt.atZone(KST).toEpochSecond(), sleepSec});
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        sched.schedule(() -> { Instant s = wall(); DayOfWeek d = s.atZone(KST).getDayOfWeek();
                long dur = (d == DayOfWeek.TUESDAY ? 150 : 100) * 60L * 1_000_000_000L; advance(dur);
                log.add(new Object[]{"M", s, wall()}); }, new CronTrigger("0 30 13 * * MON-FRI", KST));
        sched.schedule(() -> { Instant s = wall(); advance(30 * 60L * 1_000_000_000L); log.add(new Object[]{"R", s, wall()}); },
                new CronTrigger("0 0 20 * * SAT", KST));
        while (!queue.isEmpty()) {
            F f = queue.poll(); if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
        }
        if (print) for (Object[] l : log) System.out.printf("  %s 시작 %s  끝 %s%n", l[0], w((Instant) l[1]), w((Instant) l[2]));
    }

    public static void main(String[] args) {
        System.out.println("== v9 크론(평일 13:30 메서드 하나 · 화 150분 · 그 외 100분 · 토 20:00 리밸런싱 30분) · 월 10-05 10:00 기동 · 10-18까지");
        System.out.println("-- 대조: 수면 없음"); run(null, 0, true);
        System.out.println("-- 예 1: 월 18:00부터 15시간 수면(덮개 닫은 밤)"); run(LocalDateTime.of(2026, 10, 5, 18, 0), 15 * 3600L, true);
        System.out.println("-- 예 2: 금 12:00부터 33시간 수면(주말까지)"); run(LocalDateTime.of(2026, 10, 9, 12, 0), 33 * 3600L, true);
        int total = 0, early = 0, dbl = 0, wkend = 0, lost = 0, overlap = 0, rBeforeM = 0;
        Map<String, Integer> lostByLen = new TreeMap<>();
        for (int h = 0; h < 7 * 24; h += 1) {            // 수면 시작: 10-05 00:00 ~ 10-11 23:00 · 1시간 간격
            for (int len : new int[]{1, 2, 4, 8, 12, 16, 24, 40}) {
                LocalDateTime at = LocalDateTime.of(2026, 10, 5, 0, 0).plusHours(h);
                if (at.isBefore(LocalDateTime.of(2026, 10, 5, 10, 0))) continue;
                total++; run(at, len * 3600L, false);
                Map<LocalDate, Integer> perDate = new TreeMap<>(); boolean e = false, d2 = false, we = false, ov = false;
                Instant prevEnd = null;
                for (Object[] l : log) {
                    Instant s = (Instant) l[1]; ZonedDateTime z = s.atZone(KST);
                    if (prevEnd != null && s.isBefore(prevEnd)) ov = true; prevEnd = (Instant) l[2];
                    if (!"M".equals(l[0])) continue;
                    perDate.merge(z.toLocalDate(), 1, Integer::sum);
                    if (z.toLocalTime().isBefore(LocalTime.of(12, 30))) e = true;
                    if (z.getDayOfWeek().getValue() >= 6) we = true;
                }
                for (int v : perDate.values()) if (v > 1) d2 = true;
                int lostDays = 0;
                for (LocalDate dd = LocalDate.of(2026, 10, 6); dd.isBefore(LocalDate.of(2026, 10, 17)); dd = dd.plusDays(1))
                    if (dd.getDayOfWeek().getValue() <= 5 && !perDate.containsKey(dd)) lostDays++;
                if (e) early++; if (d2) dbl++; if (we) wkend++; if (lostDays > 0) { lost++; lostByLen.merge(len + "h", 1, Integer::sum); } if (ov) overlap++;
            }
        }
        System.out.printf("-- 스윕(수면 시작 월 10:00~일 23:00 매시 × 길이 1·2·4·8·12·16·24·40시간) %d칸%n", total);
        System.out.printf("   (a) 12:30 KST 전에 메서드 시작 %d · (b) 같은 날짜 두 번 %d · (c) 주말 시작 %d · (d) 평일 하루 이상 메서드 0회 %d %s · (e) 겹침 %d%n",
                early, dbl, wkend, lost, lostByLen, overlap);
    }
}
