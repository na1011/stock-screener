package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Optional;

/**
 * <b>원본 값이 사라진</b> 파생 지표 행을 보존 창 전체에서 지운다(ADR-0080).
 *
 * <p>{@link PercentileCalculator}와 {@link SectorRelativeCalculator}는 저장된
 * {@code indicator_value}를 <b>다시 읽어</b> 파생 값을 쓴다. 둘 다 {@code INSERT … SELECT …
 * ON CONFLICT}뿐이라 <b>원본이 사라져도 파생 행은 그대로 남는다</b> — SELECT가 그 종목을 안
 * 뽑을 뿐이고, 남은 파생 값은 여전히 그럴듯한 숫자다.
 *
 * <p>원본이 사라지는 경로는 둘이다. 하나는 미보정 기업 행위로 값을 내지 않기로 한 것
 * (ADR-0055)이고, 다른 하나는 계산기가 「값을 그만 낸」 날짜를 걷는 것이다(ADR-0080·0130).
 *
 * <p><b>축이 「원본이 없다」 하나다.</b> 「그날 모집단 밖이다」는 두 계산기가 각자
 * <b>날짜별로</b> 소유한다({@code PercentileCalculator.DELETE_UNWRITTEN} ·
 * {@code SectorRelativeCalculator.DELETE_UNWRITTEN}). 여기서 또 보면 <b>같은 행의 소유가 둘</b>이
 * 되고, 게다가 이 쓸기는 <b>잡마다 한 번</b>이라 {@code backfillUniverseCode}를 모른다 —
 * 비활성 유니버스에 미리 백필해 둔 파생 행을 창 전체에서 지우게 된다(ADR-0136 §버린 대안).
 *
 * <p><b>모집단 부족으로 값을 안 쓴 날의 옛 행은 이 클래스가 못 걷는다.</b> 그때 원본은
 * 그대로 있기 때문이다 — 그 자리는 위 두 여집합 삭제가 소유한다.
 */
final class DerivedIndicatorCleanup {

    private static final String DELETE_ORPHANS_AFTER = """
            DELETE FROM indicator_value derived
            WHERE derived.indicator_code = ?
              AND derived.trade_date > ?
              AND NOT EXISTS (
                  SELECT 1 FROM indicator_value source
                  WHERE source.stock_id = derived.stock_id
                    AND source.trade_date = derived.trade_date
                    AND source.indicator_code = ?
              )
            """;

    /**
     * 보존 창 전체를 쓸어내는 삭제.
     *
     * <p>IMPORTANT — 판정 기준이 "파생 값이 있는데 <b>원본이 없다</b>"이지 "이번에 안 썼다"가
     * 아니다. 후자로 쓰면 값이 안 바뀌어 UPSERT가 0행을 낸 날에도 지우게 된다
     * ({@code IS DISTINCT FROM} 때문에 그런 날이 대부분이다).
     *
     * <p>IMPORTANT — <b>날짜별 정리만으로는 원본이 과거 일자에서 사라진 경우를 못 잡는다.</b>
     * 러너가 「값을 그만 낸」 지표의 옛 행을 지우면(ADR-0080) 그 종목의 파생 행이 <b>보존 창
     * 전체에서</b> 고아가 되는데, 날짜별 정리는 그날 하나만 본다. 그때 남는 것은 <b>원본 없는
     * 백분위</b>이고 화면에서는 그냥 숫자로 보인다.
     *
     * <p>범위를 넓혀도 <b>상한이 필요 없다.</b> 판정이 「이번에 안 썼다」가 아니라 「원본이 없다」라
     * 미래 일자를 잘못 지울 경로가 없다 — 원본이 있으면 남고 없으면 지워지는 것이 언제나 옳다.
     *
     * <p>비용은 실측 지표당 약 200ms이고 스펙이 18개다. <b>잡마다 한 번만</b> 부른다 —
     * 백필은 날짜를 바깥 루프로 도므로 날짜마다 부르면 180배가 된다.
     *
     * @param cutoff 이 날짜 <b>뒤</b>만 본다. 비어 있으면 전 구간이다
     * @return 지운 행 수
     */
    static int removeOrphansAfter(JdbcTemplate jdbcTemplate, String derivedCode, String sourceCode,
                                  Optional<LocalDate> cutoff) {
        return jdbcTemplate.update(DELETE_ORPHANS_AFTER, derivedCode,
                cutoff.orElse(LocalDate.EPOCH), sourceCode);
    }

    private DerivedIndicatorCleanup() {
    }
}
