import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * v10 결정 1 꼴: 같은 사슬 메서드를 평일 13:30·15:00·17:00 세 크론에 걸고 「오늘(머리의 KST 날짜) 사슬이 시작됐나」 문으로 한 번만 ·
 * 15:30 신선도·편입 점검(독립 크론) · 토 20:00 리밸런싱 — rebut-p2v9 CronSleepSimV9의 가상 시계 하네스를 그대로 쓴다.
 * (원 설명) v9 결정 1 꼴: 평일 13:30 메서드 하나(①~⑨) + 토 20:00 리밸런싱 — Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler를
 * 가상 단조 시계 위에서(수면 = 벽시계만 뜀 · 단조 정지 · 풀 1). refute-p2v8 CronSleepSimV8을 v9 꼴로 고침.
 * 세는 것: 메서드가 (a) KST 12:30 전에 시작(미국 색인·DART 반영 전 — 단계가 보류로 접힘) (b) 같은 KST 날짜에 두 번
 * (c) 주말에 시작 (d) 평일인데 그 날짜에 한 번도 안 시작(그날 판정 D가 「보류」가 아니라 「안 돎」 → 따라잡기 대상 아님)
 * (e) 리밸런싱과 메서드의 겹침(풀 1이라 원리적으로 0이어야 함 — 대조).
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
    static final List<Object[]> log = new ArrayList<>(); // {이름, 시작, 끝}
    static void reset() { mono = 0; sleptNanos = 0; sleeps.clear(); sleepIdx = 0; queue.clear(); seq = 0; log.clear(); }


    static final Set<LocalDate> started = new HashSet<>();
    /** 사슬 소요: 화(G 배정) 140분 · 그 외 104분(v10 결정 10 「≈15:14」) · 문에 걸린 발화는 0분. */
    static Runnable chain(String tag) {
        return () -> { Instant s = wall(); LocalDate d = s.atZone(KST).toLocalDate();
            if (started.contains(d)) { log.add(new Object[]{"N" + tag, s, s}); return; }
            started.add(d);
            long dur = (s.atZone(KST).getDayOfWeek() == DayOfWeek.TUESDAY ? 140 : 104) * 60L * 1_000_000_000L; advance(dur);
            log.add(new Object[]{"M" + tag, s, wall()}); };
    }
    static void run(LocalDateTime sleepAt, long sleepSec, boolean print) {
        reset(); started.clear();
        Clock clock = new Clock() { public ZoneId getZone() { return KST; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return wall(); } };
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(KST).toInstant();
        Instant end = LocalDateTime.of(2026, 10, 18, 23, 0).atZone(KST).toInstant();
        if (sleepAt != null) sleeps.add(new long[]{sleepAt.atZone(KST).toEpochSecond(), sleepSec});
        wallAtMono0 = start;
        ConcurrentTaskScheduler sched = new ConcurrentTaskScheduler(new FakeExec());
        sched.setClock(clock);
        sched.schedule(chain("13"), new CronTrigger("0 30 13 * * MON-FRI", KST));
        sched.schedule(chain("15"), new CronTrigger("0 0 15 * * MON-FRI", KST));
        sched.schedule(chain("17"), new CronTrigger("0 0 17 * * MON-FRI", KST));
        sched.schedule(() -> { Instant s = wall(); advance(60L * 1_000_000_000L); log.add(new Object[]{"F", s, wall()}); },
                new CronTrigger("0 30 15 * * MON-FRI", KST));
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
        System.out.println("== v10 크론(평일 13:30·15:00·17:00 같은 사슬 메서드 + 「오늘 시작했나」 문 · 화 140분 · 그 외 104분 · 15:30 점검 1분 · 토 20:00 리밸런싱 30분) · 월 10-05 10:00 기동 · 10-18까지");
        System.out.println("   M=사슬 실행 · N=문에 걸려 바로 끝난 발화 · F=15:30 점검 · R=리밸런싱 · 꼬리 숫자=어느 크론의 발화인가");
        System.out.println("-- 대조: 수면 없음"); run(null, 0, true);
        System.out.println("-- 예 1: 월 18:00부터 15시간 수면(덮개 닫은 밤)"); run(LocalDateTime.of(2026, 10, 5, 18, 0), 15 * 3600L, true);
        System.out.println("-- 예 2: 금 12:00부터 33시간 수면(주말까지)"); run(LocalDateTime.of(2026, 10, 9, 12, 0), 33 * 3600L, true);
        System.out.println("-- 예 3: 월 15:12부터 2시간 수면(사슬 끝 직후 · 15:30 점검 전)"); run(LocalDateTime.of(2026, 10, 5, 15, 12), 2 * 3600L, true);
        int total = 0, pre13 = 0, fBefore = 0, falseAlarm = 0, lost = 0, wkend = 0, overlap = 0;
        int pre13Days = 0, fBeforeDays = 0, falseAlarmDays = 0;
        for (int h = 0; h < 7 * 24; h += 1) {
            for (int len : new int[]{1, 2, 4, 8, 12, 16, 24, 40}) {
                LocalDateTime at = LocalDateTime.of(2026, 10, 5, 0, 0).plusHours(h);
                if (at.isBefore(LocalDateTime.of(2026, 10, 5, 10, 0))) continue;
                total++; run(at, len * 3600L, false);
                Map<LocalDate, Instant> mStart = new TreeMap<>(); Map<LocalDate, Instant> fStart = new TreeMap<>();
                boolean we = false, ov = false; Instant prevEnd = null;
                for (Object[] l : log) {
                    Instant s = (Instant) l[1]; ZonedDateTime z = s.atZone(KST); String n = (String) l[0];
                    if (prevEnd != null && s.isBefore(prevEnd)) ov = true; prevEnd = (Instant) l[2];
                    if (n.startsWith("M")) { mStart.put(z.toLocalDate(), s); if (z.getDayOfWeek().getValue() >= 6) we = true; }
                    if (n.equals("F")) fStart.putIfAbsent(z.toLocalDate(), s);
                }
                int p = 0, fb = 0, fa = 0, lo = 0;
                for (LocalDate dd = LocalDate.of(2026, 10, 6); dd.isBefore(LocalDate.of(2026, 10, 17)); dd = dd.plusDays(1)) {
                    if (dd.getDayOfWeek().getValue() > 5) continue;
                    Instant m = mStart.get(dd), f = fStart.get(dd);
                    if (m == null) lo++;
                    if (m != null && m.atZone(KST).toLocalTime().isBefore(LocalTime.of(13, 0))) p++;
                    if (f != null && m != null && f.isBefore(m)) fb++;
                    if (f != null && m != null && f.isBefore(m)) fa++;
                }
                if (p > 0) pre13++; if (fb > 0) fBefore++; if (fa > 0) falseAlarm++; if (lo > 0) lost++; if (we) wkend++; if (ov) overlap++;
                pre13Days += p; fBeforeDays += fb; falseAlarmDays += fa;
            }
        }
        System.out.printf("-- 스윕(수면 시작 월 10:00~일 23:00 매시 × 길이 1·2·4·8·12·16·24·40시간) %d칸 · 평일 10-06~10-16 판정%n", total);
        System.out.printf("   (a) 그날 유일한 사슬이 13:00 KST 전에 시작(소스 공개 전 · 그날 13:30 이후 발화는 문에 걸림) %d칸(평일-날 %d)%n", pre13, pre13Days);
        System.out.printf("   (b) 15:30 점검(편입·지표 축 포함)이 같은 날짜 사슬보다 먼저 %d칸(평일-날 %d) — 그 점검 시점 「오늘 사슬 미시작」이 참인데 사슬은 그날 뒤에 돈다%n", fBefore, fBeforeDays);
        System.out.printf("   (d) 평일 하루 이상 사슬 0회 %d · (c) 주말 사슬 %d · (e) 겹침 %d%n", lost, wkend, overlap);
    }
}
