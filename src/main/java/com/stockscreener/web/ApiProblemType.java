package com.stockscreener.web;

import java.net.URI;

/**
 * 오류 종류의 안정적 식별자. 응답의 {@code type} 필드가 된다.
 *
 * <p>프론트가 분기하는 대상은 한글 메시지가 아니라 이 값이다. 메시지는 문구를 다듬느라
 * 언제든 바뀌지만 이것은 바뀌지 않는다 — 메시지로 분기하면 문구를 고치는 순간 화면이 깨진다.
 *
 * <p>{@code urn:} 스킴을 쓰는 이유는 RFC 7807이 {@code type}을 URI로 요구하되 <b>역참조
 * 가능할 필요는 없다</b>고 명시하기 때문이다. {@code https://} 주소를 쓰면 그 주소가 실제로
 * 존재해야 할 것 같은 인상을 주고, 존재하지 않으면 그 자체가 부채가 된다.
 */
public enum ApiProblemType {

    /** 조건 자체가 성립하지 않는다. 어느 조건인지 함께 알린다. */
    INVALID_CONDITION("invalid-condition", "조건이 올바르지 않다"),

    /** 존재하지 않거나 비활성화된 지표를 참조했다. */
    UNKNOWN_INDICATOR("unknown-indicator", "알 수 없는 지표다"),

    /** 요청 본문의 형식·필수값 문제. */
    INVALID_REQUEST("invalid-request", "요청이 올바르지 않다"),

    /** 실행 입력이 서로 배타적인데 둘 이상 왔거나 하나도 오지 않았다. */
    AMBIGUOUS_SCREENING_INPUT("ambiguous-screening-input", "실행 대상을 하나만 지정해야 한다"),

    /**
     * 보존 기간 밖의 일자를 요청했다.
     *
     * <p>IMPORTANT — 이것을 빈 결과로 돌려주면 안 된다. 사용자는 "조건에 맞는 종목이 없다"로
     * 읽고 조건을 계속 고치게 된다. 지표는 보존 창만큼만 남는다(ADR-0011).
     */
    INDICATOR_HISTORY_UNAVAILABLE("indicator-history-unavailable", "해당 일자는 조회 가능 구간 밖이다"),

    /** 지표가 아직 하나도 없다. 초기 적재 전이거나 배치가 실패했다. */
    INDICATOR_DATA_NOT_READY("indicator-data-not-ready", "지표 데이터가 아직 준비되지 않았다"),

    /**
     * 이 전략을 이 조회 대상에서 실행할 수 없다 — 임계가 그 시장 분포로 정해진 적이 없다.
     *
     * <p>IMPORTANT — 조건 단위 「판정불가」로 접지 않는 이유가 있다. 판정불가는 결과 행 안의
     * {@code outcomes}로만 나가는데, 필수 게이트가 판정불가면 종목이 전부 빠져 <b>행이 0개</b>가
     * 되고 사유가 실릴 자리 자체가 없다. P27 축①이 그 처방을 적어 뒀다 —
     * <i>"필수 게이트가 빠진 채 도는 것보다 「이 시장에서 실행 불가」가 낫다"</i>.
     */
    MARKET_THRESHOLD_MISSING("market-threshold-missing", "이 조회 대상에서는 실행할 수 없다"),

    /**
     * 조회 대상을 좁히지 않았는데 활성 유니버스의 통화가 갈린다.
     *
     * <p>한 결과에 두 시장을 섞으면 임계가 어느 쪽 기준인지 정해지지 않는다. 섞는 대신
     * 대상을 고르게 한다 — 사용자가 그렇게 정했다(2026-08-31).
     */
    UNIVERSE_REQUIRED("universe-required", "조회 대상을 하나 골라야 한다"),

    SCREEN_NOT_FOUND("screen-not-found", "화면을 찾을 수 없다"),
    STOCK_NOT_FOUND("stock-not-found", "종목을 찾을 수 없다"),
    UNIVERSE_NOT_FOUND("universe-not-found", "유니버스를 찾을 수 없다"),

    /** 프리셋은 모두에게 보이지만 아무도 고칠 수 없다. 그래서 404가 아니라 403이다. */
    PRESET_READ_ONLY("preset-is-read-only", "프리셋은 수정할 수 없다"),

    DUPLICATE_SCREEN_NAME("duplicate-screen-name", "같은 이름의 화면이 이미 있다"),

    /** 워치리스트가 참조 중인 화면을 지우려 했다. */
    SCREEN_IN_USE("screen-in-use", "다른 곳에서 사용 중인 화면이다"),

    /** 워치리스트 항목이 없다. 남의 항목에 접근했을 때도 이것이다 — 403은 존재를 알려준다. */
    WATCHLIST_ITEM_NOT_FOUND("watchlist-item-not-found", "워치리스트 항목을 찾을 수 없다"),

    /** 이미 담은 종목을 또 담으려 했다. 사용자 입력 문제이지 서버 버그가 아니다. */
    ALREADY_WATCHED("already-watched", "이미 워치리스트에 있는 종목이다"),

    /** 관리자가 띄우려는 잡이 목록에 없다. 초기 적재는 의도적으로 목록 밖이다. */
    JOB_NOT_FOUND("job-not-found", "실행할 수 있는 잡이 아니다"),

    /**
     * 같은 잡이 이미 도는 중이다.
     *
     * <p>프론트가 이것만 따로 분기한다 — 실패가 아니라 "기다리면 된다"이므로 버튼을 잠근
     * 채로 목록만 새로 고치면 되고, 일반 오류 토스트로 강등하면 사용자가 계속 다시 누른다.
     */
    JOB_ALREADY_RUNNING("job-already-running", "같은 잡이 이미 실행 중이다"),

    /**
     * 다른 쓰기(이 앱의 크론·관리자 잡 · bootRun · 손 세션)가 운영 DB 쓰기 잠금을 쥐고 있다(ADR-0175).
     *
     * <p>{@link #JOB_ALREADY_RUNNING}과 가른다 — 그쪽은 「같은 잡」이고 이쪽은 「무엇이든 다른 쓰기」다. 보유자
     * ({@code holderKind}·{@code holderPid}·{@code sessionId}·{@code purpose}·{@code since}·{@code waiting})를 실어 화면이
     * 무엇을 기다리는지 보여 준다.
     */
    WRITE_LOCK_HELD("write-lock-held", "다른 쓰기가 잠금을 쥐고 있다"),

    BATCH_EXECUTION_NOT_FOUND("batch-execution-not-found", "배치 실행 이력을 찾을 수 없다"),

    ALLOWED_EMAIL_NOT_FOUND("allowed-email-not-found", "허용목록 항목을 찾을 수 없다"),
    DUPLICATE_ALLOWED_EMAIL("duplicate-allowed-email", "이미 허용목록에 있는 이메일이다"),

    /**
     * 마지막 관리자의 권한을 회수하려 했다.
     *
     * <p>이 상태가 되면 아무도 관리자 화면에 들어갈 수 없고 복구 수단이 SQL뿐이다.
     * 프론트가 따로 분기해 "왜 안 되는지"를 말해야 하는 몇 안 되는 경우다.
     */
    LAST_ADMIN("last-admin", "마지막 관리자의 권한은 회수할 수 없다"),

    CORPORATE_ACTION_NOT_FOUND("corporate-action-not-found", "기업 행위 원장에 그 행이 없다"),

    CONFLICT("conflict", "요청이 현재 상태와 충돌한다");

    private static final String PREFIX = "urn:stockscreener:";

    private final String slug;
    private final String title;

    ApiProblemType(String slug, String title) {
        this.slug = slug;
        this.title = title;
    }

    public URI type() {
        return URI.create(PREFIX + slug);
    }

    public String title() {
        return title;
    }
}
