package com.stockscreener.pipeline.batch;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CollectionPhase}의 모든 값을 실패 기록 제약({@code ck_failure_phase})이 받는가.
 *
 * <p>제약이 enum보다 좁으면 그 단계의 실패 기록이 CHECK에 막혀 {@code failUnexpectedly}로 가고
 * <b>종목 하나의 실패가 배치 전체를 죽인다.</b> 단계를 더할 때마다 마이그레이션을 손으로 따라
 * 고쳐 왔는데(V7·V23·V33·V35·V58) 그 짝을 지키는 시험이 없었다.
 *
 * <p>MUST: 손으로 든 목록과 대조하지 않는다 — 제약 정의를 DB에서 읽는다. 목록은 내 기억이 놓친 값을 못 잡는다.
 */
@JpaSliceTest
class CollectionPhaseConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("IMPORTANT — 실패 기록 제약이 CollectionPhase의 모든 값을 받는다")
    void failureConstraintAcceptsEveryPhase() {
        String definition = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname = 'batch_execution_failure' AND c.conname = 'ck_failure_phase'
                """, String.class);

        assertThat(definition).isNotBlank();
        assertThat(Arrays.stream(CollectionPhase.values()).map(Enum::name))
                .allSatisfy(phase -> assertThat(definition).contains("'" + phase + "'"));
    }
}
