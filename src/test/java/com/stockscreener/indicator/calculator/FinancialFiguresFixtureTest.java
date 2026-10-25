package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FinancialFiguresFixture#from}이 컴포넌트를 <b>하나도</b> 안 흘리는지 지킨다.
 *
 * <p>이 시험이 있는 이유는 주석이 두 번 실패했기 때문이다. 같은 파일에 이미
 * <i>"컴포넌트를 더하면 여기에도 더한다"</i>가 MUST로 적혀 있었는데 {@code inventories}가
 * 한 번, V40 유도 재료 <b>여덟</b>이 또 한 번 빠졌다.
 *
 * <p><b>빠져도 아무 시험이 안 붉는 것이 이 결손의 성질이다.</b> {@code from()}을 거친 픽스처는
 * 그 값을 조용히 잃고, 그 값을 쓰는 계산기는 「재료가 없다」로 떨어져 <b>초록으로 통과한다</b> —
 * 시험 이름은 그대로라 무엇을 재고 있는지가 바뀐 것을 아무도 모른다. 실제로 영업이익 유도
 * 갈래를 검증하려던 대조군이 그 상태였다.
 */
class FinancialFiguresFixtureTest {

    @Test
    @DisplayName("from()은 record 컴포넌트를 하나도 흘리지 않는다")
    void fromCopiesEveryComponent() throws Exception {
        FinancialFigures source = everyComponentDistinct();

        FinancialFigures copied = FinancialFiguresFixture.from(source).build();

        // 컴포넌트별로 견줘야 어느 것이 빠졌는지가 실패 메시지에 뜬다. equals 하나로 보면
        // "달랐다"까지만 알 수 있고, 그러면 다음 사람이 32개를 손으로 뒤진다.
        for (RecordComponent component : FinancialFigures.class.getRecordComponents()) {
            assertThat(component.getAccessor().invoke(copied))
                    .as("from()이 흘린 컴포넌트: %s", component.getName())
                    .isEqualTo(component.getAccessor().invoke(source));
        }
    }

    /**
     * 모든 컴포넌트가 <b>서로 다른</b> 값을 갖는 표본. 같은 값을 쓰면 자리를 맞바꾼 결함이
     * 안 잡히고, {@code null}을 쓰면 안 복사된 것과 구분되지 않는다.
     */
    private static FinancialFigures everyComponentDistinct() throws Exception {
        @SuppressWarnings("unchecked")
        Constructor<FinancialFigures> canonical = (Constructor<FinancialFigures>)
                FinancialFigures.class.getDeclaredConstructors()[0];

        List<Object> args = new ArrayList<>();
        int seq = 0;
        for (RecordComponent component : FinancialFigures.class.getRecordComponents()) {
            Class<?> type = component.getType();
            seq++;
            if (type == int.class) {
                args.add(2000 + seq);
            } else if (type == boolean.class) {
                // 기본값(false)과 달라야 복사가 빠진 것이 드러난다.
                args.add(true);
            } else if (type == BigDecimal.class) {
                args.add(BigDecimal.valueOf(seq));
            } else if (type == LocalDate.class) {
                args.add(LocalDate.of(2024, 1, 1).plusDays(seq));
            } else if (type == String.class) {
                args.add("KRW");
            } else if (type.isEnum()) {
                args.add(type.getEnumConstants()[0]);
            } else if (type == com.stockscreener.indicator.CommonIncomeRead.class) {
                args.add(new com.stockscreener.indicator.CommonIncomeRead(BigDecimal.valueOf(seq), "T", "decl"));
            } else {
                throw new AssertionError(
                        "새 컴포넌트 타입이다. 이 표본에 그 타입의 값을 더해라: " + type);
            }
        }
        return canonical.newInstance(args.toArray());
    }
}
