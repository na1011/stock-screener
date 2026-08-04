-- 테스트 전용. QueryDSL 런타임 프로브가 쓰는 테이블이다.
--
-- 도메인 테이블이 아니므로 운영 스키마(db/migration)에 넣지 않는다.
-- 버전을 900대로 둔 이유는 운영 마이그레이션 번호와 충돌하지 않게 하기 위해서다.
--
-- MUST NOT: 이 파일과 QuerydslProbeEntity를 "쓰이지 않는 코드"로 보고 지우지 않는다.
-- QueryDSL mainline과 Hibernate 7의 비호환은 컴파일·기동을 전부 통과하고 쿼리 실행
-- 시점에만 드러나므로, 이 프로브가 그 회귀를 잡는 유일한 수단이다. ADR-0001 참조.
CREATE TABLE querydsl_probe (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label varchar(255) NOT NULL
);
