package com.stockscreener.web;

import com.stockscreener.auth.web.AllowedEmailRequest;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.indicator.IndicatorPopulation;
import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.pipeline.batch.AdminJobLauncher;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchStatus;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.screening.store.ScreenType;
import com.stockscreener.screening.web.ConditionGroupPayload;
import com.stockscreener.screening.web.ScreenRequest;
import com.stockscreener.screening.web.ScreeningResponse;
import com.stockscreener.screening.web.ScreeningResponseAssembler;
import com.stockscreener.stock.PriceReference;
import com.stockscreener.watchlist.web.WatchlistRequest;

import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트가 거울로 들고 있는 값이 서버와 같은가.
 *
 * <p>대조 대상은 {@code src/test/resources/contract/wire-contract.json}이고, 같은 파일을
 * 프론트의 {@code api/contract.spec.ts}가 읽는다. 어느 쪽이 값을 바꾸든 반대쪽이 실패한다
 * (ADR-0020).
 *
 * <p>IMPORTANT — 이 픽스처는 <b>세 번째 정본이 아니라 트립와이어</b>다. 값의 정본은 언제나
 * 아래에서 읽어오는 백엔드 코드이고, 픽스처를 고쳐야 하는 순간이 "프론트도 봐야 한다"는
 * 신호다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 여기서 확인하는 것은 전부 컴파일된 상수·enum이라
 * 컨텍스트가 필요 없고, 계약이 깨졌을 때 <b>DB나 빈 구성 문제로 오해되지 않아야</b> 한다.
 */
class WireContractTest {

    private static final JsonNode CONTRACT = load();

    private static JsonNode load() {
        try (InputStream in = WireContractTest.class.getResourceAsStream("/contract/wire-contract.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "contract/wire-contract.json이 테스트 클래스패스에 없다. "
                                + "src/test/resources 아래에 있어야 Gradle이 입력으로 인식한다(ADR-0020).");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("계약 픽스처를 읽지 못했다", e);
        }
    }

    /** enum은 <b>집합</b>으로 대조한다. 순서는 계약이 아니라 프론트의 표시 판단이다. */
    private static Set<String> expected(String group, String name) {
        JsonNode node = CONTRACT.get(group).get(name);
        assertThat(node).as("픽스처에 %s.%s이 없다", group, name).isNotNull();
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("프론트가 나열하는 enum이 서버와 같은 값 집합이다")
    void enumsMatch() {
        // 값을 하나 더하면 여기서 멈춘다. 멈추지 않으면 프론트에서 조용히 사라진다 —
        // 실제로 IndicatorCategory가 그랬다(모르는 분류가 조건 빌더에서 통째로 빠졌다).
        assertThat(names(IndicatorCategory.class)).isEqualTo(expected("enums", "IndicatorCategory"));
        assertThat(names(EvaluationMode.class)).isEqualTo(expected("enums", "EvaluationMode"));
        assertThat(names(ScreenType.class)).isEqualTo(expected("enums", "ScreenType"));
        assertThat(names(ConditionOutcome.Status.class)).isEqualTo(expected("enums", "OutcomeStatus"));
        // 값이 늘면 화면이 설명하지 못하는 모집단이 생긴다 — 그 조건의 "상위 50%"가
        // 무엇에 대한 것인지 사용자가 알 수 없게 된다.
        assertThat(names(IndicatorPopulation.class))
                .isEqualTo(expected("enums", "IndicatorPopulation"));
        assertThat(names(IndicatorDescriptor.FavorableEnd.class))
                .isEqualTo(expected("enums", "FavorableEnd"));
        assertThat(names(ConditionGroupPayload.OperandType.class))
                .isEqualTo(expected("enums", "OperandType"));
        assertThat(names(PriceReference.Role.class)).isEqualTo(expected("enums", "ZoneRole"));
        assertThat(names(PriceReference.Strength.class)).isEqualTo(expected("enums", "ZoneStrength"));
        // 관리자 화면의 행위 필터가 이 목록을 거울로 든다. 어긋나면 새로 더한 행위가
        // 필터 선택지에서 조용히 빠지고, 그 행위의 로그는 조회할 방법이 없어진다.
        assertThat(names(InteractionAction.class)).isEqualTo(expected("enums", "InteractionAction"));
        // 관리자 화면이 상태별로 색과 문구를 갈라 그린다. 새 상태가 프론트에 없으면 그 실행이
        // "알 수 없음"으로 떨어지고, PARTIAL_SUCCESS를 실패로 읽는 것과 같은 오해가 생긴다.
        assertThat(names(BatchStatus.class)).isEqualTo(expected("enums", "BatchStatus"));
        assertThat(names(BatchExecutionRecorder.TriggeredBy.class))
                .isEqualTo(expected("enums", "TriggeredBy"));
        assertThat(names(AdminJobLauncher.Parameters.class))
                .isEqualTo(expected("enums", "JobParameters"));
    }

    @Test
    @DisplayName("problem+json의 type URN이 프론트의 분기 대상과 같다")
    void problemTypesMatch() {
        // 프론트는 한글 메시지가 아니라 이 URN으로 분기한다. 슬러그를 바꾸면
        // error.is(...)가 조용히 false가 되고, 보존 구간 포착·중복 이름 처리가
        // 일반 오류 토스트로 강등된다.
        Set<String> actual = Arrays.stream(ApiProblemType.values())
                .map(it -> it.type().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(actual).isEqualTo(
                StreamSupport.stream(CONTRACT.get("problemTypes").spliterator(), false)
                        .map(JsonNode::asString)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Test
    @DisplayName("IMPORTANT — 보존 구간 오류의 추가 속성 이름이 고정되어 있다")
    void retentionProblemPropertiesMatch() {
        // 이 이름들은 Java 컴파일러와 TS 컴파일러 <b>양쪽 모두에게 보이지 않는다</b>.
        // 서버는 with("retentionFrom", …)로 문자열 키를 얹고 프론트는 인덱스 시그니처로 꺼낸다.
        // 어긋나면 날짜 선택기의 범위가 아무 오류 없이 사라지고, 사용자는 같은 보존 오류를
        // 만나며 날짜를 하나씩 다시 찍는다 — 그 필드가 존재하는 이유가 정확히 그것을 막는 것이다.
        LocalDate day = LocalDate.of(2026, 8, 5);
        Set<String> actual = new LinkedHashSet<>(
                new ApiExceptions.IndicatorHistoryUnavailableException(day, day, day)
                        .properties().keySet());

        List<String> fixture = StreamSupport.stream(
                        CONTRACT.get("problemProperties")
                                .get(ApiProblemType.INDICATOR_HISTORY_UNAVAILABLE.type().toString())
                                .spliterator(),
                        false)
                .map(JsonNode::asString)
                .toList();

        assertThat(actual).isEqualTo(new LinkedHashSet<>(fixture));
    }

    @Test
    @DisplayName("프론트가 거울로 든 상수가 서버 값과 같다")
    void constantsMatch() {
        JsonNode constants = CONTRACT.get("constants");

        assertThat(PriceReference.METHOD).isEqualTo(constants.get("priceReferenceMethod").asString());
        assertThat(ScreenDefinitionValidator.MAX_CONDITIONS)
                .isEqualTo(constants.get("maxConditions").asInt());
        assertThat(ScreeningResponseAssembler.FALLBACK_DECIMAL_SCALE)
                .isEqualTo(constants.get("defaultDecimalScale").asInt());

        // 화면이 maxlength로 미리 막아 주는 값이다. 서버가 조이면 사용자는 다 쓴 뒤에야
        // 400을 만나고, 그 오류는 이름 필드가 아니라 일반 오류 배너로 뜬다.
        assertThat(sizeMax("name")).isEqualTo(constants.get("screenNameMaxLength").asInt());
        assertThat(sizeMax("description"))
                .isEqualTo(constants.get("screenDescriptionMaxLength").asInt());
        assertThat(sizeMax(WatchlistRequest.class, "memo"))
                .isEqualTo(constants.get("watchlistMemoMaxLength").asInt());
        assertThat(sizeMax(AllowedEmailRequest.class, "note"))
                .isEqualTo(constants.get("allowedEmailNoteMaxLength").asInt());
    }

    @Test
    @DisplayName("연산자 목록은 픽스처에 넣지 않는다 — 정본이 서버 응답이다")
    void operatorsAreNotMirrored() {
        // 연산자는 /api/indicators가 한글 라벨과 함께 내려준다. 픽스처에 목록을 적으면
        // 프론트가 그것을 나열하고 싶어지고, 그 순간 서버가 정본이라는 구조가 무너진다.
        assertThat(CONTRACT.get("enums").has("ComparisonOperator")).isFalse();
        assertThat(ComparisonOperator.values()).isNotEmpty();
    }

    private static int sizeMax(String component) {
        return sizeMax(ScreenRequest.class, component);
    }

    private static int sizeMax(Class<?> requestType, String component) {
        try {
            // 레코드 컴포넌트의 @Size는 필드로 전파된다. 필드에서 읽는 편이 확실하다.
            Size size = requestType.getDeclaredField(component).getAnnotation(Size.class);
            assertThat(size).as("%s.%s에 @Size가 없다", requestType.getSimpleName(), component)
                    .isNotNull();
            return size.max();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    requestType.getSimpleName() + "에 " + component + " 컴포넌트가 없다", e);
        }
    }
}
