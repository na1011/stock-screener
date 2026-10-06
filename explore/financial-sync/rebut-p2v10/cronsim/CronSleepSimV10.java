import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * v10 결정 1 꼴: 같은 사슬 메서드를 평일 13:30 · 15:00 · 17:00 세 크론에 걸고 머리에서 「오늘(KST) 이 사슬이 시작됐나」 문으로
 * 한 번만 돈다 + ⑨ 신선도 15:30 독립 + 토 20:00 리밸런싱. Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler ·
 * 가상 단조 시계(수면 = 벽시계만 뜀 · 단조 정지) · 풀 1. rebut-p2v9 CronSleepSimV9의 시계·큐를 그대로 쓴다.
 * 세는 것(날짜 D = 사슬이 실제 시작한 KST 날짜):
 *  (a) 사슬이 13:00 KST 전에 시작한 평일 날짜 — 국내 소스가 T를 T+1 13시 뒤에 내므로(PipelineScheduler javadoc · ADR-0042)
 *      그날 대상일(T = D의 직전 평일)의 국내 봉이 아직 없다. 문이 그날 13:30 정상 실행을 막는가(= 그 날짜에 다른 시작이 없는가)
 *  (a2) 06:20 KST 전 시작(미국 대상일 세션 + 16분 규칙 전)
 *  (b) 평일 날짜 중 사슬 시작 0회
 *  (c) 같은 날 ⑨(15:30)가 그날 사슬 시작보다 먼저 돈 날짜(그날 사슬은 뒤에 시작) — ⑨의 편입 점검 INDICATOR_DROP이 13:30 뒤 전제
 *  (d) 사슬 시작이 주말 날짜
 * 대조: v9 꼴(13:30 하나 · 문 없음)도 같은 스윕으로 (a)에서 「같은 날 뒤에 정상 시작이 있는가」를 센다.
 */
public class CronSleepSimV10 {
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
    static final List<Object[]> log = new ArrayList<>(); // {이름, 시작, 끝, 크론}
    static final Set<LocalDate> chainStarted = new HashSet<>();
    static void reset() { mono = 0; sleptNanos = 0; sleeps.clear(); sleepIdx = 0; queue.clear(); seq = 0; log.clear(); chainStarted.clear(); }

    static boolean floor = false;
    static void run(boolean v10, LocalDateTime sleepAt, long sleepSec, boolean print) {
        reset();
        Clock clock = new Clock() { public ZoneId getZone() { return KST; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return wall(); } };
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();
        Instant end = LocalDateTime.of(2026, 10, 18, 23, 0).atZone(KST).toInstant();
        if (sleepAt != null) sleeps.add(new long[]{sleepAt.atZone(KST).toEpochSecond(), sleepSec});
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        String[] crons = v10 ? new String[]{"0 30 13 * * MON-FRI", "0 0 15 * * MON-FRI", "0 0 17 * * MON-FRI"}
                             : new String[]{"0 30 13 * * MON-FRI"};
        for (String c : crons) {
            final String name = c.substring(2, 7).trim();
            sched.schedule(() -> {
                Instant s = wall(); LocalDate today = s.atZone(KST).toLocalDate();
                if (v10 && chainStarted.contains(today)) { log.add(new Object[]{"G", s, s, name}); return; } // 문: 오늘 이미 시작
                if (floor && s.atZone(KST).toLocalTime().isBefore(LocalTime.of(13, 0))) { log.add(new Object[]{"S", s, s, name}); return; } // 처방: 13:00 전이면 시작 안 함
                chainStarted.add(today);
                DayOfWeek d = s.atZone(KST).getDayOfWeek();
                long dur = (d == DayOfWeek.TUESDAY ? 150 : 100) * 60L * 1_000_000_000L; advance(dur);
                log.add(new Object[]{"M", s, wall(), name}); }, new CronTrigger(c, KST));
        }
        sched.schedule(() -> { Instant s = wall(); advance(60L * 1_000_000_000L); log.add(new Object[]{"F", s, wall(), "15:30"}); },
                new CronTrigger("0 30 15 * * MON-FRI", KST));
        sched.schedule(() -> { Instant s = wall(); advance(30 * 60L * 1_000_000_000L); log.add(new Object[]{"R", s, wall(), "SAT"}); },
                new CronTrigger("0 0 20 * * SAT", KST));
        while (!queue.isEmpty()) {
            F f = queue.poll(); if (f.cancelled) continue;
            if (f.deadline > mono) advance(f.deadline - mono);
            if (wall().isAfter(end)) break;
            f.r.run();
        }
        if (print) for (Object[] l : log) System.out.printf("  %s[%s] 시작 %s  끝 %s%n", l[0], l[3], w((Instant) l[1]), w((Instant) l[2]));
    }

    static void sweep(boolean v10) {
        int noNormal = 0;
        int total = 0, earlyDisplaced = 0, earlyAny = 0, early0620 = 0, lost = 0, fBefore = 0, wkend = 0;
        List<String> examples = new ArrayList<>();
        for (int h = 0; h < 7 * 24; h += 1) {
            for (int len : new int[]{1, 2, 4, 8, 12, 16, 24, 40}) {
                LocalDateTime at = LocalDateTime.of(2026, 10, 5, 0, 0).plusHours(h);
                if (at.isBefore(LocalDateTime.of(2026, 10, 5, 10, 0))) continue;
                total++; run(v10, at, len * 3600L, false);
                Map<LocalDate, List<ZonedDateTime>> mByDate = new TreeMap<>();
                Map<LocalDate, ZonedDateTime> fByDate = new TreeMap<>();
                for (Object[] l : log) {
                    ZonedDateTime z = ((Instant) l[1]).atZone(KST);
                    if ("M".equals(l[0])) mByDate.computeIfAbsent(z.toLocalDate(), k -> new ArrayList<>()).add(z);
                    if ("F".equals(l[0])) fByDate.putIfAbsent(z.toLocalDate(), z);
                }
                boolean disp = false, any = false, e0620 = false, fb = false, we = false;
                for (Map.Entry<LocalDate, List<ZonedDateTime>> e : mByDate.entrySet()) {
                    List<ZonedDateTime> starts = e.getValue();
                    ZonedDateTime first = starts.get(0);
                    if (first.getDayOfWeek().getValue() >= 6) we = true;
                    if (first.toLocalTime().isBefore(LocalTime.of(13, 0)) && first.getDayOfWeek().getValue() <= 5) {
                        any = true;
                        boolean laterNormal = starts.stream().anyMatch(s -> !s.toLocalTime().isBefore(LocalTime.of(13, 0)));
                        if (!laterNormal) { disp = true; if (examples.size() < 6) examples.add(at + " +" + len + "h → " + first.toLocalDateTime()); }
                    }
                    if (first.toLocalTime().isBefore(LocalTime.of(6, 20))) e0620 = true;
                    ZonedDateTime f = fByDate.get(e.getKey());
                    if (f != null && f.isBefore(first)) fb = true;
                }
                int lostDays = 0;
                for (LocalDate dd = LocalDate.of(2026, 10, 6); dd.isBefore(LocalDate.of(2026, 10, 17)); dd = dd.plusDays(1))
                    if (dd.getDayOfWeek().getValue() <= 5 && !mByDate.containsKey(dd)) lostDays++;
                int noNormalDays = 0;
                for (LocalDate dd = LocalDate.of(2026, 10, 6); dd.isBefore(LocalDate.of(2026, 10, 17)); dd = dd.plusDays(1)) {
                    if (dd.getDayOfWeek().getValue() > 5) continue;
                    List<ZonedDateTime> st = mByDate.get(dd);
                    if (st == null || st.stream().noneMatch(x -> !x.toLocalTime().isBefore(LocalTime.of(13, 0)))) noNormalDays++;
                }
                if (noNormalDays > 0) noNormal++;
                if (disp) earlyDisplaced++; if (any) earlyAny++; if (e0620) early0620++; if (lostDays > 0) lost++; if (fb) fBefore++; if (we) wkend++;
            }
        }
        System.out.printf("   %s 스윕 %d칸: (a) 평일 날짜의 첫 시작이 13:00 전 %d · 그중 그 평일에 13:00 뒤 시작이 없음 %d · (a2) 06:20 전 시작 %d · (b) 평일 사슬 0회 날짜가 있는 칸 %d · (c) ⑨가 그날 사슬보다 먼저 %d · (d) 주말 시작 %d%n",
                (v10 ? "v10(세 크론+문)" : "v9(13:30 하나)") + (floor ? "+13:00 하한" : "") + " [13:00 뒤 시작이 없는 평일이 있는 칸 " + noNormal + "]", total, earlyAny, earlyDisplaced, early0620, lost, fBefore, wkend);
        for (String x : examples) System.out.println("      예: 수면 " + x);
    }

    public static void main(String[] args) {
        System.out.println("== v10 결정 1 꼴(13:30·15:00·17:00 + 「오늘 시작」 문 + ⑨ 15:30 독립 + 토 20:00) · 월 10-05 10:00 기동 · 10-18까지 · 사슬 화 150분 · 그 외 100분");
        System.out.println("-- 대조: 수면 없음"); run(true, null, 0, true);
        System.out.println("-- 예 1: 월 18:00부터 15시간 수면(덮개 닫은 밤)"); run(true, LocalDateTime.of(2026, 10, 5, 18, 0), 15 * 3600L, true);
        System.out.println("-- 예 1 대조(v9 꼴)"); run(false, LocalDateTime.of(2026, 10, 5, 18, 0), 15 * 3600L, true);
        System.out.println("-- 예 3: 월 14:00부터 2시간 수면(사슬 도중)"); run(true, LocalDateTime.of(2026, 10, 5, 14, 0), 2 * 3600L, true);
        System.out.println("-- 스윕(수면 시작 월 10:00~일 23:00 매시 × 길이 1·2·4·8·12·16·24·40시간)");
        sweep(false); sweep(true); floor = true; sweep(true); floor = false;
    }
}
