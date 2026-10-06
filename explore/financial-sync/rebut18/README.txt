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
