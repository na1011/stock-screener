# 미국 관문 실행기 (2026-10-16)

`docs/reload-gate.md` 순서 10(관문 실행)을 스크립트로 옮긴 것이다. **관문의 내용(어떤 수정이 들어가는가)은 정하지 않는다** — 관문 설정
(`stockscreener.gate.us`) 하나 뒤에 모인 것을 켜고 재적재·백필하는 실행기다. 계획 정본은 브랜치 `explore/s-vintage-coherence`의
`explore/financial-sync/GATE-RUN-PLAN-1016.md`(v2 · 두 렌즈 반증 「고쳐서 간다」를 닫은 판)이다. 옛 ADR-0161 적용 창 절차(전체 사본 둘)는
`q4v/`에 기록으로 남아 있다.

**전체 사본이 없다** — 관문은 운영 한 판 + 전후 스냅숏 + 하드 단언이고, 명령이 서는가는 **작은 DB 드라이런**이 묻는다
(`CLAUDE.md` 「드라이런이란 무엇인가」). 산출물은 `$GATE_WORK`(기본 `~/stock-screener-gate-run` · 공백 없는 경로)에 쌓인다.

## 순서

| 단계 | 어디서 | 하는 일 |
|---|---|---|
| `00-precheck.sh` | 세션 밖 · 읽기 | HEAD · 트리 · RUNNING · bootRun · 그 주 토요일 리밸런싱 · 마이그레이션(운영 jar) · 대상 수와 하한 · a2 종목 ⊂ 대상 · 미국 알림 항목 · 프리셋 날짜 |
| `00b-stop.sh` | 세션 밖 | VACUUM → `ops/stop.sh --app`(크론이 남았으면 멈춘다) → 앱 pid 없음 |
| `ops/write-session.sh begin <id>` | | 이 뒤로 쓰는 단계는 `ops/write-session.sh exec <id> -- ops/apply-window/<단계>` |
| `01-dump.sh` | 세션 안 | 되돌리기 덤프(운영 = `ops/dump-db.sh` · 작은 DB = 산출물 폴더) |
| `02-snapshot.sh before <db> off` | 읽기 | 프리셋 · 순위(꺼진 판독기) · 지표 묶음 · 재무 행 · 명부 · 펜스(세션 훅의 붙잡는 갈래 · `PREMISE_DB`) · 국내 해시 · a2 |
| `02b-intake.sh` | 세션 안 | 폐지 실체 인입 `us-delisted-intake --apply=true`(행만 · 원자료는 03이 받는다) · 늘어난 행 = 요약의 만든 행 · 들인 행 전부 비운 표지·등록인·시대 끝 · 후보 수 다시 셈 |
| `03-refill.sh` | 세션 안 | 재적재 `cur`(`--universe=US_TOP1000`) → `cand`(`--population=candidates`) · 11년 · 이른 판 · 관문 인자 · 실패는 구성원마다 |
| `04-unresolved.sh` | 읽기 | 클래스 축 미해결 0 · 해석 실패·보충 실패·옛 판·회수정지 전원 판정(`verdicts.tsv`) · a2 미국 0 |
| `05-roster.sh` | 세션 안 | 순위 가드(훅을 그 DB로) → `market-calendar` → `universe-restore --apply=true --replace=true` → 창 결손 0 |
| `06-reconcile.sh` | 세션 안 | 화해 두 판 — 둘째 판 ①② 쓴·지운 0 · 두 판 실패 0 → VACUUM |
| `02-snapshot.sh after <db> on` | 읽기 | 순위는 켠 판독기 |
| `07-judge.sh after-<db> before-<db> [after-<작은 db>]` | 읽기 | 국내 불변 · ADJ_CLOSE 감소 0 · 표본 동일성 · 펜스 귀속 |
| `09-flip.md` | 사람 | 세션 닫기 → 기본값 커밋 → 배포 → 켜면서 고칠 것 → 설정 걷기 |

**MUST: `05-roster.sh`만으로 관문이 닫히지 않는다** — 관측 평면(08-31~)도 옛 순위 기저이고 그것을 다시 짓는 잡은 클래스 축 묶음의
설계 항목이다(`docs/reload-gate.md` 「관측 평면이 옛 순위 기저로 남음」). 그 잡이 생기면 `05`의 ③을 바꾼다.
**MUST: 창 결손 0은 복원 잡의 꼬리가 채운다는 전제다** — 그 꼬리가 main에 들어가기 전에는 `05` ④가 멈춘다(V·STZ·FWONA 시세 0행).

## 드라이런 (작은 DB · 계획 v2 §3)

```
ops/apply-window/small-db.sh stock_screener_gate_<이름> <표본 파일>     # G1 — 표마다 행 수 = 운영의 같은 술어
GATE_PROD=stock_screener_gate_<이름> ops/apply-window/00-precheck.sh  # G3 — 이하 같은 순서를 그 DB로
ops/write-session.sh begin g3 --db stock_screener_gate_<이름>
GATE_PROD=… ops/write-session.sh exec g3 --db stock_screener_gate_<이름> -- ops/apply-window/03-refill.sh  # …
GATE_PROD=… ops/apply-window/08-next-regular.sh <표본 파일>          # G4 — 다음 정기 경로가 관문 행을 안 건드린다(후보 ⑧ 포함)
```

- 작은 DB에는 그 이름의 `guard_setting` 행이 있다 — 쓰기 세션 경로를 운영과 똑같이 탄다(운영에서 한 번도 안 돈 경로를 관문 날 처음 태우지 않는다).
- 작은 DB에 붙는 `bootRun`은 알림 변수를 비우고 `DB_URL`·`BOOTSTRAP_DB`를 둘 다 준다(`env.sh` `boot()`).
- **판정이 끝나면 작은 DB·덤프·산출물을 지운다**(`CLAUDE.md`). 지우기가 훅에 막히면 명령을 사용자에게 넘긴다.

## 판정에서 헷갈리지 않게

- `07` ③ 표본 동일성에서 다름이 나오면 **두 실행 사이 그 종목의 새 공시**부터 본다(제출 목록 `filingDate`) — 귀속되면 `sample-attributed.tsv`.
- 펜스는 관문이 값을 바꾸러 도는 것이라 바뀌는 것이 정상이다 — 바뀐 전제마다 어느 묶음이 바꿨는지를 `fence-attribution.tsv`에 적는다.
- `08-next-regular.sh`의 0이 아닌 수는 먼저 「창 의존」(11년 대 8년 창 — LNT 2018·2019 Q4)인지 본다.
