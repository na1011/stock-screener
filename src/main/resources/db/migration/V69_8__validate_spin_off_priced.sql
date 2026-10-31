-- V61이 NOT VALID로 건 「승인된 분사는 가격 배수가 있어야 한다」를 이제 기존 행에도 건다.
-- NOT VALID였던 이유(가격 배수 없는 국내 APPLIED 2행 — 000880 · 012450)는 판정으로 값이 들어와 사라졌다(2026-10-31 운영 위반 0행).
-- 값을 한 칸도 안 바꾸고, 앞으로 그 꼴의 옛 행이 남아 있는지 카탈로그(convalidated)가 답하게 한다.
ALTER TABLE corporate_action VALIDATE CONSTRAINT ck_ca_spin_off_priced;
