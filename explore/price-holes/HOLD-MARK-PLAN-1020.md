# 보류 표지 — 기계 보류와 사람 판정을 노트가 아니라 열로 둔다 (묶음 1 · 0단계 · 2026-10-20)

## 결손 (가짜 한계 · 오늘 배포한 ADR-0181 보류 경로의 회귀)

`PriceRefutation.screen`이 APPROVED 소스 공시 행을 OBSERVED로 보류하면(`CorporateActionLedger.hold`), 그 상태를 지키는 것이 없다.

1. **수집이 보류를 되돌린다.** `record()`의 같은 일자 갈래는 `OBSERVED ∧ trusted`면 type·ratio·detection_source·**status(APPROVED)**를 덮는다
   (`CorporateActionLedger.java:313`). 수집 창은 시장의 가장 이른 봉부터라 보류 행의 사건을 매일 다시 받는다 → 14:00 APPROVED → 14:16 재구축이
   다시 보류 → 노트 표지가 덧붙고 `heldSince`가 매일 다시 알린다.
2. **사람의 APPROVED가 서지 않는다.** `screen`의 대상은 `status='APPROVED' ∧ SOURCE_DISCLOSURE` 전부라 사람이 승인한 행도 다음 재구축이 다시
   보류한다. 노트로는 못 가린다 — `judge()`가 `note = coalesce(?, note)`로 기계 표지를 덮고, 분사 행은 `writeDistribution`이 매 수집
   `note LIKE '분사 · 자식 %'`를 `describe()`로 갈아 쓴다.

**실측(운영 SELECT · 2026-10-20 14:33)**: 368 RJF `updated_at 2026-10-20 14:16:08.978` · 노트 표지 「가격 반증 — 」 2회(어제 1회).
348 CORZ `updated_at 2026-10-20 14:16:08.542` · 표지 1회(분사 노트가 수집마다 `describe()`로 다시 쓰여 이전 표지가 지워진 뒤 다시 붙음).
batch 865 corporate-action 14:00:01–14:16:04 · 867 adjustment-rebuild 14:16:08–14:16:09. 둘 다 오늘 되돌려졌다가 다시 보류됐다.

## 덮기 조건을 「휴리스틱 후보만」으로 좁히면 안 되는 이유 (반증 제안을 다시 쟀다)

`record()`의 OBSERVED 덮기는 휴리스틱 후보 말고도 정당한 갈래를 하나 더 갖는다: **미해석 분사**
(`CorporateAction.unresolvedSpinOff` — `SOURCE_DISCLOSURE` · `UNKNOWN` · OBSERVED). javadoc: 「다음 수집이 해석하면 `spinOff`로 같은 날 행을
덮고」(`CorporateAction.java:111-116` · `SpinOffValuer` 「해석하지 못하면」). `isUnreviewedCandidate()`(HEURISTIC만)로 좁히면 이 승격이 끊겨
자식 첫 봉이 하루 늦게 온 분사가 영영 사람 판정으로 간다. 운영 지금: OBSERVED·SOURCE_DISCLOSURE 3행 = 보류 2(348 · 368) + 미해석 분사 1(365 WOLF).

## 계획

마이그레이션 `V70__corporate_action_verdict_marks.sql` — 열 둘(DDL만 · 데이터 갱신 0):

- `held_at timestamptz` — 기계가 가격 반증으로 보류한 시각. `hold()`가 `status = OBSERVED`와 함께 `now()`로 적는다.
- `judged_at timestamptz` — 사람이 상태를 정한 시각. `judge()`가 `judgement.status() != null`일 때만 `now()`로 적는다(값만 고치는 판정은 안 적는다).

코드:

1. `record()` 같은 일자 갈래: 덮기 조건에 `held_at IS NULL`을 더한다(`ExistingRow`에 열을 싣는다). 보류 행이면 IGNORED — `writeDistribution`도 안 탄다
   (지금 순서가 이미 IGNORED 뒤에 있다). 미해석 분사·휴리스틱 후보의 덮기는 그대로다.
2. `PriceRefutation.screen`의 pending 질의에 `judged_at IS NULL`을 더한다 — 사람이 APPROVED한 행은 가격 반증을 다시 안 탄다.
3. `heldSince`: `held_at >= since`로 판다(노트 LIKE 대신). 노트 표지는 사람이 읽을 근거로 그대로 남긴다(`hold`가 한 번만 붙인다 — 다시 보류될 길이 없으므로).
4. `judge()`가 보류 행(OBSERVED · held_at 있음)을 APPROVED로 올리면 `judged_at`이 서서 다음 재구축이 적용한다. `held_at`은 이력으로 남긴다.

`relocateCandidate`(근접 일자 덮기)는 이미 `isUnreviewedCandidate`(HEURISTIC)만 받는다 — 보류 행은 SOURCE_DISCLOSURE라 원리적으로 안 닿는다. `annotate`는 `source_form`만 쓴다.

### 값 영향

- **지표 값 변화 0.** 보류 행은 지금도 재구축에서 매번 빠진다(14:00 APPROVED → 14:16 같은 트랜잭션에서 보류 → 적용 목록 밖). 바뀌는 것은 원장 행의
  `updated_at`·노트 반복과 매일 알림이 멈추는 것, 그리고 사람 APPROVED가 서는 것.
- 배포 전에 보류된 행(held_at NULL)은 다음 14:00에 한 번 더 APPROVED로 덮이고 14:16에 `held_at`과 함께 보류된다 — 하루에 수렴한다. 백필 UPDATE를
  마이그레이션에 안 넣는 이유: 운영 해당 행은 348·368 둘이고 둘 다 오늘 15:40 뒤 사람 판정(REJECTED·UNSUPPORTED)으로 이 고리를 떠난다.
- 사정거리: 보류 행은 데이터가 바뀌어도(효력일 앞 채움 봉이 진짜 봉으로 바뀌는 등) 기계가 다시 판정하지 않는다 — 사람 판정까지 OBSERVED다.
  ADR-0181의 뜻(보류 = 사람에게 돌림)과 같다. 지금은 매일 재판정이 우연히 돌던 것이다.

### 소비자 (grep 전수 · `held_at`·`judged_at`은 새 열이라 읽는 곳 = 이 계획이 만드는 곳뿐)

- `status` 뜻은 안 바뀐다 — OBSERVED를 읽는 20파일(ADR-0055 억제 `findUnadjusted` · `JdbcStoredShareCounts` · `ShareBasisDeriver` · 판정 대기 알림 등)은 그대로.
- 노트 표지를 읽는 곳: `heldSince` 하나(→ 3). `PriceRefutationTest:54`가 노트 머리말을 단언한다(유지).
- ADR-0090 펜스(`APPLIED ∧ note IS NULL`): 이 계획은 APPLIED 행의 노트를 안 건드린다.
- `SELECT *`·`c.*`로 corporate_action을 매핑하는 곳: 0(`CorporateActionLedger`·`ShareBasisDeriver` grep) — 열을 더해도 기존 질의 모양은 그대로다.

### 드라이런 (가장 싼 재료 — 단위·통합 시험만)

`CorporateActionLedgerTest`·`PriceRefutationTest`(빈 시험 DB · 꼴마다 최소 행):

1. 보류 행에 같은 일자 trusted 공시를 다시 `record` → IGNORED · status OBSERVED · 노트 표지 1회 · 다음 `screen`에 알림 0(heldSince 빈 목록).
2. 사람 APPROVED(`judge` status=APPROVED) 뒤 `screen` → 보류 안 함 · 적용 목록에 든다.
3. 대조군(덮기가 남아야 하는 것): 미해석 분사(UNKNOWN · SOURCE_DISCLOSURE · OBSERVED) 뒤 `spinOff` 같은 일자 → UPGRADED · APPROVED. 휴리스틱 후보 → UPGRADED.
4. 값만 고치는 `judge`(status null)는 `judged_at`을 안 세운다 → 보류 행은 그대로 OBSERVED.
5. 대조군은 원래 결함으로 붉혀 본다: 1·2를 수정 전 코드에서 돌려 실패를 확인한다.

운영 SELECT 재현은 필요 없다 — 지금 운영에서 걸리는 행이 위 실측 셋뿐이다. 작은 DB·사본은 안 쓴다.

## 반증 뒤 고친 것 (두 렌즈 모두 「고쳐서 선다」 · 2026-10-20)

- 번호 `V70` → **`V69_3`**: 시험 `db/testmigration/V70__share_class_axis.sql`(관문 마이그레이션)과 충돌한다. 운영 적용 판 69.2.
- 덮기 조건 `held_at IS NULL` → **`held_at IS NULL ∨ 들어온 type·ratio·price_ratio·listing_date가 저장값과 다름`**(그때 `held_at`을 비워 다시 판정).
  같은 값이면 IGNORED라 매일 고리는 멈추고, 소스 정정으로 저절로 회복되는 길은 남는다. 조건은 UPDATE의 WHERE에 둔다 — `ExistingRow` 매퍼(두 질의
  공유)를 안 건드린다.
- 「지표 값 변화 0」 → 정상 사슬에서는 0. 다만 수정 전에는 14:00 수집 ~ 14:16 재구축 사이에 사슬이 끊기면 반박된 비율이 APPROVED로 하룻밤을 넘겨
  ADR-0055 억제가 풀리고 `findApplicable` 소비자(`IndicatorDataReader.readShareAdjustments` · `GovernanceFactDeriver`)가 그 비율을 읽었다 — 이 수정이
  그 창을 닫는다(끊김 횟수는 재보지 않았다).
- `judged_at`의 뜻 = **V69_3 뒤 `judge()`가 상태를 정한 시각**. 그 앞의 사람 판정(미국 APPROVED 분사 7 · REJECTED 3 · UNSUPPORTED 2 · 노트만 단 확인
  196 QXO · 265 RGC)은 NULL이다 — 지금 소비자(`screen`은 APPROVED만 · 미국 APPROVED 0행)에는 영향 0. 주식수 렌즈 2단계(APPLIED 재확인)가 「사람이 본 행」을
  가를 때 이 공백을 그 계획에서 다룬다.
- 시험: 보류 상태는 SQL이 아니라 봉 + `rebuild`로 만든다 · 붉는 단언은 IGNORED·상태·표지 1회 · `heldSince` 양성은 since = DB `now()`.
- 백필 UPDATE는 넣지 않는다(운영 행 수정은 사용자 승인 사항) — 배포 전 보류 행은 한 사슬 안에서 `held_at`과 함께 다시 보류된다.
- 낡은 문장 고침: `judge()` 오류 문구 · `batch-load/SKILL.md` 「OBSERVED는 수집이 만든다」.

## 드라이런 결과

`PriceRefutationTest` 새 시험 5 — 수정 전 코드에서 결함 둘이 원래 결함으로 붉었다(「같은 값 재수집」 UPGRADED · 「사람 APPROVED」 OBSERVED), 대조군 셋
(소스 정정 재판정 · 값만 고친 판정 · `heldSince` 양성)은 초록. 수정 뒤 `com.stockscreener.pipeline.collector.*` 전부 초록(미해석 분사 승격 ·
annotate 기존 시험 포함).
