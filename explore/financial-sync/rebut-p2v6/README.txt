rebut-p2v6 산출물 색인 (2026-10-06 · 2부 v6 계획 안 렌즈 · DB는 운영 SELECT만 · 사본 만들지 않음)
DART 실키 21콜 순차(16:19:45~16:27:24 · DartListQueryProbeLiveTest · 700ms 간격) · SEC 0콜
코드 기준: main 01c885b7 (읽기만)

[DB 질의 — 운영 stock_screener SELECT]
q01_kr_fallback_now.*       현 편입 국내 · is_latest · period_end>=2024 · 지배 칸 NULL 계수(분기·FY)
q02_*, q02b_*               (버림) OCF로 FSR 미반영을 가르던 첫 판 — 분기 OCF가 원래 비어 오판. q03으로 대체
q03_kr_fsr_unfilled_v2.*    FSR 전용 칸(현금·재고·매출원가·지배 둘) 전부 NULL인 라벨 = 카카오뱅크 10행뿐
q04_heavy_runs.out          09-15 이후 무거운 잡·기업행위 실행 행(note의 실제콜 · 교정 수)
q05_peak_current.*          현 편입(350)만의 공시 몰린 날 종목 수(최대 287 · 2026-08-14)
q06_taxrate_skip.*          (a) 상태 모의: 최신 FY의 세율 재료가 NULL이면 normalizedTaxRate가 null 대신 4년 중앙값을 내는 종목 수
q07_ofs_candidates.out      FSR이 채운 행은 있는데 지배자본이 한 번도 없는 현 편입(OFS 전용 후보 20)
q08_nic_null_nci_real.out   지배순이익 NULL인데 비지배가 실재(tec/te<0.9)하는 행 15(4종목)

[DART — 원시 JSON 그대로]
dart1/ dart1.log  q1 list.json 10-06 정기공시(4건 · 전부 [기재정정]) · q2~q4 에코프로머티 2025/11014 FR·FSR CFS(013)·OFS
                  q5~q7 HK이노엔 2025/11011 FR·FSR CFS(013)·OFS · q8 신영증권 2026/11012 FR → 013 · q9 신영 2026/11013 FR
                  q10 리노공업 2026/11012 FSR CFS → 013
dart2/ dart2.log  오늘 정정 3건(시알홀딩스 · 청보 · 피코그램 [기재정정]반기 (2026.06))의 같은 날 반영 — FR · FSR CFS · FSR OFS
dart3/ dart3.log  펌텍코리아 2025/11014 CFS · 솔브레인홀딩스 2025/11011 CFS — 지배 귀속 순이익을 원장이 주는가
fr_fsr_shared.py/.out  저장 응답 5라벨로 FR 대 FSR 공유 9칸 대략 대조(0콜) — 불일치 0/45

[기타]
corp/CORPCODE.xml  ../../../../p2/corpcode/q1.zip(오늘 09:25 받은 corpCode)을 푼 것(30MB · 지우지 않음) · stock2corp.tsv 종목코드→corp_code

[메모]
notes.txt  근거 메모 N1~N8 + 덤(코드 줄 · 질의 결과 · 실측)
