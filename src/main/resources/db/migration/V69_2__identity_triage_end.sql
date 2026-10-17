-- 끝 판정(ADR-0180 · 탐색 계획 US-PAST-LISTING-PLAN-1017.md 「끝 판정」) — 신원 판정이 피인수 · 가치 없음 제거로 사라진 실체의 열린
-- 행을 닫고, 그 판정과 근거를 다른 판정처럼 identity_triage에 남긴다. 값을 바꾸지 않는 제약 넓히기다: 기존 행은 전부 옛 다섯 값이다.
ALTER TABLE identity_triage DROP CONSTRAINT ck_identity_triage_verdict;
ALTER TABLE identity_triage ADD CONSTRAINT ck_identity_triage_verdict
    CHECK (verdict IN ('RUN', 'RENAME', 'SPLIT', 'REUSE', 'AMBIGUOUS', 'END'));
