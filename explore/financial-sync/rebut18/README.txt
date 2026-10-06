rebut18 — 1부 v14(e341c70c) 계획 안 렌즈 반증 · 외부 0콜 · 운영 SELECT만 · 시험 DB stock_screener_r18a_test(새로 만든 빈 DB)
코드 사본: scratchpad/r18/{base,m5b,m1b,...} = git archive e341c70c (wt-p1v11은 읽기만)

pin/run.sh        판 고정 시험을 r18a로 돌리고 붉은 해시를 뽑는다
pin/mut.py        변이 e5(JDK 형 빈 주입) · e1(@Bean 익명 하위형) · bump(판 8)
pin/drive.sh      a(들임) → 판 8 → b → c(범위 밖 본문만) 순서
pin/base.sum      사본 그대로: 9 시험 · 실패 0 · 건너뜀 1(git 아님)
pin/drive-e5.out  e5: a 붉음(0a0afbd6…) → b 초록 → c(SecNameNormalizer 본문만 "_lab.xml"→".xml") 초록 = 새어 나감
pin/drive-e1.out  e1: a 붉음(8b5c57f6…) → b 초록 → c(익명 ExternalApiClientFactory 본문만) 초록 = 새어 나감
pin/e5-effect.log e5 효과: 운영 생성자(@Autowired 4인자)에 그 빈 → 같은 색인이 OK abc-20180331.xml → UNREADABLE(종료 상태)
(진행 중 — 아래에 이어 적는다)
--- 이어 쓴 에이전트(23:4x~) ---
coll/run2.sh      캐시 디렉터리를 재사용해 수집기를 다시 돈다(ADR 2-2 「그 경로를 다시 모은다」 꼴)
coll/out/me1·me2  me1: index.json 503-always → failures=1 · .status 503 / me2: 고장 없이 같은 디렉터리 → failures=0인데 index.json.status가 남음
coll/out/me3.log  me-stale 재생: replay FAILED TRANSIENT · 같은 서버 직접 OK → 2-2 통과 · 2-5 거짓 정지(내가 다시 잼)
coll/out/kind.log (앞 에이전트) 늘 잘린 인스턴스: 직접 MALFORMED · 수집기 .status 503 → 재생 TRANSIENT (종류 불일치)
pin/me-ms.log     대조군 ms(운영 @Autowired 생성자가 Backoff 하나를 두 호스트에 나눔 = v12 원래 결함): SecRetryBackoffTest 7 · SecFilingSupplementTest 35 · WriteContract 9 전부 초록(내가 다시 잼) — 교차 시험은 패키지 생성자로 Backoff 둘을 직접 넣는다(SecFilingSupplementTest:214-217) → 운영 배선 회귀를 행동 시험이 못 잡고 판 해시만 붉음
pin/me-m5b-c.log  e5 c 상태(판 8 · SecNameNormalizer 본문만 바꿈) 판 고정 9/0/0/1 초록(내가 다시 잼)
pin/me-m1b-c.log  e1 c 상태(판 8 · 익명 @Primary ExternalApiClientFactory 본문만) 9/0/0/1 초록(내가 다시 잼) · e1 효과(저장 상태)는 안 잼
coll/out s0new=s0old=s1new 파일 8개 해시 같음(d0c8edd1…) · s1old(잘림 1회) 다름 · s0new sec/ = p1v14-cache sec/ 0차이 — 성공 경로 바이트 동일(0콜)
운영 SELECT: universe-rebalance 18행 · US_TOP1000 effective_from = target(금) 전부 · 지난 토요일 6번 되감기(아래 보고) · 앱 stdout.log는 10-03 21:11부터 · .gz 0개
주의: 이 에이전트가 해시 목록을 /tmp/x에 한 번 썼다(지우지 않음)
