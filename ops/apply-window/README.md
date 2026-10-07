# 적재·백필 창 절차 — Q4V 반영본 (2026-09-18 · 리허설 거침)

**이 폴더의 스크립트가 절차다.** 산출물은 `$Q4V_WORK`(기본 `~/Library/Application Support/stock-screener/apply-window`)에 쌓인다.
같은 형태의 창(E·L 반영 · 국내 기저 등)에서 그대로 쓰되, 대상·기대값은 그 축의 ADR §결과에서 다시 정한다.

스크립트: 이 폴더 — `env.sh` `00-precheck.sh` `01-dump-shadows.sh` `02-snapshot.sh` `04-refill.sh` `05-backfill.sh` `06-vacuum.sh`
`07-judge.sh` `08-gate.sh` `r3nk.py` `premises.py` `a2.sql`. 정본 결정: ADR-0161 §결과. 앞선 판과 반증 기록은 커밋 메시지와 ADR-0161 §결과에 있다.

## 리허설(2026-09-18 01:27~03:44 · 운영 역할을 사본에 준 전 구간 실행)에서 잰 것
- **명령·단언이 전부 동작한다**: 대상 1,207(현재 1,000 · 과거 207) · 잡마다 새 실행 행·상태·대상 수 단언 · 일시 실패 자동 재시도(WCC·EXC `TRANSIENT`) ·
  기대 실패(APGE·CRNX `NOT_FOUND`)는 통과.
- **이른 판 수가 재생과 같다**: 현재 편입 2,257 · 과거 편입 310 (재생 2,565 + 캐시 없는 EBC 2 = 2,567).
- **③ 구간 규칙 OK**: 규칙 밖 0 · 같은 판 값(전 컬럼 해시) 차이 0 · 끝없는 구간 0 · 삽입 판 수 = note 합계.
- **④ 국내 불변** · **⑤ ADJ_CLOSE 감소 0**.
- **⑧ 게이트 통과**: Q4V 사본과 대조 사본에 스케줄러와 같은 refill(플래그 거짓·8년)을 돌린 결과가 **같다**(둘 다 LNT 2건).
  즉 Q4V가 넣은 행을 다음 정기 refill이 되돌리지 않는다.
- **소요**: refill 1,000종목 19분 · 과거 207 3분 · 원본층 백필 4분 · 파생층 5분 · 스냅숏 1분 · 판정 3분. 전 구간 약 2시간.

## 리허설이 드러낸 것 (절차·코드에 반영함)
- **판정 스크립트의 프로세스 치환 버그** — `cmp` 뒤 `diff`가 같은 `<(...)`를 다시 못 읽어 「Bad file descriptor」가 났고 비교가 **판정 불가인데 「다름」으로** 찍혔다. 파일로 만들어 넘기게 고쳤다.
- **② 기준이 틀렸다** — 「적용 전 대 후 하한 앞 순위 재료 불변」은 Q4V 밖 원인을 잡는다. 리허설의 10줄은 전부 `EXE`이고, **2026 Q2 판이 아예 없다가 이번 refill이 새 라벨로 넣은 것**이다.
  Q4V만 가르는 기준은 **운영 후 대 S0 후(전 격자) 0줄**이고 리허설에서 통과했다. 전/후 차이는 정보로 남기고 복원 재실행 여부는 ADR-0135 규칙으로 따로 판단한다.
- **note가 새 라벨을 안 셌다** → `34e3acc`로 `신규=`를 넣었다. 새 라벨은 무효화도 안 걸려 백필 목록에도 없다 — 현재 편입은 13:30이 보존 창을 다시 써서 낫지만 과거 편입은 그 경로가 없다(따로 잰다).
- **적재 창 의존(기존 결함)** — 11년 창과 8년 창이 내는 판 집합이 `LNT` 2018·2019 Q4(2021-02-19)에서 갈린다. 캐시 1,214종목 스윕 전수로 그 둘뿐이고 값 차이는 0이다.
  ⑧ 게이트가 두 사본에서 같은 사건을 내므로 Q4V와 무관함이 확인된다.
- **note 비교는 합계로** — 일시 실패 종목이 달라 본실행/재시도 분배가 갈린다(운영 2,249+8 대 S1 2,253+4, 합 2,257 동일).

## 창
**금 2026-09-18 15:12 이후 시작** · refill·게이트는 **18:50 KST 전**(EDGAR 접수 창 — 인용) · 토 09-19 20:00 리밸런싱 전에 판정까지. 잡은 하나씩.

## 순서 (스크립트)
**잠금 jar가 배포된 운영 DB에서는** 운영에 쓰는 단계(4·5·6)를 세션 안에서 돈다(잡 사이 틈에 크론이 끼지 않게 — 가드를 켜면
무신원 쓰기는 거절까지 된다) — `ops/write-session.sh begin <id>` 뒤
`ops/write-session.sh exec <id> -- ops/apply-window/04-refill.sh stock_screener true`처럼 스크립트째 `exec`한다(ADR-0175).
`boot()`는 사본 DB로 띄울 때 세션 변수를 걷는다 — 세션은 운영 DB의 잠금 행이라 사본의 JVM이 합류하려 들면 거절된다.

0. `00-precheck.sh` — HEAD 기록·작업 트리 깨끗·RUNNING 0·bootRun 0·오늘 13:30·14:00 완료·마이그레이션 0·대상 파일(전체=현재+과거)·LATEST.
1. `01-dump-shadows.sh` — 덤프 → S1·S0 복원 + VACUUM ANALYZE.
2. `02-snapshot.sh before stock_screener`.
3. `ops/deploy.sh` → `ops/status.sh`로 앱 확인. **쓰기 세션을 쥔 채면 배포가 멈춘다**(새 앱은 기동에서 잠금을 쥔다 ·
   ADR-0175) — `ops/write-session.sh end <id>` → `ops/deploy.sh` → `ops/write-session.sh begin <id>`. 세션을 `end`하면 그 사이
   크론·관리자 잡이 쥘 수 있으니 다시 `begin`한 뒤 `RUNNING` 0을 확인하고 잇는다.
4. `04-refill.sh stock_screener_q4v_shadow true` — 그날 수를 먼저 본다(현재 약 2,257 · 과거 약 310 · 실패는 기대 목록뿐).
5. `04-refill.sh stock_screener true` — 이 뒤로 6이 끝날 때까지 멈추지 않는다.
6. `05-backfill.sh stock_screener` → `06-vacuum.sh stock_screener`.
7. `05-backfill.sh stock_screener_q4v_shadow` → `04-refill.sh stock_screener_q4v_ctrl false` → `05-backfill.sh stock_screener_q4v_ctrl`.
8. `02-snapshot.sh after stock_screener` · `… after stock_screener_q4v_shadow` · `… after stock_screener_q4v_ctrl`.
9. `07-judge.sh after-stock_screener after-stock_screener_q4v_shadow after-stock_screener_q4v_ctrl before-stock_screener`
   - ① 전 항목 같음(다르면 그 줄을 연다) · ② **운영 후 대 S0 후 0줄**(전/후 차이는 정보) · ③ `OK 규칙 밖 0` · ④ 국내 같음 · ⑤ ADJ_CLOSE 감소 0.
10. `08-gate.sh stock_screener_q4v_shadow stock_screener_q4v_ctrl` — 두 사본의 플래그 거짓 refill 결과가 같아야 한다(LNT 2건은 정상).
11. 기록: ADR-0161 §결과에 운영 수 · progress ① 닫음 · 사본 삭제는 사용자 확인 뒤(가드가 `dropdb`를 막는다).

## 판정에서 헷갈리지 않게
- ① 에서 다름이 나오면 **먼저 두 refill note 합계와 실패 목록**을 본다. 리허설의 ① 차이는 내가 S1에만 먼저 돌린 게이트가 만든 LNT 2행이었다.
- ADR-0135 펜스는 적용 뒤 `US_TOP1000(… 재무 2,5xx행)`으로 신고한다 — Q4V 행이고, 순위 재료는 ②로 따로 답한다.
- ADR-0151 펜스는 97±(새 공시로 흔들린다) · ADR-0161 펜스는 `OK …(증거 예외 1)`.
- ⑤ 의 「백분위 감소가 원본 감소보다 큰 칸」은 과거 편입이 백분위 모집단 밖이라 생기는 정상 차이다(리허설 142칸).
