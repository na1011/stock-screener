package com.stockscreener.web;

import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API 계층이 던지는 예외들.
 *
 * <p>한 파일에 모으는 이유는 이들이 <b>하나의 계약</b>이기 때문이다 — 어떤 상황이 어떤 상태
 * 코드로 나가는지가 한눈에 보여야 프론트와 맞출 수 있고, 파일마다 흩어지면 새 예외를 만들 때
 * 기존 매핑을 보지 않게 된다. 각각은 짧고 상태가 없다.
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /**
     * 조건 하나가 성립하지 않는다.
     *
     * <p>IMPORTANT — {@code conditionIndex}를 반드시 싣는다. 도메인 record의 생성자가 던지는
     * {@code IllegalArgumentException}은 자기가 몇 번째 조건인지 모르므로, 그대로 흘려보내면
     * 화면이 어느 줄을 강조할지 알 수 없고 사용자는 조건을 하나씩 지워가며 찾아야 한다.
     */
    public static class ConditionValidationException extends ApiException {
        public ConditionValidationException(int conditionIndex, String indicatorCode, String detail) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.INVALID_CONDITION, detail);
            with("conditionIndex", conditionIndex);
            with("indicatorCode", indicatorCode);
        }

        public ConditionValidationException(int conditionIndex, String indicatorCode, Throwable cause) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.INVALID_CONDITION, cause.getMessage(), cause);
            with("conditionIndex", conditionIndex);
            with("indicatorCode", indicatorCode);
        }

        /** 조건 목록 전체에 대한 문제(개수 초과, 중첩 그룹 등). 인덱스가 없다. */
        public ConditionValidationException(String detail) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.INVALID_CONDITION, detail);
        }
    }

    /** 존재하지 않거나 폐기된 지표를 참조했다. */
    public static class UnknownIndicatorException extends ApiException {
        public UnknownIndicatorException(String indicatorCode, Integer conditionIndex) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.UNKNOWN_INDICATOR,
                    "사용할 수 없는 지표다: " + indicatorCode);
            with("indicatorCode", indicatorCode);
            with("conditionIndex", conditionIndex);
        }
    }

    /** 실행 대상이 모호하다 — screenId·presetCode·conditions 중 정확히 하나여야 한다. */
    public static class AmbiguousScreeningInputException extends ApiException {
        public AmbiguousScreeningInputException(List<String> present) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.AMBIGUOUS_SCREENING_INPUT,
                    present.isEmpty()
                            ? "screenId·presetCode·conditions 중 하나를 지정해야 한다"
                            : "screenId·presetCode·conditions 중 하나만 지정할 수 있다: " + present);
            with("present", present);
        }
    }

    /**
     * 보존 기간 밖의 일자다.
     *
     * <p>보존 구간을 함께 실어야 화면이 "언제부터 조회할 수 있는지"를 말해줄 수 있다.
     * 구간 없이 거절만 하면 사용자는 날짜를 하나씩 시도하게 된다.
     */
    public static class IndicatorHistoryUnavailableException extends ApiException {
        public IndicatorHistoryUnavailableException(LocalDate requested, LocalDate from, LocalDate to) {
            super(HttpStatus.BAD_REQUEST, ApiProblemType.INDICATOR_HISTORY_UNAVAILABLE,
                    "지표는 최근 구간만 보존한다. 요청한 일자(%s)는 보존 구간(%s ~ %s) 밖이다."
                            .formatted(requested, from, to));
            with("requestedAsOf", requested);
            with("retentionFrom", from);
            with("retentionTo", to);
        }
    }

    /**
     * 지표가 하나도 없다. 초기 적재 전이거나 지표 배치가 실패했다.
     *
     * <p>503 + {@code retryable}인 이유: 사용자가 고칠 수 있는 것이 없고, 배치가 돌면 저절로
     * 해결된다. frontend.md의 "일시적 오류는 사용자 개입 없이 짧게 자동 재시도"가 겨냥하는
     * 경우가 정확히 이것이다.
     */
    public static class IndicatorDataNotReadyException extends ApiException {
        public IndicatorDataNotReadyException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, ApiProblemType.INDICATOR_DATA_NOT_READY,
                    "지표가 아직 계산되지 않았다. 데이터 갱신 배치가 끝난 뒤 다시 시도한다.");
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * 화면을 찾을 수 없다.
     *
     * <p>IMPORTANT — 남의 커스텀 화면에 접근했을 때도 이것을 쓴다. 403은 "있지만 네 것이 아니다"를
     * 알려주어 다른 사용자의 화면 존재 여부를 탐색할 수 있게 한다. 지인이 쓰는 서비스라도
     * 계정별 경계는 유지한다({@code SecurityConfig}가 관리자 영역에 취한 것과 같은 논리다).
     */
    public static class ScreenNotFoundException extends ApiException {
        public ScreenNotFoundException(Object identifier) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.SCREEN_NOT_FOUND,
                    "화면을 찾을 수 없다: " + identifier);
            with("screen", String.valueOf(identifier));
        }
    }

    public static class StockNotFoundException extends ApiException {
        public StockNotFoundException(long stockId) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.STOCK_NOT_FOUND, "종목을 찾을 수 없다: " + stockId);
            with("stockId", stockId);
        }
    }

    public static class UniverseNotFoundException extends ApiException {
        public UniverseNotFoundException(String universeCode) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.UNIVERSE_NOT_FOUND,
                    "유니버스를 찾을 수 없다: " + universeCode);
            with("universeCode", universeCode);
        }
    }

    /** 프리셋은 보이지만 고칠 수 없다. 존재가 이미 드러나 있으므로 404가 아니라 403이다. */
    public static class PresetReadOnlyException extends ApiException {
        public PresetReadOnlyException(String code) {
            super(HttpStatus.FORBIDDEN, ApiProblemType.PRESET_READ_ONLY,
                    "프리셋은 수정하거나 삭제할 수 없다: " + code);
            with("presetCode", code);
        }
    }

    public static class DuplicateScreenNameException extends ApiException {
        public DuplicateScreenNameException(String name, Throwable cause) {
            super(HttpStatus.CONFLICT, ApiProblemType.DUPLICATE_SCREEN_NAME,
                    "같은 이름의 화면이 이미 있다: " + name, cause);
            with("name", name);
        }
    }

    /**
     * 워치리스트가 참조 중인 화면을 지우려 했다.
     *
     * <p>건수를 함께 싣는 이유는 사용자가 <b>다음에 무엇을 해야 하는지</b> 알아야 하기
     * 때문이다. "삭제할 수 없다"만 주면 워치리스트를 하나씩 열어보게 된다.
     */
    public static class ScreenInUseException extends ApiException {
        public ScreenInUseException(long screenId, int watchlistCount, Throwable cause) {
            super(HttpStatus.CONFLICT, ApiProblemType.SCREEN_IN_USE,
                    watchlistCount > 0
                            ? "이 화면을 알림 조건으로 쓰는 워치리스트가 %d건 있어 삭제할 수 없다. 먼저 그 항목의 알림 조건을 바꾼다."
                                    .formatted(watchlistCount)
                            : "이 화면을 참조하는 곳이 있어 삭제할 수 없다.", cause);
            with("screenId", screenId);
            with("watchlistCount", watchlistCount);
        }
    }

    /**
     * 워치리스트 항목을 찾을 수 없다.
     *
     * <p>IMPORTANT — 남의 항목에 접근했을 때도 이것을 쓴다. {@link ScreenNotFoundException}과
     * 같은 논리다 — 403은 "있지만 네 것이 아니다"를 알려주어 다른 사용자의 관심종목 존재
     * 여부를 탐색할 수 있게 한다.
     */
    public static class WatchlistItemNotFoundException extends ApiException {
        public WatchlistItemNotFoundException(long id) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.WATCHLIST_ITEM_NOT_FOUND,
                    "워치리스트 항목을 찾을 수 없다: " + id);
            with("watchlistItemId", id);
        }
    }

    /** 이미 담은 종목을 또 담으려 했다. 화면이 "이미 담겨 있다"로 안내한다. */
    public static class AlreadyWatchedException extends ApiException {
        public AlreadyWatchedException(long stockId, Throwable cause) {
            super(HttpStatus.CONFLICT, ApiProblemType.ALREADY_WATCHED,
                    "이미 워치리스트에 있는 종목이다.", cause);
            with("stockId", stockId);
        }
    }

    /** 관리자가 목록 밖의 잡을 띄우려 했다. 초기 적재가 목록 밖인 것이 의도다. */
    public static class JobNotFoundException extends ApiException {
        public JobNotFoundException(String jobName) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.JOB_NOT_FOUND,
                    "화면에서 띄울 수 있는 잡이 아니다: " + jobName);
            with("jobName", jobName);
        }
    }

    /**
     * 같은 잡이 이미 도는 중이다.
     *
     * <p>{@code retryable}을 켜지 않는다. 자동 재시도는 <b>이 상황을 악화시킨다</b> —
     * 도는 중이라 거절당한 요청을 곧바로 다시 보내면 같은 거절이 반복될 뿐이고, 그 사이
     * 사용자는 아무 안내도 받지 못한다. 화면이 "도는 중"으로 그리고 목록을 새로 고치는 것이
     * 옳은 대응이다.
     */
    public static class JobAlreadyRunningException extends ApiException {
        public JobAlreadyRunningException(String jobName) {
            super(HttpStatus.CONFLICT, ApiProblemType.JOB_ALREADY_RUNNING,
                    "같은 잡이 이미 실행 중이다: " + jobName);
            with("jobName", jobName);
        }
    }

    public static class BatchExecutionNotFoundException extends ApiException {
        public BatchExecutionNotFoundException(long executionId) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.BATCH_EXECUTION_NOT_FOUND,
                    "배치 실행 이력을 찾을 수 없다: " + executionId);
            with("executionId", executionId);
        }
    }

    public static class CorporateActionNotFoundException extends ApiException {
        public CorporateActionNotFoundException(long id, Throwable cause) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.CORPORATE_ACTION_NOT_FOUND,
                    "원장에 그 행이 없다: " + id, cause);
            with("corporateActionId", id);
        }
    }

    public static class AllowedEmailNotFoundException extends ApiException {
        public AllowedEmailNotFoundException(long id, Throwable cause) {
            super(HttpStatus.NOT_FOUND, ApiProblemType.ALLOWED_EMAIL_NOT_FOUND,
                    "허용목록 항목을 찾을 수 없다: " + id, cause);
            with("allowedEmailId", id);
        }
    }

    public static class DuplicateAllowedEmailException extends ApiException {
        public DuplicateAllowedEmailException(String email, Throwable cause) {
            super(HttpStatus.CONFLICT, ApiProblemType.DUPLICATE_ALLOWED_EMAIL,
                    "이미 허용목록에 있는 이메일이다: " + email, cause);
            with("email", email);
        }
    }

    /**
     * 마지막 관리자의 권한을 회수하려 했다.
     *
     * <p>사유를 그대로 말한다. 다른 인가 오류와 달리 <b>숨길 운영 정보가 없고</b>, 숨기면
     * 관리자는 자기 조작이 왜 실패했는지 알 수 없어 같은 시도를 반복한다.
     */
    public static class LastAdminException extends ApiException {
        public LastAdminException(Throwable cause) {
            super(HttpStatus.CONFLICT, ApiProblemType.LAST_ADMIN,
                    "관리자가 한 명도 남지 않게 된다. 다른 계정에 관리자 권한을 먼저 준다.", cause);
        }
    }

    /**
     * 쿼리 파라미터 검증 실패.
     *
     * <p>{@code @Valid} 본문 검증과 <b>같은 형태</b>({@code errors: [{field, message}]})로 낸다.
     * 스프링 기본 예외를 그대로 흘리면 {@code type}이 {@code about:blank}가 되어 프론트의
     * URN 분기가 성립하지 않는다(ADR-0015). 두 경로의 모양이 다르면 프론트가 오류 형식을
     * 두 가지로 다뤄야 하고, 그 사실은 흔치 않은 경로에서만 드러난다.
     */
    public static ApiException invalidField(String field, String message) {
        ApiException exception = of(HttpStatus.BAD_REQUEST, ApiProblemType.INVALID_REQUEST, message);
        exception.with("errors", List.of(Map.of("field", field, "message", message)));
        return exception;
    }

    /** 편의 — 응답 속성이 없는 단순 매핑용. */
    public static ApiException of(HttpStatus status, ApiProblemType type, String detail) {
        return new ApiException(status, type, detail) {
        };
    }

    /** 테스트·조립 편의. */
    public static Map<String, Object> propertiesOf(ApiException e) {
        return Map.copyOf(e.properties());
    }
}
