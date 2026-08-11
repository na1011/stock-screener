-- V20: 상대 비교 지표의 모집단을 카탈로그에 적는다
--
-- 백분위 하나뿐일 때는 모집단이 상수여서 응답에 문자열 하나로 실렸다. 업종 상대값이
-- 생기면서 **모집단이 조건마다 달라졌고**, 그 상수는 거짓말이 된다 — 사용자가 보는
-- "상위 50%"의 모집단이 조건마다 다른데 화면 어디에도 흔적이 없다(ADR-0044).
--
-- 화면이 지표 코드로 분기해 판단하게 두지 않으려고 카탈로그에 싣는다. 코드로 분기하면
-- 지표가 추가될 때마다 화면을 고쳐야 하고, 그것이 백엔드에서 없앤 하드코딩을 프론트로
-- 옮기는 것이다(`.claude/rules/frontend.md`).
--
-- NULL은 "상대 비교 지표가 아니다"라는 뜻이다. PER·SMA_200처럼 값 자체가 뜻을 갖는
-- 지표가 대부분이며, 그것들에는 모집단이라는 개념이 없다.

ALTER TABLE indicator ADD COLUMN population varchar(16);

ALTER TABLE indicator ADD CONSTRAINT ck_indicator_population
    CHECK (population IS NULL OR population IN ('UNIVERSE', 'SECTOR'));

COMMENT ON COLUMN indicator.population IS
    '상대 비교의 모집단. UNIVERSE=지수 안(ADR-0036) · SECTOR=같은 업종 안(ADR-0044) · NULL=상대 지표가 아님.';
