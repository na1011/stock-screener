package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.UniverseSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 지수 구성종목 스냅샷을 {@code universe_membership}의 시간 구간으로 반영한다.
 *
 * <h2>멱등성</h2>
 * <p>편입/제외를 <b>집합 차집합</b>으로 계산하고 교집합은 건드리지 않는다. 그래서 같은 날
 * 두 번 실행해도 두 번째는 아무것도 하지 않는다. 교집합까지 닫았다 여는 방식이면 같은 날
 * 제외와 재편입이 동시에 일어나 {@code ck_membership_period}(0일 구간 금지)에 걸린다.
 *
 * <h2>EXCLUDE 제약을 지키는 방법</h2>
 * <p>구간이 {@code [effective_from, effective_to)} 반열림이므로, 제외를
 * {@code effective_to = 기준일}로 닫고 재편입을 {@code effective_from = 기준일}로 열면
 * {@code daterange}가 겹치지 않는다(끝점 배타). 이것이 {@code ex_membership_no_overlap}을
 * 위반하지 않는 근거다.
 *
 * <h2>왜 종목 단위 실패 격리를 쓰지 않는가</h2>
 * <p>유니버스는 전체가 하나의 정합적 스냅샷이라 부분 적용이 의미 없다. "제외만 되고 편입이
 * 안 된" 중간 상태가 스크리닝에 노출되면 안 되므로 트랜잭션 하나로 묶는다.
 */
@Component
public class UniverseReconciler {

    private static final Logger log = LoggerFactory.getLogger(UniverseReconciler.class);

    /**
     * 한 번에 제외할 수 있는 최대 비율. 이를 넘으면 소스가 부분 목록을 줬다고 보고 거부한다.
     *
     * <p>IMPORTANT — 이 안전장치가 없으면 소스의 파싱 실패 한 번이 유니버스를 통째로 비우고,
     * 그 결과는 "스크리닝에 아무 종목도 나오지 않는다"로 나타난다. 원인을 유니버스에서
     * 찾기까지 시간이 걸린다. 정기 리밸런싱의 실제 교체 규모는 코스피200 기준 연 2회 각
     * 10종목 안팎이므로 30%는 충분히 여유 있는 임계다.
     */
    private static final double MAX_REMOVAL_RATIO = 0.3;

    /**
     * 비율 검사를 적용하기 시작하는 최소 제외 건수.
     *
     * <p>비율만으로 판정하면 구성종목이 적을 때 오탐이 난다 — 3종목 중 1종목 제외가 33%다.
     * 실제 유니버스는 150~500종목이고 정기 리밸런싱의 교체 규모는 10종목 안팎이므로,
     * 이 하한이 실제 운영에서 검사를 무력화하지 않는다. 반대로 하한이 없으면 테스트와
     * 초기 구축 단계에서 정상적인 변경이 거부된다.
     */
    private static final int MIN_REMOVALS_FOR_RATIO_CHECK = 5;

    private final JdbcTemplate jdbcTemplate;

    public UniverseReconciler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 스냅샷을 반영한다.
     *
     * @param stockIdsByTicker 스냅샷의 티커를 stock_id로 해석한 결과. 해석은 호출부의 몫이다 —
     *                         새 종목의 {@code stock} 행 생성은 마스터 수집과 함께 일어나야 하며
     *                         이 클래스의 책임이 아니다.
     * @return 변경 요약
     * @throws IllegalStateException 제외 비율이 임계를 넘을 때. 조용히 진행하지 않는다.
     */
    @Transactional
    public Result reconcile(UniverseSnapshot snapshot, List<Long> stockIdsByTicker) {
        String universeCode = snapshot.universeCode();
        LocalDate effectiveDate = snapshot.asOf();

        Set<Long> current = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT stock_id FROM universe_membership
                WHERE universe_code = ? AND effective_to IS NULL
                """, Long.class, universeCode));
        Set<Long> incoming = new LinkedHashSet<>(stockIdsByTicker);

        Set<Long> removed = new LinkedHashSet<>(current);
        removed.removeAll(incoming);
        Set<Long> added = new LinkedHashSet<>(incoming);
        added.removeAll(current);

        if (removed.size() >= MIN_REMOVALS_FOR_RATIO_CHECK
                && (double) removed.size() / current.size() > MAX_REMOVAL_RATIO) {
            throw new IllegalStateException(
                    "제외 종목이 지나치게 많다: %s에서 %d/%d 제외 (임계 %.0f%%) — 소스가 부분 목록을 준 것으로 보고 중단한다. 소스=%s"
                            .formatted(universeCode, removed.size(), current.size(),
                                    MAX_REMOVAL_RATIO * 100, snapshot.sourceName()));
        }

        for (Long stockId : removed) {
            jdbcTemplate.update("""
                    UPDATE universe_membership SET effective_to = ?
                    WHERE universe_code = ? AND stock_id = ? AND effective_to IS NULL
                    """, effectiveDate, universeCode, stockId);
        }

        for (Long stockId : added) {
            // ON CONFLICT는 같은 날 두 번 실행하는 경우를 막는다. uq_membership이
            // (universe_code, stock_id, effective_from)이므로 재실행이 중복을 만들지 않는다.
            jdbcTemplate.update("""
                    INSERT INTO universe_membership
                        (universe_code, stock_id, effective_from, effective_to, created_at)
                    VALUES (?, ?, ?, NULL, now())
                    ON CONFLICT (universe_code, stock_id, effective_from) DO NOTHING
                    """, universeCode, stockId, effectiveDate);
        }

        if (!removed.isEmpty() || !added.isEmpty()) {
            log.info("유니버스를 갱신했다. {} 기준일={} 편입={} 제외={} 유지={} 소스={}",
                    universeCode, effectiveDate, added.size(), removed.size(),
                    current.size() - removed.size(), snapshot.sourceName());
        }
        return new Result(universeCode, effectiveDate, added.size(), removed.size(),
                current.size() - removed.size());
    }

    /**
     * 현재 편입 중인 종목.
     *
     * <p>일일 갱신 배치의 대상 종목이 이 결과다. {@code effective_to IS NULL}이 "현재 편입 중"을
     * 뜻하며, 부분 인덱스 {@code idx_membership_current}가 이 조회의 접근 경로다.
     */
    public List<Long> currentMembers(String universeCode) {
        return jdbcTemplate.queryForList("""
                SELECT stock_id FROM universe_membership
                WHERE universe_code = ? AND effective_to IS NULL
                ORDER BY stock_id
                """, Long.class, universeCode);
    }

    /** 리밸런싱 결과 요약. 관리자 화면과 알림이 "무엇이 바뀌었는지"를 이것으로 말한다. */
    public record Result(String universeCode, LocalDate effectiveDate,
                         int addedCount, int removedCount, int retainedCount) {

        public boolean changed() {
            return addedCount > 0 || removedCount > 0;
        }
    }
}
