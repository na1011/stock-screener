import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * v11 결정 1 꼴 — RF9 (가)(나)(다)를 v11 문으로 다시. rebut-p2v10 CronSleepSimV10의 가상 단조 시계·큐를 그대로 쓴다
 * (Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler · 수면 = 벽시계만 뜀 · 풀 1).
 *  v11: 같은 사슬 메서드 13:30·15:00·17:00 MON-FRI · 머리에서 now · today · T = 직전 평일 ·
 *       now가 주말이거나 13:00 KST 전이면 기록 없이 지나감 · 「T의 사슬 행이 SUCCESS」면 문에서 지나감 · 아니면 돈다(끝나면 T 완료).
 *       ⑨ 신선도 15:30 독립 · 토 20:00 리밸런싱.
 *  v9 대조: 13:30 하나 · 문 없음.
 * 더한 시나리오: 사슬 도중 종료(kill) 뒤 r분 뒤 재기동 — 새 JVM이 재기동 시각 뒤로 크론을 다시 잡는다 · 중단된 사슬은 완료 아님(스위퍼가 ABORTED).
 */
public class CronSleepSimV11 {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static long mono = 0; static Instant wallAtMono0; static long sleptNanos = 0;
    static List<long[]> sleeps = new ArrayList<>(); static int sleepIdx = 0;
    static Instant wall() { return wallAtMono0.plusNanos(mono + sleptNanos); }
    static long monoOfWall(Instant w) { return (w.getEpochSecond() * 1_000_000_000L + w.getNano()) - (wallAtMono0.getEpochSecond() * 1_000_000_000L + wallAtMono0.getNano()) - sleptNanos; }
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
    static final List<Object[]> log = new ArrayList<>(); // {종류, 시작, 끝, 크론, T}
    static final Set<LocalDate> done = new HashSet<>();
    static Instant killAt; static long downSec; static boolean killed;
    static void reset() { mono = 0; sleptNanos = 0; sleeps.clear(); sleepIdx = 0; queue.clear(); seq = 0; log.clear(); done.clear(); killAt = null; killed = false; }

    static LocalDate prevWeekday(LocalDate d) { LocalDate x = d.minusDays(1); while (x.getDayOfWeek().getValue() >= 6) x = x.minusDays(1); return x; }

    static void schedule(boolean v11) {
        Clock clock = new Clock() { public ZoneId getZone() { return KST; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return wall(); } };
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        String[] crons = v11 ? new String[]{"0 30 13 * * MON-FRI", "0 0 15 * * MON-FRI", "0 0 17 * * MON-FRI"} : new String[]{"0 30 13 * * MON-FRI"};
        for (String c : crons) {
            final String name = c.substring(2, 7).trim();
            sched.schedule(() -> {
                Instant s = wall(); ZonedDateTime z = s.atZone(KST); LocalDate today = z.toLocalDate();
                LocalDate T = prevWeekday(today);
                if (v11 && (z.getDayOfWeek().getValue() >= 6 || z.toLocalTime().isBefore(LocalTime.of(13, 0)))) { log.add(new Object[]{"S", s, s, name, T}); return; }
                if (v11 && done.contains(T)) { log.add(new Object[]{"G", s, s, name, T}); return; }
                long dur = (z.getDayOfWeek() == DayOfWeek.TUESDAY ? 150 : 100) * 60L * 1_000_000_000L;
                if (killAt != null && !killed) {
                    long km = monoOfWall(killAt);
                    if (km >= mono && km < mono + dur) { advance(km - mono); killed = true; log.add(new Object[]{"K", s, wall(), name, T}); return; }
                }
                advance(dur);
                done.add(T);
                log.add(new Object[]{"M", s, wall(), name, T}); }, new CronTrigger(c, KST));
        }
        sched.schedule(() -> { Instant s = wall(); advance(60L * 1_000_000_000L); log.add(new Object[]{"F", s, wall(), "15:30", null}); },
                new CronTrigger("0 30 15 * * MON-FRI", KST));
        sched.schedule(() -> { Instant s = wall(); advance(30 * 60L * 1_000_000_000L); log.add(new Object[]{"R", s, wall(), "SAT", null}); },
                new CronTrigger("0 0 20 * * SAT", KST));
    }

    static void run(boolean v11, LocalDateTime sleepAt, long sleepSec, LocalDateTime kill, long down, boolean print) {
        reset();
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();
        Instant end = LocalDateTime.of(2026, 10, 18, 23, 0).atZone(KST).toInstant();
        if (sleepAt != null) sleeps.add(new long[]{sleepAt.atZone(KST).toEpochSecond(), sleepSec});
        if (kill != null) { killAt = kill.atZone(KST).toInstant(); downSec = down; }
        wallAtMono0 = start;
        schedule(v11);
        while (!queue.isEmpty()) {
            F f = queue.poll(); if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
            if (killed && killAt != null) { // JVM 죽음 → 재기동: 큐를 버리고 재기동 시각에서 크론을 다시 잡는다
                queue.clear(); advance(downSec * 1_000_000_000L); killAt = null; schedule(v11);
            }
        }
        if (print) for (Object[] l : log) System.out.printf("  %s[%s] 시작 %s  끝 %s  T=%s%n", l[0], l[3], w((Instant) l[1]), w((Instant) l[2]), l[4]);
    }

    static int[] metrics() {
        // (가) 13:00 전·주말 실효 시작 수 · (나) 「13:00 뒤 시작 없는 평일」 수 · (나') 완료 안 된 대상일 T 수 · (다) 겹침 수
        int early = 0, noNormal = 0, lostT = 0, overlap = 0, margin = 0;
        Map<LocalDate, List<ZonedDateTime>> byDate = new TreeMap<>();
        List<Instant[]> iv = new ArrayList<>();
        Set<LocalDate> completedT = new HashSet<>();
        for (Object[] l : log) {
            if (!"M".equals(l[0]) && !"K".equals(l[0])) continue;
            ZonedDateTime z = ((Instant) l[1]).atZone(KST);
            if (z.getDayOfWeek().getValue() >= 6 || z.toLocalTime().isBefore(LocalTime.of(13, 0))) early++;
            else if (z.toLocalTime().isBefore(LocalTime.of(13, 30)) && "M".equals(l[0])) margin++; // ADR-0042 여유 30분 안에서 완료된 사슬
            byDate.computeIfAbsent(z.toLocalDate(), k -> new ArrayList<>()).add(z);
            iv.add(new Instant[]{(Instant) l[1], (Instant) l[2]});
            if ("M".equals(l[0])) completedT.add((LocalDate) l[4]);
        }
        for (int i = 0; i < iv.size(); i++) for (int j = i + 1; j < iv.size(); j++)
            if (iv.get(i)[0].isBefore(iv.get(j)[1]) && iv.get(j)[0].isBefore(iv.get(i)[1])) overlap++;
        for (LocalDate dd = LocalDate.of(2026, 10, 6); dd.isBefore(LocalDate.of(2026, 10, 17)); dd = dd.plusDays(1)) {
            if (dd.getDayOfWeek().getValue() > 5) continue;
            List<ZonedDateTime> st = byDate.get(dd);
            if (st == null || st.stream().noneMatch(x -> !x.toLocalTime().isBefore(LocalTime.of(13, 0)))) noNormal++;
            if (!completedT.contains(prevWeekday(dd))) lostT++;
        }
        return new int[]{early, noNormal, lostT, overlap, margin};
    }

    static void sweep(boolean v11) {
        int total = 0, cEarly = 0, cNoNormal = 0, cLostT = 0, cOverlap = 0, cMargin = 0, nMargin = 0;
        for (int h = 0; h < 7 * 24; h++) for (int len : new int[]{1, 2, 4, 8, 12, 16, 24, 40}) {
            LocalDateTime at = LocalDateTime.of(2026, 10, 5, 0, 0).plusHours(h);
            if (at.isBefore(LocalDateTime.of(2026, 10, 5, 10, 0))) continue;
            total++; run(v11, at, len * 3600L, null, 0, false);
            int[] m = metrics();
            if (m[0] > 0) cEarly++; if (m[1] > 0) cNoNormal++; if (m[2] > 0) cLostT++; if (m[3] > 0) cOverlap++; if (m[4] > 0) cMargin++; nMargin += m[4];
        }
        System.out.printf("   %s 수면 스윕 %d칸: (가) 13:00 전·주말 실효 시작이 있는 칸 %d · (나) 13:00 뒤 시작 없는 평일이 있는 칸 %d · (나') 완료 안 된 대상일 T가 있는 칸 %d · (다) 겹침 칸 %d · (라) [13:00,13:30) 시작으로 T를 닫은 칸 %d(사슬 %d)%n",
                v11 ? "v11" : "v9", total, cEarly, cNoNormal, cLostT, cOverlap, cMargin, nMargin);
    }

    static void killSweep(boolean v11) {
        // 사슬 도중 종료: 평일 화(10-06) 13:30 사슬 안 kill 시각 13:40~17:50(10분 간격) × 재기동 지연 1·10·60·240분
        int total = 0, recovered = 0, lost = 0; List<String> lostEx = new ArrayList<>();
        for (int m = 0; m <= 260; m += 10) for (int down : new int[]{1, 10, 60, 240}) {
            LocalDateTime k = LocalDateTime.of(2026, 10, 6, 13, 40).plusMinutes(m);
            run(v11, null, 0, k, down * 60L, false);
            boolean wasKilled = log.stream().anyMatch(l -> "K".equals(l[0]));
            if (!wasKilled) continue;
            total++;
            boolean tDone = log.stream().anyMatch(l -> "M".equals(l[0]) && LocalDate.of(2026, 10, 5).equals(l[4])
                    && ((Instant) l[1]).atZone(KST).toLocalDate().equals(LocalDate.of(2026, 10, 6)));
            if (tDone) recovered++; else { lost++; if (lostEx.size() < 4) lostEx.add(k.toLocalTime() + " +" + down + "분"); }
        }
        System.out.printf("   %s 도중 종료 스윕 %d칸(사슬 안에서 죽은 칸만): 그날 안 T 완료 %d · 그날 T 미완 %d %s%n", v11 ? "v11" : "v9", total, recovered, lost, lostEx);
    }

    public static void main(String[] args) {
        System.out.println("== v11 결정 1 꼴 · 월 10-05 10:00 기동 · 10-18까지 · 사슬 화 150분 그 외 100분");
        System.out.println("-- 대조: 수면 없음"); run(true, null, 0, null, 0, true);
        System.out.println("-- 예: 월 10:00부터 8시간 수면(v10 예 1 꼴)"); run(true, LocalDateTime.of(2026, 10, 5, 10, 0), 8 * 3600L, null, 0, true);
        System.out.println("-- 예: 월 18:00부터 15시간 수면"); run(true, LocalDateTime.of(2026, 10, 5, 18, 0), 15 * 3600L, null, 0, true);
        System.out.println("-- 예: 금 12:00부터 33시간 수면(v10 주말 두 번 꼴)"); run(true, LocalDateTime.of(2026, 10, 9, 12, 0), 33 * 3600L, null, 0, true);
        System.out.println("-- 예: 화 14:00 사슬 도중 종료 → 10분 뒤 재기동"); run(true, null, 0, LocalDateTime.of(2026, 10, 6, 14, 0), 600, true);
        System.out.println("-- 예: 화 17:10 사슬 도중 종료(17:00 발화가 돈 사슬이 아니라 15:00 재시도 뒤) → 10분 뒤 재기동");
        run(true, LocalDateTime.of(2026, 10, 6, 13, 0), 3 * 3600L + 1800L, LocalDateTime.of(2026, 10, 6, 17, 10), 600, true);
        System.out.println("-- 스윕");
        sweep(false); sweep(true);
        killSweep(false); killSweep(true);
    }
}
