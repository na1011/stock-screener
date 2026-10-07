-- JVM을 건너는 운영 DB 쓰기 배타 잠금과 그 DB 강제(ADR-0175).
--
-- 앱 JVM(크론·관리자 launch·원장 판정 PUT) · bootRun JVM · 손 세션(psql·pg_restore·bootRun 연쇄)이 같은 DB의 파이프라인
-- 표를 함께 쓰지 못하게 한다. 보유자는 write_lock 한 행(DB당)이고, 파이프라인 표의 문장 단위 트리거가 쓰는 연결의 신원
-- (application_name)이 그 보유자인지 본다 — 빠뜨린 진입점·옛 jar·무신원 psql이 시끄럽게 실패해 목록이 스스로 검사된다.
--
-- MUST: 이 파일은 멱등이다(IF NOT EXISTS · OR REPLACE · DROP EVENT TRIGGER IF EXISTS). V66 앞 덤프로 되돌린 DB에는 이력이
-- 62인데 V66 객체가 남을 수 있고(세션 신원 복원 실측), 그때 다음 Flyway가 「already exists」로 기동을 멈추면 안 된다.
-- MUST NOT: 여기서 guard_setting에 행을 넣지 않는다. 운영 켜기는 잠금 jar 배포 뒤 사용자 승인이다 — 행이 없으면 꺼짐이다.

-- ① 스위치. 행 = 켤 DB 이름. current_database()와 같은 행이 있을 때만 켜진다 — 이름이 다른 사본·복원 DB는 저절로 꺼진다
--    (ALTER DATABASE 설정은 pg_dump -Fc가 안 옮기고 열린 세션에 늦게 반영돼 버렸다 · 잠금 v3).
--    identity_prefix가 있으면 그 접두로 시작하는 신원의 문장만 검사한다 — 시험이 자기 JVM(ss:j:<pid>:)만 켜고, 죽어 남아도
--    그 pid에만 걸리게.
CREATE TABLE IF NOT EXISTS guard_setting (
    db_name         text        PRIMARY KEY,
    identity_prefix text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    note            text
);
COMMENT ON TABLE guard_setting IS
    '쓰기 가드 스위치(ADR-0175). db_name = current_database()인 행이 있으면 파이프라인 표의 쓰기를 잠금 보유자에게만 허용한다. '
    'identity_prefix가 NULL이면 모든 신원, 아니면 그 접두의 신원만 검사한다(시험 JVM 범위). 운영 행은 사용자 승인으로만 넣는다';

-- ② 잠금. DB당 한 행이고 반납해도 행은 남는다(보유 칸만 비움) — 대기 표지가 보유자와 따로 살아야 하기 때문이다.
--    version은 모든 갱신에서 1씩 오르고 획득·합류·표지가 그것으로 CAS한다(관측한 행이 그대로일 때만 바꾼다).
--    사망 술어는 pid + 프로세스 시작 초다(부팅 시각은 같은 부팅 안에서도 움직여 산 보유자를 죽었다고 판정했다 — 잠금 v3 실측).
CREATE TABLE IF NOT EXISTS write_lock (
    db_name          text        PRIMARY KEY,
    version          bigint      NOT NULL DEFAULT 0,
    holder_kind      varchar(8),
    token            uuid,
    purpose          text,
    session_id       varchar(24),
    holder_pid       bigint,
    holder_start     bigint,
    members          text[]      NOT NULL DEFAULT '{}',
    acquired_at      timestamptz,
    heartbeat_at     timestamptz,
    last_activity_at timestamptz,
    waiter_pid       bigint,
    waiter_start     bigint,
    waiter_purpose   text,
    waiter_since     timestamptz,
    CONSTRAINT ck_write_lock_kind CHECK (holder_kind IS NULL OR holder_kind IN ('WEB', 'BOOTRUN', 'SESSION')),
    CONSTRAINT ck_write_lock_held CHECK ((holder_kind IS NULL) = (token IS NULL)),
    CONSTRAINT ck_write_lock_session CHECK ((holder_kind = 'SESSION') = (session_id IS NOT NULL)),
    CONSTRAINT ck_write_lock_jvm CHECK (holder_kind IS NULL OR holder_kind = 'SESSION'
                                        OR (holder_pid IS NOT NULL AND holder_start IS NOT NULL)),
    CONSTRAINT ck_write_lock_session_id CHECK (session_id IS NULL OR session_id ~ '^[a-z0-9-]{1,24}$')
);
COMMENT ON TABLE write_lock IS
    'JVM을 건너는 쓰기 배타 잠금(ADR-0175). 보유 = token이 있음. WEB·BOOTRUN은 그 JVM(holder_pid · holder_start)이, SESSION은 '
    'ops/write-session.sh 세션(session_id)이 쥔다. members는 세션 구성원(jvm:<pid>:<시작초> · exec:<pid>:<시작초>). '
    '덤프에서 데이터를 뺀다(ops/dump-db.sh) — 복원 뒤 행 없음 = 비보유';
COMMENT ON COLUMN write_lock.members IS
    '세션의 산 구성원 후보. 세션 만료 = 산 구성원 0 ∧ last_activity_at에서 TTL(60분) 경과. 합류 JVM은 하나뿐이다(합류도 CAS)';
COMMENT ON COLUMN write_lock.waiter_pid IS
    '잠금을 기다리는 쓰기 크론의 JVM. 산 대기자가 있으면 세션 밖 새 획득을 거절한다(크론이 다음 차례를 잃지 않게)';

-- ③ 보호에서 빼는 표 — 각 사유.
--    사용자 자료(웹 사용자 흐름만 쓰는 표 · 로그인 경로 포함): allowed_email(허용목록 관리) · app_user(로그인마다 갱신) ·
--    interaction_log(요청 기록) · screen · screen_condition · screen_condition_threshold · condition_group(스크린 저장 ·
--    프리셋 시더) · watchlist_item(관심종목 · 일일 갱신의 알림 판정). 잠금 기제: write_lock(획득은 정의상 비보유자의 쓰기) ·
--    guard_setting(스위치). flyway_schema_history(DDL은 트리거 대상이 아니고 이력 표는 Flyway가 쓴다).
CREATE OR REPLACE FUNCTION public.write_guard_exempt(relname text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT relname IN ('allowed_email', 'app_user', 'interaction_log',
                       'screen', 'screen_condition', 'screen_condition_threshold', 'condition_group',
                       'watchlist_item',
                       'write_lock', 'guard_setting',
                       'flyway_schema_history')
$$;

-- ④ 가드. 문장 단위라 0행 문장도 발화한다 — 시더는 같으면 문장을 안 내야 한다(ADR-0175).
--    신원: JVM ss:j:<pid>:<시작초> · 세션 ss:s:<id> · 세션에 합류한 JVM ss:s:<id>:j:<pid>:<시작초>.
--    MUST: 스키마를 한정하고 search_path를 고정한다 — pg_restore가 search_path=''로 돌 때 이름이 안 풀리면 복원이 깨진다.
CREATE OR REPLACE FUNCTION public.write_guard_check() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_db     text := current_database();
    v_app    text := coalesce(current_setting('application_name', true), '');
    v_on     boolean;
    v_prefix text;
    v_lock   public.write_lock%ROWTYPE;
BEGIN
    SELECT true, g.identity_prefix INTO v_on, v_prefix FROM public.guard_setting g WHERE g.db_name = v_db;
    IF v_on IS NULL THEN
        RETURN NULL;
    END IF;
    IF v_prefix IS NOT NULL AND left(v_app, length(v_prefix)) <> v_prefix THEN
        RETURN NULL;
    END IF;
    SELECT * INTO v_lock FROM public.write_lock l WHERE l.db_name = v_db;
    IF v_lock.token IS NOT NULL THEN
        IF v_lock.holder_kind = 'SESSION' THEN
            IF v_app = 'ss:s:' || v_lock.session_id
               OR left(v_app, length(v_lock.session_id) + 8) = 'ss:s:' || v_lock.session_id || ':j:' THEN
                RETURN NULL;
            END IF;
        ELSIF v_app = 'ss:j:' || v_lock.holder_pid || ':' || v_lock.holder_start THEN
            RETURN NULL;
        END IF;
    END IF;
    RAISE EXCEPTION USING
        ERRCODE = 'SSWG1',
        MESSAGE = format('쓰기 가드: %s %s 거절 — 신원 「%s」는 쓰기 잠금 보유자가 아니다(보유: %s)',
                         TG_OP, TG_TABLE_NAME, v_app,
                         CASE WHEN v_lock.token IS NULL THEN '없음'
                              WHEN v_lock.holder_kind = 'SESSION' THEN '세션 ' || v_lock.session_id
                              ELSE v_lock.holder_kind || ' pid ' || v_lock.holder_pid || ' · ' || coalesce(v_lock.purpose, '') END),
        HINT = 'ops/write-session.sh begin|exec로 세션 안에서 쓰거나, 보유자가 끝난 뒤 다시 한다(ADR-0175).';
END
$$;

CREATE OR REPLACE FUNCTION public.write_guard_attach(target regclass) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    EXECUTE format('CREATE OR REPLACE TRIGGER write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %s '
                   'FOR EACH STATEMENT EXECUTE FUNCTION public.write_guard_check()', target);
END
$$;

-- ⑤ 지금 있는 보호 표 전부에 단다.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT c.oid::regclass AS rel
               FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relpersistence <> 't'
                AND NOT public.write_guard_exempt(c.relname)
    LOOP
        PERFORM public.write_guard_attach(r.rel);
    END LOOP;
END
$$;

-- ⑥ 새 표는 기본 보호 — 만들어지는 순간 단다(임시 표 · 다른 스키마 · 보호 제외 표는 뺀다). CREATE TABLE IF NOT EXISTS가
--    아무것도 안 만들면 명령이 안 잡혀 다시 달지 않는다(실측).
CREATE OR REPLACE FUNCTION public.write_guard_attach_new() RETURNS event_trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT c.oid::regclass AS rel
               FROM pg_event_trigger_ddl_commands() d
               JOIN pg_class c ON c.oid = d.objid
               JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE d.classid = 'pg_class'::regclass
                AND n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relpersistence <> 't'
                AND NOT public.write_guard_exempt(c.relname)
    LOOP
        PERFORM public.write_guard_attach(r.rel);
    END LOOP;
END
$$;
DROP EVENT TRIGGER IF EXISTS write_guard_attach_new;
CREATE EVENT TRIGGER write_guard_attach_new ON ddl_command_end
    WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
    EXECUTE FUNCTION public.write_guard_attach_new();

-- ⑦ 실행 행의 주인. 스위퍼가 「같은 DB ∧ 그 프로세스가 죽었다」로만 RUNNING을 닫는다 — triggered_by로 주인을 추정하던 것을
--    갈음한다(두 bootRun이 서로의 행을 닫던 한계 · 꼬리 행). 이 칸이 빈 옛 행과 다른 DB 이름의 행(사본으로 넘어온 운영 행)은
--    예전 규칙대로다. MUST NOT: NOT NULL을 걸지 않는다 — 옛 jar의 INSERT가 이 칸을 안 넣는다.
ALTER TABLE batch_execution ADD COLUMN IF NOT EXISTS owner_pid bigint;
ALTER TABLE batch_execution ADD COLUMN IF NOT EXISTS owner_start bigint;
ALTER TABLE batch_execution ADD COLUMN IF NOT EXISTS owner_db text;
COMMENT ON COLUMN batch_execution.owner_pid IS
    '이 행을 연 JVM의 pid(ADR-0175). owner_start(프로세스 시작 epoch 초)와 함께 주인 생존 판정에 쓴다. NULL은 V66 전의 이력';
COMMENT ON COLUMN batch_execution.owner_start IS
    '이 행을 연 JVM의 프로세스 시작 epoch 초(초 절사 · Java ProcessHandle과 셸 LC_ALL=C ps -o lstart가 같은 값)';
COMMENT ON COLUMN batch_execution.owner_db IS
    '이 행을 연 DB 이름(current_database()). 다른 이름이면 사본으로 넘어온 행이라 그 pid는 이 기계의 주인이 아니다';
