-- V18: 업종코드 자릿수 제약을 3~5로 넓힌다
--
-- V17은 3자리 또는 5자리만 허용했다. 근거는 `dart-company-profile.md`의 「기업개황」 절이 든
-- 실측이었는데 **표본이 6개사였다.** 활성 유니버스 350종목에 실제로 돌린 결과
-- **4자리(KSIC 세분류)가 52종목에서 왔다** — SK하이닉스 2612, POSCO홀딩스 2411,
-- LX인터내셔널 4610 등.
--
-- 파생 키는 손대지 않는다. 4자리에서도 left(_, 3)=소분류, left(_, 2)=중분류로 옳다
-- (2612 → 261/26). V17이 잘못 잡은 것은 허용 범위뿐이다.
--
-- **제약을 없애지 않고 넓히는 이유**는 그 자리가 실제로 일을 했기 때문이다 — 없었다면
-- 52종목이 조용히 적재되고, 자릿수가 섞인다는 사실을 아무도 모른 채 넘어갔을 것이다.
-- 2자리 이하는 계속 막는다. left(_, 3)이 중분류를 소분류라 부르게 된다.

ALTER TABLE stock DROP CONSTRAINT ck_stock_industry_code;

ALTER TABLE stock ADD CONSTRAINT ck_stock_industry_code
    CHECK (industry_code IS NULL OR industry_code ~ '^[0-9]{3,5}$');
