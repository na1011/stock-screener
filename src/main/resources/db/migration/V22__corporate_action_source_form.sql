-- V22: 소스가 그 사건을 뭐라 부르는지 원장에 적는다 (ADR-0050)
--
-- 지금 DART 어댑터는 「무상감자」와 처음 보는 발행감소 형태를 log.warn으로만 흘린다. 그래서
-- 아시아나항공 2021-01-15은 원장에 휴리스틱이 올린 REVERSE_SPLIT 1/3만 남고, **그것이 감자라는
-- 사실은 어디에도 없다.** 그대로 승인하면 ADR-0049가 범위 밖으로 둔 사건에 계수가 들어간다.
--
-- MUST NOT: note에 합치지 않는다. note는 사람이 판정하며 쓰는 칸이고, 한 칸에 두면 한쪽이 다른
-- 쪽을 덮는다 — 소스가 준 낱말은 사실이고 사람이 쓴 말은 판단이라 덮이는 순간 구분이 사라진다.
--
-- 이 컬럼은 사람이 못 고친다(ADR-0050). 소스가 준 사실이므로 detection_source·source_date와
-- 같은 자리에 있다.

ALTER TABLE corporate_action ADD COLUMN source_form varchar(32);

COMMENT ON COLUMN corporate_action.source_form IS
    '소스가 그 사건을 부르는 낱말(DART isu_dcrs_stle: 주식분할·무상증자·무상감자 등). '
    'type이 UNKNOWN인데 이 값이 있으면 "우리가 이름은 아는데 계수를 못 낸다"는 뜻이다.';

-- V21의 status COMMENT를 고쳐 둔다. UNSUPPORTED를 "원리적으로 못 고침(인적분할 등)"이라고만
-- 적어 뒀는데, 실제로는 **범위 밖이라 안 고치는 것**(무상감자)도 같은 자리로 간다. 적용된
-- 마이그레이션은 못 고치므로 여기서 덮는다.
COMMENT ON COLUMN corporate_action.status IS
    'OBSERVED=후보 · APPROVED=적용 대상 · APPLIED=계수에 반영됨 · REJECTED=오탐(그런 일이 없었다) · '
    'UNSUPPORTED=사건은 실재하나 우리가 계수를 못 낸다(인적분할, 그리고 범위 밖 형태). '
    'APPLIED와 OBSERVED는 사람이 찍을 수 없다 — 전자는 재구축만, 후자는 수집만이 만든다(ADR-0050).';
