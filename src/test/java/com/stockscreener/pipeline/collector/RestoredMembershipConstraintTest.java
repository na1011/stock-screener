package com.stockscreener.pipeline.collector;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V50의 두 CHECK를 <b>실제로 위반시켜</b> 확인한다(ADR-0135).
 *
 * <p><b>{@code ck_restored_is_closed}가 이 결정의 중심이다.</b> 「복원 행은 절대 열려 있지 않다」를
 * 행 단위로 못박으면, {@code effective_to IS NULL}로 모집단을 잡는 운영 질의 25곳과 ADR premise
 * 열여덟이 <b>한 줄도 안 바뀌고 바뀔 수도 없다.</b> 그 성질이 무너지면 복원분이 「지금 편입」으로
 * 새어 들어가는데, <b>값이 그럴듯해 화면에도 로그에도 안 드러난다.</b>
 *
 * <p>{@link UniverseReconcilerTest}가 {@code ex_membership_no_overlap}에 대해 갖는 지위와 같다 —
 * 제약을 우회하지 않고 직접 건드리는 유일한 수단이다.
 */
@JpaSliceTest
class RestoredMembershipConstraintTest {

    private static final String UNIVERSE = "KOSPI200";
    private static final LocalDate FROM = LocalDate.of(2026, 6, 12);
    private static final LocalDate TO = LocalDate.of(2026, 12, 11);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000000', '종목0')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("기본값이 OBSERVED다 — 기존 행과 리밸런싱이 컬럼을 몰라도 된다")
    void defaultsToObserved() {
        insert(FROM, null, null);

        assertThat(originOf()).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("열린 RESTORED 행은 거절된다 — 복원분이 「지금 편입」으로 새지 않는다")
    void rejectsOpenRestoredRow() {
        assertThatThrownBy(() -> insert(FROM, null, "RESTORED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_restored_is_closed");
    }

    @Test
    @DisplayName("닫힌 RESTORED 행은 들어간다")
    void acceptsClosedRestoredRow() {
        insert(FROM, TO, "RESTORED");

        assertThat(originOf()).isEqualTo("RESTORED");
    }

    @Test
    @DisplayName("열린 OBSERVED 행은 그대로 들어간다 — 라이브 경로가 안 막힌다")
    void acceptsOpenObservedRow() {
        insert(FROM, null, "OBSERVED");

        assertThat(originOf()).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("모르는 origin 값은 거절된다 — 오타가 조용한 셋째 평면을 만들지 않는다")
    void rejectsUnknownOrigin() {
        assertThatThrownBy(() -> insert(FROM, TO, "RESTORE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_membership_origin");
    }

    /**
     * 복원 구간과 관측 구간이 <b>인접</b>해도 EXCLUDE에 안 걸린다.
     *
     * <p>{@code daterange}가 {@code [)} 반열림이라 {@code […, X)}와 {@code [X, ∞)}는 겹치지 않는다.
     * <b>이것이 이 결정 전체가 서 있는 자리다</b> — 겹친다면 관측 행의 {@code effective_from}을 뒤로
     * 늘리는 UPDATE가 필요했고, 그것은 {@code uq_membership}의 키를 바꾸는 일이었다.
     */
    @Test
    @DisplayName("복원 구간이 관측 구간의 시작에 딱 붙어도 겹침이 아니다")
    void restoredIntervalMayAbutObservedInterval() {
        insert(TO, null, "OBSERVED");
        insert(FROM, TO, "RESTORED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM universe_membership WHERE stock_id = ?", Integer.class, stockId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("하루라도 겹치면 거절된다 — 인접이 통과하는 것이 제약이 느슨해서가 아니다")
    void rejectsOverlapByOneDay() {
        insert(TO, null, "OBSERVED");

        assertThatThrownBy(() -> insert(FROM, TO.plusDays(1), "RESTORED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_membership_no_overlap");
    }

    private void insert(LocalDate from, LocalDate to, String origin) {
        if (origin == null) {
            jdbcTemplate.update("""
                    INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                    VALUES (?, ?, ?, ?)
                    """, UNIVERSE, stockId, from, to);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO universe_membership
                    (universe_code, stock_id, effective_from, effective_to, origin)
                VALUES (?, ?, ?, ?, ?)
                """, UNIVERSE, stockId, from, to, origin);
    }

    private String originOf() {
        return jdbcTemplate.queryForObject(
                "SELECT origin FROM universe_membership WHERE stock_id = ?", String.class, stockId);
    }
}
