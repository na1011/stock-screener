---
id: ADR-0168
status: 채택됨
date: 2026-10-05
applies_to:
  - "src/main/java/**/pipeline/datasource/CorporateAction.java"
  - "src/main/java/**/pipeline/datasource/Distribution.java"
  - "src/main/java/**/pipeline/collector/SpinOffValuer.java"
  - "src/main/java/**/pipeline/collector/CorporateActionLedger.java"
  - "src/main/java/**/pipeline/collector/AdjustmentRecalculator.java"
  - "src/main/java/**/pipeline/datasource/us/AlpacaCorporateActionAdapter.java"
  - "src/main/resources/db/migration/V60__corporate_action_price_ratio.sql"
  - "src/main/resources/db/migration/V61__corporate_action_spin_off_price.sql"
  - "src/main/resources/db/migration/V62__distribution_symbol_width.sql"
---

# ADR-0168. 분사는 원장 행이 가격 배수를 따로 들고, 그 값은 같은 날 두 종가로 정한다

## 결정

- **원장 행 하나가 배수 둘을 든다.**
  - `ratio`는 **주식수 배수**(1주가 몇 주가 되었는가)다.
  - `price_ratio`는 **가격 배수**다 — 재구축이 사건 전 봉에 `1/price_ratio`를 곱한다(V60).
  - 분할·병합·무상증자는 둘이 같아 `price_ratio`가 NULL이다. 갈리는 종류는 `SPIN_OFF`뿐이다(V61 제약).
- **`SPIN_OFF` = 「주식이 s배가 되고 자식 증권이 분배된다」.** `ratio` = s이고 `price_ratio` = s / v다.
  - 미국 단순 분사는 s = 1이다.
  - 같은 날(7일 안) 분할·병합과 합치면 s가 그 비율이다 — HON 2026-06-29는 병합 1:2 + HONA라 s = 0.5다.
  - 국내 인적분할은 s가 감자 후 비율이다. 같은 날 액면분할과 겹치면 1보다 크다(SK텔레콤 2021-11-29는 3.04).
- **v = 같은 날 종가로 잰 존속 쪽 가치 비율**(`Distribution`).
  - 미국: `s × 모체 종가 / (s × 모체 종가 + Σ rate × 자식 종가)`. 자식이 ex일에 안 거래되면 ex 뒤 5거래일 안의 자식 첫 날을 쓴다(분배 워런트).
  - 국내: 재상장일 존속·신설 보통주 시가총액의 비율.
  - 원자료는 `corporate_action_distribution`에 원장 행과 함께 둔다.
- **미국 `spin_offs`를 원장에 담는다**(ADR-0090의 「안 담고 센다」를 분사에 한해 바꾼다).
  - 어댑터는 원자료만 내고, 수집기(`SpinOffValuer`)가 가격 포트로 자식 봉을, 저장 시세로 모체 봉을 읽어 v를 정한다.
  - 못 정하면 종류 미상 `OBSERVED`로 남긴다 — 억제(ADR-0055)와 「판정 대기」 알림이 걸린다.
  - 사람은 발행사 배분 비율(Form 8937)로 판정하거나 `UNSUPPORTED`로 보낸다.
- **기업행위 수집의 기본 대상을 보존 창 편입으로 넓힌다**(재구축 ADR-0167과 같은 모집단).
- **설정 뒤에 두지 않는다.**
  - 효과가 `price_ratio`가 있는 원장 행의 종목에만 미치고, 새 분할 하나가 들어올 때와 같은 경로(14:00 사슬: 수집 → 재구축 → 무효화 → 사슬 안 화해 ①②)로 한 평일에 수렴한다.
  - 수집이 창 편입 전원을 매 실행 전 창으로 읽으므로, 배포 뒤 첫 사슬이 모든 분사를 한 번에 담는다.

## 규칙

- MUST: 가격을 다시 짓는 자리는 `CorporateActionLedger.Entry#priceMultiple()`만 읽는다.
  - 주식수를 읽는 자리(`sharesOn` · `corporateActionMultiple` 소비자 다섯 · `GovernanceFactDeriver` · `ShareBasisDeriver` · `JdbcStoredShareCounts`)는 `ratio`만 읽는다.
  - 섞으면 분사 모체의 주식수가 가격 배수만큼 늘거나, 가격에 주식수 배수가 실린다(ADR-0117 §규칙의 그 결함 — 한화 2026-08-25 사건 전 봉 × 1.3221).
- MUST: 원장의 값을 바꾸는 쓰기 경로 셋(`record` 덮기 · `relocateCandidate` · `judge`)이 `price_ratio`와 분배 원자료를 같은 트랜잭션에서 쓴다.
  - 하나라도 빠지면 해석이 끝난 분사가 가격 배수 없는 승인 행이 되려다 `ck_ca_spin_off_priced`에 막혀 그 종목이 롤백되거나(미국), 원자료 없는 행이 된다.
- MUST NOT: 미해석 분사를 `annotate`로 보내지 않는다. 이웃 행에 낱말만 붙이고 끝나 그 분사가 무보정·무억제로 남는다. `record`가 자기 행을 만들거나 NEAR_DUPLICATE로 사람을 부른다.
- MUST NOT: 자식의 값을 근접중복 창(5거래일) 밖의 봉으로 갈음하지 않는다. 그 밖이면 소스 날짜를 의심할 사건이다(MRP — LEN 2025-01-21 · 첫 봉 02-07 · 그만한 하루 낙폭이 어느 날에도 없다).
- MUST NOT: 자식 시세의 일시 장애(TRANSIENT · RATE_LIMITED · AUTH)를 미해석으로 접지 않는다. 접으면 거짓 판정 대기가 가고 그 종목이 억제된다 — 종목 실패로 올려 다음 실행이 다시 한다.
- MUST NOT: 장부 비율(v = s)로 국내 가격 배수를 대신하지 않는다. 7건 모두 같은 날 시가총액의 v와 달랐다(솔브레인홀딩스 s 0.55 대 v 0.22).
- MUST: 분사를 사람이 승인하면 `price_ratio`와 근거 note를 함께 준다(`judge`가 거절한다). ADR-0117 premise가 note를 센다.
  - 자동 행은 `Distribution.describe()`가 note를 쓴다.
- MUST: 판정으로 `UNSUPPORTED` 종목 수가 바뀌면 ADR-0071 premise의 기준 수를 함께 고친다(2026-10-05 국내 인적분할 5행을 `SPIN_OFF`로 · DNTH · CNH를 `UNSUPPORTED`로 바꿔 7 → 4). 안 고치면 그 premise가 매 세션 「수익률 제외 목록이 낡았다」를 낸다.

## 검증

```bash
./gradlew test --tests "*CorporateActionLedgerTest" --tests "*CorporateActionCollectorTest" \
  --tests "*AlpacaCorporateActionAdapterTest" --tests "*AdjustmentRecalculatorTest" --tests "*CorporateActionJobTest"
```

대조군(2026-10-05): 아래 다섯을 각각 되돌리면 해당 시험이 붉었다.
- 재구축이 `ratio`를 읽게 한다.
- 덮기 UPDATE에서 `price_ratio`를 뺀다.
- 옮기기에서 원자료 쓰기를 뺀다.
- 5거래일 창 검사를 끈다.
- 합치기를 끈다 · 수집 대상을 지금 편입으로 되돌린다.

```premise 승인된 분사가 모두 가격 배수를 든다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) FILTER (WHERE type = 'SPIN_OFF') = 0 THEN '잴 것이 없었다 — 원장에 분사가 없다'
  WHEN count(*) FILTER (WHERE type = 'SPIN_OFF' AND status IN ('APPROVED','APPLIED') AND price_ratio IS NULL) = 0
    THEN 'OK 승인 분사 ' || count(*) FILTER (WHERE type = 'SPIN_OFF' AND status IN ('APPROVED','APPLIED')) || '행 전부 가격 배수가 있다'
  ELSE count(*) FILTER (WHERE type = 'SPIN_OFF' AND status IN ('APPROVED','APPLIED') AND price_ratio IS NULL)
    || '행의 승인 분사가 가격 배수 없이 주식수 배수를 가격에 싣고 있다 — 국내 옛 2행(000880 · 012450)이면 판정 API로 s/v를 넣는다(V61은 그 둘 때문에 NOT VALID다)'
  END FROM corporate_action"
```

## 배경

분사가 원장에서 두 방향으로 틀려 있었다(2026-10-05 실측 · 정본은 탐색 브랜치 `explore/spin-off/PLAN.md`).

- **미국** — `spin_offs`가 원장에 0행이라 모체의 분사 낙폭이 수정주가에 그대로 남았다.
  - CTVA 10-01 `RETURN_52W` +14.58 → −81.41 · `PCT_FROM_52W_HIGH` −86.18.
  - DD 2025-11-03(−57.5%)이 1년 뒤에도 −44.72.
  - 읽기 창 53건이고 그중 보존 창이 13건이다. 원장에 행이 없으니 억제(ADR-0055)도 안 걸렸다.
- **HON 2026-06-29** — 병합 1:2가 `REJECTED`였다.
  - 같은 날 분사가 가격을 상쇄했는데, 2026-08-31 판정이 「가격이 비율을 안 받친다」로 읽었다.
  - 그 결과 06-29~07-22의 17봉 동안 시총·PER이 약 2배였다.
- **국내** — 인적분할 `APPLIED` 2행이 주식수 배수를 가격계수로 쓰고 있었다. `UNSUPPORTED` 5행은 억제로 지표를 지우고 있었다 — 삼성바이오로직스의 10-01 지표 행은 92개로, KOSPI 중앙값 139보다 적다.

계획은 반증 두 렌즈 × 두 바퀴를 받았다.
- 1바퀴가 잡은 것: 같은 날 두 사건을 한 행으로 못 만드는 것 · 미해석이 다음 실행에 v 없는 승인으로 덮이는 것 · 과거 편입 모체가 영영 안 고쳐지는 것 · 관례 선택이 반증 불가한 형태인 것.
- 2바퀴가 잡은 것: `relocateCandidate`와 분배 원자료의 쓰기 누락.

**관례는 대조 전에 커밋한 규칙으로 골랐다**(커밋 2b276862 → 계산).
- 기준선: 사건 없는 날의 사건일 수익률 z가 |z| > 3인 비율 1.19%.
- 두 관례가 다 계산되는 37건에서 이상 사건은 같은 날 종가 관례(f_b) 4건, 소스 관례(f_c · 분사 전날 when-issued 가격 — Alpaca `adjustment=spin-off`를 DD·HON×2·FDX·SPGI에서 정확히 재현한다) 7건이다. 입력 하나를 고치면 6건이다.
- 둘 다 기준선 구간 밖이라, 규칙대로 더 가까운 f_b를 골랐다. McNemar p = 0.125로 유의하지 않지만, 동률 규칙(국내에는 when-issued가 없어 f_c가 원리적으로 안 선다)으로도 f_b다.

## 버린 대안

- **분사를 `UNSUPPORTED`로 적어 억제만 건다**: 소스가 v를 낼 재료(자식 심볼 · 배정 비율 · 자식 봉)를 다 준다 — 가짜 한계다. 억제는 그 종목의 장기 지표를 수년 지운다(LEN 34코드 · 1,261봉 창은 약 2030년까지).
- **소스 관례 f_c(분사 전날 when-issued)**: 37건 이상 7(위). 국내에 when-issued가 없어 두 시장이 다른 관례가 된다. 소스 계수가 자기 rate와 어긋나는 사건도 있다 — FTV 0.6697은 rate 1/3과 RAL.WI 52.98로 0.7534여야 한다.
- **f_a = 1 − rate·자식(ex)/모체(cum)**: 자식이 모체보다 클 때 사건일 수정 수익률을 키운다(CTVA +33.9% 대 f_b +4.1%).
- **Alpaca `adjustment=spin-off` 시세를 그대로 쓴다**: 벤더가 계산한 값에 기대는 자리이고(spec §7), CTVA처럼 소스가 아직 안 붙인 사건(10-01 기준 1.0000)과 틀린 사건(FTV)이 있다. 드라이런의 대조군으로만 쓴다.
- **국내 장부 대리(v = s → 가격 배수 1 · ADR-0117 §규칙의 이론 주가)**: 7건 모두 시장 v와 달랐다.
- **사건마다 자식 하나씩 행을 둔다**: `uq_ca`(종목, 효력일) 때문에 같은 날 둘째부터 버려진다(OPEN 워런트 세 종).
- **설정 뒤에 두고 관문에서 켠다**: 그 규칙의 사유(옛 규칙과 새 규칙의 값이 섞여 전체 백필로만 풀리는 상태)가 여기 안 선다 — 위 §결정의 수렴 경로다. 반증 두 렌즈가 지금 편입에는 「선다」, 과거 편입에는 「깨진다」로 답했고, 수집 대상을 넓혀 뒤의 것을 닫았다.

## 결과

- **드라이런**(2026-10-05 · 사본 a = 배포 코드 · b = 이 결정 · 사전 규칙과 결과 표는 탐색 브랜치 `explore/spin-off/PLAN.md` §3)
  - 원장
    - 새 행 49: 분사 승인 43 · 미해석 6.
    - 수정계수가 바뀐 39종목이 전부 모체다. 모체 밖 원본층 변화와 국내 변화는 0이다.
    - ex 뒤 계수는 불변이다.
    - 가격 배수가 독립 계산과 43/43 일치했다.
    - 둘째 수집의 변경은 0이다.
  - 값
    - CTVA 10-01 `RETURN_52W` −81.41 → +19.52.
    - 사건일 수정 수익률: DD +1.89% · FDX +0.37% · HON(판정) −3.54% · 000880(판정) +20.91%.
    - 207940(판정)의 최신일 지표 92 → 147행.
  - 비용: 수집 12.5 → 16분 · 화해 411 → 424초.
  - 드라이런이 찾은 결함: 다자식 분배의 CUSIP이 `varchar(16)`을 넘었다 → V62.
  - 미해석 6(CNH · DNTH · FTI · LBTYB · LEN · SYRE)은 판정 전에 화해가 돌면 지표 행을 잃는다(LEN −11,761 등 · 합 49,187).

- **분사일 ~ 첫 분사 뒤 공시 구간의 재무 × 시총 지표는 이 결정 밖이다.**
  - CTVA `PER_PCTL` 0.048 · `PBR_PCTL` 0.000이 그 자리다.
  - 사용자 결정(2026-10-05): *"`claude.md`의 판단기준에 정의된 진짜한계(원장이 뒤죽박죽 등)는 억지로 계산 표시하여 정합성을 떨어뜨리지 말고 미표시하는게 나음. 판단기준에 근거하여 너 스스로 판단하고 기록해둬."*
  - 경계가 TTM 구성 분기마다 갈려(재게재하지 않는 회사가 있다 — CMCSA) 별도 바퀴로 설계한다.
  - 중단영업이 PER에 섞이는 것은 분사만이 아니라 두 시장의 지표 정의 문제라 관문 재고에 있다(`docs/reload-gate.md`).
- **운영 적용(2026-10-05)** — 덤프 → 배포 15:43(V60~V62) → 수집 → 판정 16행 → 재구축 → 화해(16:11 끝).
  - 사용자 결정: 배포 시점은 「오늘 15:40 뒤 (Recommended)」 · 판정 첫 묶음 8행(HON · 국내 7)과 둘째 묶음 8행(FTV · DELL · FTI · LEN · LBTYB · SYRE는 가격 배수 · DNTH · CNH는 `UNSUPPORTED`)은 각각 「8행 승인 (Recommended)」. 행마다의 값과 근거는 탐색 브랜치 `explore/spin-off/JUDGE-PAYLOADS.md`에 있다.
  - 수집(1,683종목 · 실패 0 · 16분): 새 행 50 = 분사 승인 44(드라이런 43 + V62 뒤 OPEN) · 미해석 6. 50행의 가격 배수가 드라이런 사본과 바이트까지 같다. 기존 행 변경 0.
  - 판정 뒤: 승인 분사 58행 전부 가격 배수가 있다(위 premise) · 판정 대기 0 · `UNSUPPORTED` 4종목(국내 020560 · 007340 · 미국 DNTH · CNH — ADR-0071 premise 기준 수).
  - 재구축: 계수가 바뀐 51종목 = 오늘 원장 행이 바뀐 54종목 − DNTH · CNH(미표시라 계수 없음) − SYRE(가격 배수 1). 그 밖 0.
  - 화해: 원본층 쓴 1,448,247 · 지운 16,990(= 드라이런의 DNTH 9,560 + CNH 7,430 — 미표시) · 파생층 272거래일 쓴 550,323. 미해석 넷은 화해 전에 판정돼 지표를 잃지 않았다.
  - 값이 드라이런과 같다: CTVA 10-01 `RETURN_52W` +19.52 · DD +32.58 · 사건일 HON −3.54% · 000880 +20.91% · DD +1.89% · FDX +0.37% · 207940 +7.85%(최신일 지표 92 → 147행) · HON 06-29 `MARKET_CAP` 144.3 → 72.2B.
  - 프리셋 결과(3기준일 · 저장 화면 포함): 포함 뒤집힘 0 · 새로 듦 6 · 빠짐 1 · 점수만 14. 전부 모체이거나 그 순위가 민 종목이다 — SCHW 07-01 `PER_PCTL` 0.3011(고친 HON PER 17.13이 그 아래로 들어옴) · DGX 10-02 MOMENTUM 탈락(`EXCESS_RETURN_52W_PCTL`).
  - 다음 수집이 판정을 안 덮는다 — 같은 날짜에 사람이 판정한 행이 있으면 `record`가 IGNORED로 돌려준다(코드로 읽음 · 드라이런 둘째 수집이 HON `REJECTED` · FTV/DELL `APPLIED`에서 그 갈래를 탔다).
- **콜이 는다**: 기업행위 수집 대상이 미국 +208 · KOSPI +36 · KOSDAQ +89이고, 분사마다 자식 봉 콜이 붙는다.
- **되돌리려면**:
  - 어댑터의 `spin_offs` 수확과 `SpinOffValuer`를 걷고, 수집 대상을 `activeUniverseMemberIds()`로 되돌린다.
  - V60·V61은 그대로 둬도 무해하다(NULL이면 주식수 배수로 짓는다).
  - 다만 되돌리기 전에 원장의 `SPIN_OFF` 행을 본다 — 남겨 두면 그 가격 배수로 계속 짓는다.
