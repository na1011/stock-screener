-- V4: 인증·인가
--
-- spec §6이 정한 "Google OAuth 소셜 로그인 + 허용 이메일 목록"의 저장 구조다.
-- 허용목록을 설정 파일이 아닌 테이블로 두는 이유는 spec §4가 관리자 화면에서
-- 이메일을 추가·삭제하고 권한을 부여하는 기능을 요구하기 때문이다.


-- ---------------------------------------------------------------------------
-- allowed_email — 접근 허용 목록. 권한 부여의 근거(source of truth).
-- ---------------------------------------------------------------------------
CREATE TABLE allowed_email (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      varchar(255) NOT NULL,
    is_admin   boolean      NOT NULL DEFAULT false,
    note       varchar(200),
    invited_by varchar(255),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

-- 이메일은 대소문자를 구분하지 않는다. 원본 컬럼에 유니크를 걸면
-- Foo@gmail.com과 foo@gmail.com이 둘 다 등록되거나, 판정이 어긋난다.
-- 애플리케이션이 소문자로 정규화해 저장하지만, 관리자가 직접 INSERT하는 경로가
-- 결국 생기므로 함수 인덱스로도 막는다.
CREATE UNIQUE INDEX uq_allowed_email ON allowed_email (lower(email));


-- ---------------------------------------------------------------------------
-- app_user — 실제로 로그인한 사용자
--
-- allowed_email과 분리한다. 허용목록에만 있고 아직 로그인하지 않은 사람은
-- Google sub도 이름도 없고, screen.owner_user_id가 참조할 id도 없다.
-- 하나로 합치면 대부분 컬럼이 NULL인 행과 "실제로 로그인했는가" 불리언이 생긴다.
--
-- MUST NOT: app_user.email과 allowed_email.email 사이에 FK를 걸지 않는다.
-- 허용목록에서 제거하는 것이 그 사람의 저장된 화면까지 지워서는 안 된다.
-- 다시 초대하면 화면이 남아 있어야 한다. 둘은 로그인 시점에 이메일로 대조할 뿐이다.
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email            varchar(255) NOT NULL,
    name             varchar(100),
    picture_url      varchar(500),
    provider         varchar(20)  NOT NULL DEFAULT 'GOOGLE',
    -- Google sub 클레임. 이메일은 바뀔 수 있지만 sub는 불변이다.
    -- 인증은 sub로, 인가는 이메일로 한다.
    provider_user_id varchar(100) NOT NULL,
    -- allowed_email.is_admin을 로그인 시점에 복사한 값이다. 아래 주석 참조.
    is_admin         boolean      NOT NULL DEFAULT false,
    is_active        boolean      NOT NULL DEFAULT true,
    last_login_at    timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_user_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT uq_user_provider UNIQUE (provider, provider_user_id)
);

CREATE UNIQUE INDEX uq_user_email ON app_user (lower(email));

-- IMPORTANT — is_admin이 두 테이블에 있는 것은 의도된 중복이다.
--   allowed_email.is_admin : 권한 부여 자체(관리자가 화면에서 설정하는 값). 근거다.
--   app_user.is_admin      : 로그인 시점에 복사된 세션 사실(Spring Security가 읽는 값). 캐시다.
-- 매 인가 검사마다 allowed_email을 조인하면 인증 경로가 허용목록 테이블에 결합되고
-- "권한 회수가 다음 로그인부터 적용된다"는 성질이 암묵적이 된다.
-- MUST: allowed_email.is_admin이 근거이고 app_user.is_admin은 로그인 시 갱신되는 사본이다.
COMMENT ON COLUMN app_user.is_admin IS
    'allowed_email.is_admin의 사본. 로그인 시 갱신된다. 근거는 allowed_email이다.';


-- ---------------------------------------------------------------------------
-- V3에서 미룬 FK — app_user가 이제 존재한다.
-- ---------------------------------------------------------------------------
ALTER TABLE screen
    ADD CONSTRAINT fk_screen_owner FOREIGN KEY (owner_user_id) REFERENCES app_user(id);

ALTER TABLE watchlist_item
    ADD CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
