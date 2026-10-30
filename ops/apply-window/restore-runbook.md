# 과거 유니버스 재복원 절차서 (2026-09-19 작성 · 실행은 2026-09-21 월 14:00 이후)

기준선 TSV 둘은 저장소 밖 `~/stock-screener-apply-window/restore-20260921/`(13.7MB · 버전 관리 대상 아님)에 있었고, 판정이 끝나 2026-10-30에 지웠다 — 이 절차를 다시 돌리면 기준선부터 새로 찍는다.

지배 결정: **ADR-0135**. 그 §규칙이 순서를 못박는다 — **재적재 → `corporate-action` 사슬 → 복원 → 파생층 백필**.

## 왜 지금 도는가 (2026-09-19 16:00 실측)

| 유니버스 | 복원 행 | 마지막 복원 | 복원 뒤 재무 | 복원 뒤 원장 | 복원 구간 |
|---|---|---|---|---|---|
| KOSDAQ_TOP150 | 422 | 2026-09-14 14:39 | 1,803 | 28 | 2025-08-27 ~ 2026-08-05 |
| KOSPI_TOP200 | 301 | 2026-09-14 14:39 | 880 | 6 | 2025-08-27 ~ 2026-08-05 |
| US_TOP1000 | 1,711 | 2026-09-17 11:31 | 2,570 | 8 | 2025-09-08 ~ 2026-08-31 |

**MUST: 이 표를 「순위가 바뀌었다」로 읽지 마라 — 대리 신호다.** 펜스가 세는 것은 「복원 뒤에 재무 행이
들어왔다」이지 그 행이 순위 재료를 움직였는가가 아니다.

- **미국 2,570은 2026-09-18 Q4V 이른 판 그 자체다.** 그런데 그날 판정 ②가 `RankShareCountSnapshotTest`로
  **격자 날짜마다 `activeByMarket`을 찍어 S0(플래그 끈 대조) 대비 0줄**을 확인했다. 그 격자가 곧 복원 격자다.
  → **Q4V는 미국 복원 입력을 안 바꿨다.** 미국에 남은 후보는 **그 뒤 들어온 원장 8건**뿐이고, 그것은
  ②가 원리적으로 못 가른다(운영·S0가 같은 원장을 썼다).
- **국내 2,683행·34건은 2026-09-16 재적재가 09-14 복원 뒤에 온 것**이고 **아무도 순위 영향을 안 쟀다.**
  이쪽이 이번 재복원의 주된 대상이다.
- 선례(ADR-0135 §규칙): 2026-09-14 재복원에서 **1,740행 중 1,119행(64%)이 달라졌다.**

## 기준선 (이 폴더 · 2026-09-19 16:08~16:09)

- `restored-before.tsv` — 지금 `origin='RESTORED'` 전량 **2,434행** (`universe_code · ticker · effective_from · effective_to`).
  md5 `f0c6f92c34c021f5f1f2aac583dfd149`. **판정의 1차 재료다.**
- `rank-before.tsv` — `RankShareCountSnapshotTest` 출력. 격자 **55일**(2025-09-12~2026-09-17) × 미국 종목.
  **왜 인가**: 복원 결과가 달라졌을 때 「입력이 바뀌어서」와 「격자가 밀려서」를 가른다.

## MUST: 두 원인을 섞지 마라

격자는 `indicator_trade_date`에서 뽑으므로 **거래일이 늘면 밀린다**(월요일 13:30이 09-18 봉을 넣는다).
그래서 월요일 복원 결과는 **두 원인이 겹친다** — ① 순위 입력 변화 ② 격자 이동.

**가르는 법**: 복원 전에 `rank-after.tsv`를 같은 방법으로 찍고, **양쪽에 다 있는 as_of 날짜만** 비교한다.
공통 날짜에서 입력이 같으면 멤버십 차이는 **격자 이동 몫**이다.

## 순서

**다시 쓸 때 — 복원(1단계 되돌리기) 전에** `ops/status.sh`의 「가드」 줄을 적고, 복원 뒤 같은 줄과 대조한다 — `-c` 전체 복원은
`guard_setting`을 덤프 시점 값으로 되돌린다. 다르면 사용자 승인으로 맞춘다(운영 스위치 · 원시 SQL). 복원 전 잠금 보유자도 본다
(`ops/write-session.sh status`) — 남이 쥐었으면 멈춘다.
**잠금 jar가 배포된 DB면** 4·5단계의 bootRun을(가드를 켰으면 1단계 되돌리기의 psql·`pg_restore`까지) `ops/write-session.sh begin <id>` 뒤
`ops/write-session.sh exec <id> -- …`로 돈다(ADR-0175). 무신원 psql·bootRun은 보호 표 쓰기가 `SSWG1`로 거절된다.

0. **선행 조건 확인** — 월 13:30 `daily-update`와 14:00 `corporate-action` 사슬이 **끝난 뒤**. `RUNNING` 0.
   ```
   psql -d stock_screener -At -F'|' -c "SELECT job_name,status,started_at::timestamp(0) FROM batch_execution WHERE started_at::date='2026-09-21' ORDER BY id"
   ```
1. **덤프.** `--apply --replace`는 `RESTORED` 평면 2,434행을 **지우고 다시 쓴다.** `ops/dump-db.sh`.
2. **순위 축 확인** — `guard-rebalance-on-dirty-rank.sh`가 `--job=universe*`를 막는다. 2026-09-19 16:00 실측
   **이상치 0**이지만 14:00 사슬이 재무를 건드리므로 **그날 다시 잰다**(가드가 알아서 막지만, 막히면
   우회하지 말고 오염을 먼저 고친다).
3. **복원 전 입력 스냅숏**:
   ```
   RANK_SNAPSHOT_DB=stock_screener RANK_SNAPSHOT_OUT=$HOME/stock-screener-apply-window/restore-20260921/rank-after.tsv \
     ./gradlew test --tests "*RankShareCountSnapshotTest*" --rerun -q -PskipFrontend
   ```
   **MUST: `--rerun`을 뺀 적이 있다** — gradle이 UP-TO-DATE로 건너뛰어 옛 파일을 결과로 읽을 뻔했다.
4. **재복원 — 유니버스마다 따로.** `--replace=true`가 없으면 **반드시 터진다**(UPSERT 키가
   `(universe_code, stock_id, effective_from)`이라 격자가 밀려 생긴 구간 겹침을 못 막고
   `ex_membership_no_overlap`에 걸려 통째로 되돌아간다).
   ```
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe-restore --universe=KOSPI_TOP200  --apply=true --replace=true'
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe-restore --universe=KOSDAQ_TOP150 --apply=true --replace=true'
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe-restore --universe=US_TOP1000    --apply=true --replace=true'
   ```
   콜: 국내 격자점당 1콜(약 50) · 미국 격자점당 약 20콜(약 1,060 · 15분).
   **격자점 하나가 실패하면 그 유니버스를 통째로 중단한다** — 런렝스가 구멍 위를 이어 없던 편입을 지어낸다.
5. **파생층 백필** (§규칙 — 명부가 바뀌면 백분위·업종상대가 낡는다):
   ```
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill --from=<보존 하한> --as-of=<최신 거래일> --tickers=<활성 편입 1종목>'
   ```
   실측 소요: 파생층 1.20초/거래일 × 약 269일 + 창 밖 고정비 약 118초 ≈ **7~8분**.
   **원본층은 안 돌아도 된다** — 복원은 `financial_statement`를 안 건드린다.

## 판정 (수로 답한다)

```
psql -d stock_screener -At -F'|' -c "COPY (SELECT m.universe_code, s.ticker, m.effective_from, m.effective_to
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id WHERE m.origin='RESTORED'
  ORDER BY m.universe_code, s.ticker COLLATE \"C\", m.effective_from) TO STDOUT WITH (FORMAT csv, DELIMITER E'\t')" \
  > restored-after.tsv
diff restored-before.tsv restored-after.tsv | grep -c '^[<>]'
```

- **① 달라진 행 수 / 2,434** — 선례가 64%다. 유니버스별로 가른다.
- **② 공통 격자 날짜에서 `rank-before` 대 `rank-after`가 0줄인가** — 0이면 ①은 격자 이동 몫이다.
- **③ 미국이 ①에서 0에 가까운가** — 위 예측(Q4V는 입력을 안 바꿨다)이 맞았는지 답한다. 크게 다르면
  **원장 8건이 범인인지 먼저 보고**, 아니면 ②의 전제를 의심한다.
- **④ EMAT 2026-02-20이 여전히 `US_TOP1000` 편입인가.** 지금은 `RESTORED` 02-20~02-27이고 그날 시총이
  192배 어긋난다(ADR-0126 펜스). 재복원이 그 명부를 바꾸면 ①(L·E·H) 축의 크기가 달라진다.
- **⑤ 펜스 재실행** — ADR-0135 전제가 초록으로 돌아오는가.

## 안 하는 것

- **`--dates`를 주지 않는다.** `--apply`와 못 쓰고, 관측 하한 뒤를 가리키는 격자로 쓰면 GiST에 걸린다.
- **원본층 백필을 안 돈다.** 복원은 재무를 안 바꾼다.
