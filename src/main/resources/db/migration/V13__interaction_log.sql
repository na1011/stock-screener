-- V13: 사용자 인터랙션 기록
--
-- spec §8의 첫 NFR이다 — "로그인, 스크리닝 실행, 조건 저장처럼 사용자의 의도가 드러나는
-- 주요 행위는 기록되어야 한다. ... 어떤 행위를 기록 대상으로 삼을지는 코드 상에서
-- 명시적으로 표시하는 방식이어야 한다."
--
-- 표시 수단은 @RecordInteraction이고 수집은 InteractionInterceptor 하나가 맡는다(ADR-0028).
-- 이 테이블은 그 수집의 저장소이며, spec §4의 "사용자 인터랙션 로그 조회"가 여기를 읽는다.
--
-- IMPORTANT — 요청 본문은 담지 않는다. 담으려면 요청 스트림을 캐싱해야 하고, 그 비용과
-- 저장되는 개인정보에 비해 얻는 것이 적다. detail에는 URI 템플릿 변수와 지정한 쿼리
-- 파라미터만 들어간다. "누가 언제 어떤 행위를 했는가"는 그것으로 답해진다.


CREATE TABLE interaction_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- NULL을 허용한다. 인증 이전 경로(로그인 실패)와, 인증은 통과했으나 app_user 행을
    -- 찾지 못한 경우가 있다. 후자에서 기록을 통째로 버리면 그 사건 자체가 사라진다 —
    -- 사용자를 몰라도 "그 시각에 그 행위가 있었다"는 남아야 한다.
    user_id     bigint REFERENCES app_user(id),

    -- InteractionAction. CHECK 제약을 걸지 않는 이유는 값이 추가될 때 마이그레이션이
    -- 하나 더 필요해지기 때문이다. 값의 정본은 enum이고 wire-contract.json이 프론트와의
    -- 어긋남을 잡는다. 여기서 막아야 할 잘못된 쓰기 경로는 애플리케이션 밖에 없다.
    action      varchar(40) NOT NULL,

    http_method varchar(10),
    -- 실제 요청 경로(템플릿이 아니라 /api/screens/42). 템플릿은 action이 이미 말해준다.
    path        varchar(200),

    -- 실패한 시도도 기록한다. "저장을 눌렀는데 400이 났다"는 운영자가 알아야 할 사실이고,
    -- 성공만 남기면 사용자가 겪은 마찰이 로그에서 통째로 보이지 않는다.
    status_code int,
    duration_ms int,

    -- URI 템플릿 변수 + 화이트리스트 쿼리 파라미터. 없으면 NULL이다.
    detail      jsonb,

    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- 관리자 화면의 조회 축 셋이 그대로 인덱스 셋이다(spec §4 "조회·필터링").
CREATE INDEX idx_interaction_recent ON interaction_log (occurred_at DESC);
CREATE INDEX idx_interaction_user   ON interaction_log (user_id, occurred_at DESC);
CREATE INDEX idx_interaction_action ON interaction_log (action, occurred_at DESC);

COMMENT ON TABLE interaction_log IS
    '사용자 인터랙션 기록. 붙이는 수단은 @RecordInteraction, 수집은 InteractionInterceptor다(ADR-0028).';
COMMENT ON COLUMN interaction_log.user_id IS
    'NULL 허용. 사용자를 특정하지 못해도 행위 자체는 남긴다.';
COMMENT ON COLUMN interaction_log.detail IS
    'URI 템플릿 변수와 지정한 쿼리 파라미터만. 요청 본문은 담지 않는다.';
