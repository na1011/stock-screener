package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 무거운 잡 한 실행이 <b>시장마다 끝까지 마쳤는가</b>를 판정한다(ADR-0174).
 *
 * <h2>왜 필요한가</h2>
 * <p>FR·FSR·G의 정기 실행이 한 시장의 소스 답을 못 받고도(한도 소진 · 일시 장애 · 소스 빈 없음) {@code SUCCESS}나
 * {@code PARTIAL_SUCCESS}로 끝나면 신선도 시계가 그 실행을 「돌았다」로 세어 <b>그 시장이 한 달을 낡은 채 남는다</b> —
 * 다음 차례가 시계로 정해지기 때문이다. 그래서 마감 때 시장마다 묻고, 하나라도 못 끝냈으면 그 실행을 시계에서 뺀다
 * ({@link BatchExecutionRecorder#finish(long, BatchProgress, String, List)}).
 *
 * <h2>시장 미완료의 정의 — 잡이 다루는 시장 집합 안의 m마다, 셋 중 하나</h2>
 * <ol>
 *   <li>(가) 시작 때 m의 소스가 등록돼 있지 않다 — 그 시장은 이 실행이 원리적으로 못 채운다.</li>
 *   <li>(나) m의 대상이 있었고 성공이 0이고 실패가 있다 — 종류와 무관하게 그 시장은 아무것도 안 들어왔다.</li>
 *   <li>(다) m의 실패 중 <b>소스의 답을 못 받은 것</b>이 하나라도 있다({@link #sourceAnswered}가 거짓).</li>
 *   <li>(라) m의 종목이 성공했어도 그 아래 층이 <b>받지 못한 것</b>을 신고했다 — 미국 공시 보충의 받기 실패 · 목록 실패
 *       ({@link #recordUnfetchedLayer}). 보충 층에서는 404·본문 이상도 「다음 실행이 다시 받는다」라 종목 층의 「답함」 술어를
 *       옮기지 않는다 — 해석 실패({@code UNREADABLE})만 끝난 것이고, 그것은 보충 층이 애초에 신고하지 않는다.</li>
 * </ol>
 *
 * <h2>MUST: 「답함」 분류는 {@link #sourceAnswered} 한 곳이다</h2>
 * <p>시계와 재실행(2부)이 같은 함수를 쓴다 — 둘이 따로 정의하면 같은 실패가 한쪽에선 「끝났다」, 다른 쪽에선 「다시 받아야
 * 한다」가 된다. 입력은 종류와 <b>던지는 자리가 단 「답함」 표지</b>({@link com.stockscreener.pipeline.datasource.DataSourceException#sourceAnswered})
 * 둘이다 — 같은 {@code NOT_FOUND}·{@code MALFORMED}가 결정적 답일 수도 끊긴 전송·우리 인자 오류일 수도 있어 종류만으로는 못
 * 가른다. <b>기본은 「답 못 받음」</b>이다. 어느 던지는 자리에 표지를 달지는 어댑터마다의 판정이고 이 함수의 몫이 아니다.
 *
 * <h2>MUST NOT: 실패율 임계로 바꾸지 않는다</h2>
 * <p>ADR-0172 §버린 대안 2가 기각한 것은 「종목 실패율로 FULL·NARROW를 가르는 임계」다 — 임계를 실측으로 못 고르고, 한도
 * 소진의 꼬리가 경계 밑이면 반쯤 못 받은 실행이 FULL이 된다. 여기의 술어는 수가 아니라 <b>실패 하나하나의 성질</b>(소스가
 * 답했는가)로 가르므로 그 기각에 안 선다 — 소스가 답한 실패는 아홉 번 되풀이돼도 미완료가 아니고, 못 받은 실패는 한 건이면
 * 미완료다.
 *
 * <p>스레드 안전하다 — {@link BatchExecutionContext}의 약속과 같다.
 */
public final class MarketCompleteness {

    /** 소스의 답을 못 받은 실패의 사람 말. 키가 없는 종류는 이름 그대로 싣는다. */
    private static final Map<String, String> UNANSWERED_WORDS = Map.of(
            "RATE_LIMITED", "한도",
            "TRANSIENT", "일시",
            "UNEXPECTED", "예상밖",
            "AUTH", "인증",
            "NOT_FOUND", "없음(답 미확인)",
            "MALFORMED", "해석실패(답 미확인)");

    private final Coverage coverage;
    private final Map<String, Counters> byMarket = new ConcurrentHashMap<>();
    /** (라) — 시장 → 받지 못한 아래 층의 종류 → 수. */
    private final Map<String, Map<String, AtomicInteger>> unfetchedLayer = new ConcurrentHashMap<>();

    public MarketCompleteness(Coverage coverage) {
        this.coverage = coverage;
    }

    /**
     * 실패 하나가 <b>소스의 답</b>인가 — 「그 종목은 소스에 물어 답을 받았고, 다시 물어도 같은 답이다」.
     *
     * <ul>
     *   <li><b>답함</b>: 종류가 {@code NOT_FOUND}·{@code MALFORMED}이고 <b>던지는 자리가 「답함」 표지를 단</b> 실패이며
     *       ({@link com.stockscreener.pipeline.datasource.DataSourceException#answered}), <b>그 종목에 상장폐지 증거가 있을 때뿐</b>이다
     *       ({@link RetirementEvidence} — 실행 시작 때 현재 활성 편입이 아님 ∨ 최근 5거래일 봉 없음). 표지(신선한 매핑 판의 부재)의
     *       근거는 상장폐지 종목(CRNX·APGE)이었다. 현재 편입이고 봉이 오는 종목의 부재는 신규 상장의 등록 지연 꼴이라 「다시 물어도
     *       같은 답」이 안 선다 — 답 못 받음으로 두어 다음 차례에 다시 묻는다(구현 반증 · 지금 노출 0).</li>
     *   <li><b>답 못 받음</b>: 그 밖 전부 — {@code RATE_LIMITED} · {@code TRANSIENT} · {@code AUTH} · {@code UNEXPECTED}(소스가
     *       아니라 우리 코드·DB가 그 종목을 처리 못 했다)는 표지와 무관하게, 표지 없는 {@code NOT_FOUND}·{@code MALFORMED}도.
     *       모르는 실패를 「답함」으로 접으면 못 받은 시장이 시계를 민다 — 틀리는 방향을 「한 번 더 돈다」 쪽으로 둔다.</li>
     * </ul>
     *
     * <p>SEC 공시 보충의 {@code UNREADABLE}은 보충 층의 종료 상태라 종목 실패로 오지 않는다 — 보충 층을 이 판정에 잇는 일은
     * 따로다(ADR-0174 §결과).
     *
     * @param failureKind    {@code batch_execution_failure.failure_kind}와 같은 문자열(종류 이름 또는 {@code UNEXPECTED})
     * @param answeredMarked 던지는 자리가 단 「답함」 표지
     * @param retired        그 종목에 상장폐지 증거가 있다({@link RetirementEvidence})
     */
    public static boolean sourceAnswered(String failureKind, boolean answeredMarked, boolean retired) {
        return answeredMarked && retired && ("NOT_FOUND".equals(failureKind) || "MALFORMED".equals(failureKind));
    }

    /** 종목 하나의 결과를 그 시장에 센다. */
    void record(String marketCode, long stockId, boolean succeeded, String failureKind, boolean answeredMarked) {
        Counters counters = byMarket.computeIfAbsent(marketCode, key -> new Counters());
        if (succeeded) {
            counters.success.incrementAndGet();
            return;
        }
        counters.failure.incrementAndGet();
        if (!sourceAnswered(failureKind, answeredMarked, coverage.retired().contains(stockId))) {
            counters.unanswered.computeIfAbsent(String.valueOf(failureKind), key -> new AtomicInteger())
                    .incrementAndGet();
        }
    }

    /**
     * 종목은 성공했지만 그 아래 층(미국 공시 보충)이 받지 못한 것을 그 시장에 센다 — (라).
     *
     * @param label 사유 앞에 붙는 층 이름(「보충」)
     */
    void recordUnfetchedLayer(String marketCode, String label,
                              List<com.stockscreener.pipeline.datasource.DataSourceException.Kind> kinds) {
        if (kinds.isEmpty()) {
            return;
        }
        Map<String, AtomicInteger> byKind = unfetchedLayer.computeIfAbsent(marketCode, key -> new ConcurrentHashMap<>());
        for (com.stockscreener.pipeline.datasource.DataSourceException.Kind kind : kinds) {
            byKind.computeIfAbsent(label + " " + UNANSWERED_WORDS.getOrDefault(kind.name(), kind.name()),
                    key -> new AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * 종목 단위가 아닌 잡이 그 시장을 끝까지 못 마친 것을 센다 — 공시 목록이 그 시장의 날을 못 닫았다(받기 실패 · 장부 쓰기 실패).
     *
     * @param reason 사람 말 사유(「공시 목록 한도」 · 「공시 목록 장부 쓰기」)
     */
    void recordUnfinished(String marketCode, String reason) {
        unfetchedLayer.computeIfAbsent(marketCode, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, key -> new AtomicInteger()).incrementAndGet();
    }

    /** 받지 못한 것의 사람 말 — 종류 이름이 표에 없으면 그대로. */
    static String unansweredWord(DataSourceException.Kind kind) {
        return UNANSWERED_WORDS.getOrDefault(kind.name(), kind.name());
    }

    /** 미완료 시장. 잡의 시장 집합 순서대로다. 비면 전부 끝까지 마쳤다. */
    public List<Incomplete> judge() {
        List<Incomplete> incomplete = new ArrayList<>();
        for (String market : coverage.markets()) {
            List<String> reasons = new ArrayList<>();
            if (coverage.withoutSource().contains(market)) {
                reasons.add("소스 없음");
            }
            Counters counters = byMarket.get(market);
            if (counters != null) {
                int failure = counters.failure.get();
                if (failure > 0 && counters.success.get() == 0) {
                    reasons.add("성공 0 실패 " + failure);
                }
                counters.unanswered.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> reasons.add(
                                UNANSWERED_WORDS.getOrDefault(entry.getKey(), entry.getKey())
                                        + " " + entry.getValue().get()));
            }
            Map<String, AtomicInteger> layer = unfetchedLayer.get(market);
            if (layer != null) {
                layer.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> reasons.add(entry.getKey() + " " + entry.getValue().get()));
            }
            if (!reasons.isEmpty()) {
                incomplete.add(new Incomplete(market, List.copyOf(reasons)));
            }
        }
        return List.copyOf(incomplete);
    }

    /**
     * {@code batch_execution.note} 꼬리에 붙일 사람 말. 미완료가 없으면 빈 문자열이다.
     *
     * <p><b>MUST NOT: {@code 낱말=} 꼴 토큰을 쓰지 않는다</b> — {@code ops/apply-window/env.sh}의 {@code note_nums()}가 {@code (신규|정정|채움|교정|이른판)=}
     * 정규식으로 합산하고, 공시 보충 칸({@code 보충받음=} 등 · {@code FinancialRefillJob.supplementNote})과 앞머리
     * ({@code 연도=} · {@code 대상=})도 같은 꼴이다. 구조화된 답은 {@code incomplete_markets} 컬럼이 들고, 이것은 사람이 읽는 줄이다.
     */
    static String noteTail(List<Incomplete> incomplete) {
        if (incomplete.isEmpty()) {
            return "";
        }
        List<String> parts = incomplete.stream()
                .map(item -> item.market() + " " + String.join(" ", item.reasons()))
                .toList();
        return "시장 미완료 " + String.join(" · ", parts);
    }

    /** 미완료 시장 코드만. */
    static List<String> markets(List<Incomplete> incomplete) {
        return incomplete.stream().map(Incomplete::market).toList();
    }

    /**
     * 잡이 다루는 시장 집합과, <b>시작 때</b> 그중 소스가 등록되지 않은 시장.
     *
     * <p>소스 유무를 시작 때 한 번 묻는 이유: 잡마다 소스 부재가 다른 꼴로 나타난다 — FR은 종목마다 registry
     * {@code NOT_FOUND}(소스가 답한 것처럼 보인다), FSR은 그 시장을 대상에서 빼 0건, G는 그 시장을 건너뛰고 빈 성공이다.
     * 실패 행만 보면 뒤의 둘이 원리적으로 안 보인다.
     *
     * @param markets 잡이 다루는 시장 — 판정 순서이기도 하다
     */
    public record Coverage(List<String> markets, Set<String> withoutSource, Set<Long> retired) {

        public Coverage {
            markets = List.copyOf(markets);
            withoutSource = Set.copyOf(withoutSource);
            retired = Set.copyOf(retired);
        }

        /** 상장폐지 증거 없이 — 「답함」 표지 단 실패도 답 못 받음으로 센다(표지를 다는 자리가 없는 잡). */
        public Coverage(List<String> markets, Set<String> withoutSource) {
            this(markets, withoutSource, Set.of());
        }

        /** 상장폐지 증거가 있는 종목을 싣는다 — 실행 시작 때 한 번 읽은 것이다({@link RetirementEvidence}). */
        public Coverage withRetired(Set<Long> retiredStocks) {
            return new Coverage(markets, withoutSource, retiredStocks);
        }

        public static Coverage of(List<String> markets, Predicate<String> hasSource) {
            Set<String> without = new LinkedHashSet<>();
            for (String market : markets) {
                if (!hasSource.test(market)) {
                    without.add(market);
                }
            }
            return new Coverage(markets, without);
        }
    }

    /** 미완료 시장 하나와 그 사유(사람 말 · 판정 순서). */
    public record Incomplete(String market, List<String> reasons) {
    }

    private static final class Counters {
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger failure = new AtomicInteger();
        private final Map<String, AtomicInteger> unanswered = new ConcurrentHashMap<>();
    }
}
