# 재무 동기 — B(F6) · (가) companyfacts 결손 · (나) 공시 반영 주기를 한 체계로 · 계획 v1 (2026-10-06 01시 30분)

**왜 합치나(내 판단 · 세 반증이 같은 결론)**: (가)의 어댑터 보충 없이는 B가 PPL에 표지만 쓰고 6월 분기는 못 받으며, (나)의 「접수번호 ∉ DB」 술어는 80종목에서 영영 안 맞는다. (가)를 손 FR로 적용하면 좁든 전량이든 무거운 잡 시계를 밀어 순손실이다(`financial-gap/PLAN.md` 계획 밖 판정 4). 시계를 안 미는 적용 경로는 14:17 단계의 GAP_FILL FR뿐이고 그것이 B의 부품이다. 셋의 재료(submissions 하루 캐시 · stock_id 진입점 · 상한 · 감시)가 같다.
**크기(반증들의 수)**: (가) 최신일 FUNDAMENTAL 4,035칸(상한) · 누적 136,638칸 · (나) 10-30까지 46,751칸-일 · 시즌(10-30~11-26) 587,458칸-일(모형) · B 실측 23종목 287칸(창 전체) + A의 새 대표 3종목 최신일 69칸.
**마감**: 10-30(시즌 · 정기 FR) — 그 전에 배포 · 사용자 확인. **(가)는 10-10 리밸런싱의 상류**(80종목 Q1 주식수 · 경계 EXLS·KNSA·VFC·CDNS·LFUS)지만 그 전에는 못 닫는다 — 10-10 명부가 그 주식수로 판정된다는 것을 기록하고, 동기 뒤 첫 리밸런싱(10-17)이 바로잡는다.

## 1. 어댑터 — SEC 공시 보충((가))
1. `SecEdgarFacts` 조회가 companyfacts + **submissions(하루 캐시 · `recent` + 적재 창을 덮을 때까지 `files` 페이지)**를 읽고, 창 안 정기공시 중 **`isXBRL == 1` ∧ 그 accn의 us-gaap 팩트 0**인 것만 보충 대상으로 고른다(GEHC 10-K 2022처럼 dei만 있는 것 포함 · 비XBRL 6-K 제외).
2. 대상마다 묶음 색인 → 인스턴스(`*_htm.xml` · 없으면 `_cal/_def/_lab/_pre`·`FilingSummary`·`MetaLinks` 아닌 `.xml`) → **네임스페이스 URI로** us-gaap·dei · `unitRef` 있는 팩트만 · 축 없는 컨텍스트 · **컨텍스트 식별자 = 그 CIK** → companyfacts 노드 모양(accn · start · end · val · filed = submissions `filingDate` · form = submissions `form` · fy·fp = 인스턴스 dei · unit = measure · 분자/분모 `A/B`)으로 같은 트리에 합친다 → 기존 해석 그대로.
3. 같은 (개념·기간·단위)의 중복은 `decimals`가 가장 정밀한 것 · 같은 정밀도에 다른 값이면 **그 종목 MALFORMED**(조용히 고르지 않는다 · 빈도는 RS0이 잰다) · 비교는 `compareTo`.
4. **보충이 하나라도 실패하면 그 종목 실패**(색인·인스턴스 404 · XBRL인데 인스턴스 없음 · 파싱 실패 → MALFORMED · 5xx·전송 → TRANSIENT) — 아무것도 안 쓴다. `completeVintageHistory=true`가 그 판 이력을 지우는 것을 막는다(`FinancialWriter.retractStaleVintages:283-349`).
5. **근거 문장**: 「보충된 트리의 출력 = companyfacts가 그 공시를 실었을 때의 출력」(옛 라벨에 비교열 판이 생기는 것 · HOLE 해가 살아나는 것은 그 정의에 든다 — 920종목이 이미 받은 해석과 같다) · 설정 뒤에 두지 않는다(관문 규칙의 사유 = 같은 원자료를 새 규칙으로 다시 읽어 섞는 것 — 여기 해당 없음 · ADR-0173).
6. FR note에 「보충한 공시 수 · submissions 정기 접수번호 − companyfacts 접수번호」.
7. 스윕 캐시(`SecEdgarFactsSweepTest` · `EarlierVintageReplayHarnessTest`)에 submissions·인스턴스 캐시를 더한다 — 안 하면 관문 2단계 스윕이 거짓 차이를 낸다.

## 2. 14:17 재무 동기 단계(`FinancialSyncStep` · 스케줄러 전용 · GAP_FILL)
대상 술어 셋 — 합집합 → 시장마다 FR 한 실행 → 국내 FSR 한 실행.
- **(가)·(나) 공시 갈래 — 미국**: 현 편입마다 submissions(하루 캐시) 정기공시(`isXBRL == 1` · 적재 창 안)의 접수번호 중 DB `report_code`(`10-Q:<accn>` 꼴)에 없는 것이 있으면 대상(보충 뒤엔 80 + HOLE을 정확히 고른다 · 원장이 회수한 것 등 「영영 안 맞는」 것은 아래 감시가 N평일 연속 대상으로 잡는다).
- **(나) 공시 갈래 — 국내**: DART `list.json`(`pblntf_ty=A` · 마지막 확인일 ~ 오늘 · 페이지 전부)에서 현 편입 `stock_code`의 정기보고서 → 그 보고 기간이 DB에 없거나 [기재정정]이면 FR + FSR 대상(실측 08-01~10-05 3,594건 · 36페이지 — 평일 하루는 수 페이지).
- **B 편입 갈래**: v7 그대로(표지 표 · 시작 > 표지 날짜).
- **국내 FSR 후속**: v7 그대로(편입 갈래 + 변경 갈래 `created_at > FSR synced_at` · 표지는 같은 트랜잭션 `now()` · 정정보류 → 표지 안 씀).
- 표지 표 · 초기값 · 이른 판 끔 · 스위퍼 · 정기 FSR 대상 축소는 B v7 결정 그대로.

## 3. 상한과 시각(B v7 반증 2·3·7)
- 콜: SEC는 일 한도 없음(분당) — 미국 submissions 1,000/일 ≈ 3.3분(5/초) + 대상 FR · DART는 **이 JVM 잔량**(`usedToday`)을 FR 41 · FSR 49.4로 나눔 — 「방어 상한」 주장은 지운다.
- **시각 상한**: 단계 전체가 14:17 + 25분을 넘으면 남은 것을 다음 평일로(무거운 잡 날 15:30 점검 최악 약 15:43 → 상한으로 15:30 전에 끝나게 다시 잰다) · `PipelineScheduler:417-421` · `data-pipeline.md:182` 숫자를 실측으로 다시 쓴다.
- 순서: 편입 갈래 → 공시 갈래(오래된 공시 먼저) → FSR 편입 → FSR 변경.

## 4. 감시(15:30 경로만)
① 편입 갈래 대상이 시작 뒤 첫 14:17을 지나도 남음 ② 공시 갈래 대상이 **3평일 연속**(보충 실패·원장 회수·상한 넘침) ③ 국내 FSR 변경 갈래 3평일 연속 ④ 오늘 표지·공시 갈래로 FR이 돌았는데 최신 지표일 지표 0행(14:20 안 돎) ⑤ **빈 회계연도**(현 편입 미국 · 창 안 FY 결번 — SPGI·AOS 꼴 · 예외는 구성원을 열어 정한다).

## 5. 고칠 것 그대로 가져옴
- `DartCorpCodeCache`: 하루 갱신 · 실패 기억 · 옛 맵 + 나이 WARN · 첫 적재만 던짐(B v7 계획 안 1 · B v7 계획 밖 5).
- 드라이런 가드(B v7 6 · `FlywayMigrationStrategy` · `SENTRY_DSN`도 비움 · 대조군 DB는 버리는 이름) · RB 시각 창(평일 13:20~15:45 밖 · a·b 순차).
- FSR 고아: 14:17 FSR은 평일 최대 수십 분 · 웹이 만듦 → 노출이 꼬리와 다르다(B v7 계획 밖 3) — **배치 행 생존 판정(JVM 토큰 + advisory lock)을 이 계획에 넣는다**(그 전엔 고아가 생기면 `00-precheck.sh`가 영구 정지 · 원시 SQL 승인).
- 소비자: B v7 결정 12 + `.factcache` 코드 2 + ADR-0135 펜스(약 80종목 더 붉어짐 → 관문 재고) + `PresetResultSnapshotTest` MUST(14:17 귀속) + B의 15:30 javadoc.

## 6. 드라이런 규칙 v1(대조 전 커밋 · 시각 창 밖 · 사본 a(main) · b(이 코드) · 같은 덤프)
- **RS0(0콜 · 저장 원자료)**: (i) AAPL·MSFT·JPM + GOOGL 또는 HIG(비교열 판) + SPGI(HOLE)에서 정기공시 하나씩을 companyfacts에서 **지운 트리 + 인스턴스 보충** = 원래 트리 출력(필드 전부) · 지운 트리(보충 없음)는 그 분기가 없다(원래 결함 재현) (ii) 79종목 보충 전/후 스윕 — 기존 라벨에서 바뀐 칸 전수를 연다 (iii) 같은 정밀도 다른 값 중복 빈도.
- **RS1(사본 b · 통합 시험 단계 1회)**: 대상 = 예측(편입 21 + 공시 갈래 약 80 + HOLE + 국내 공시 갈래) · 보충된 공시 수 · 6월 분기 행 생김 · **차분 오라클 a1 → b → a2**(a는 같은 종목에 손 FR — a1 = a2인 종목만 비교 · a는 보충이 없으니 80종목은 「b에만 6월 분기」가 기대).
- **RS2**: 2회째 대상 = 1회째 실패 · 보충 실패 주입(BE /A 404) → 그 종목 실패 · 삭제 0.
- **RS3**: 화해 뒤 바뀐 칸(최신일 · 창 전체) — (가) 상한 4,035/일 대비.
- **RS4**: 상한(콜·시각) · 국내 편입·변경 갈래 실데이터(사본에서 라벨 한 행 지움 → 손 FR 재삽입) · 시작 = 표지 날짜 경계.
- **RS5**: 단위 — 보충 술어(isXBRL · us-gaap 0) · 인스턴스 찾기(인라인 · 비인라인) · 네임스페이스 · unitRef · CIK 식별자 · 정밀도 · 실패 계약 · DART list 페이지 · 표지 불변 · 감시 축 · Dart 캐시 · 생존 판정.
- **RS6**: 가드 대조군 · A + A2 + 이 코드 사본에서 10-02 리밸런싱 재연 → 단계 대상에 새 대표·ET·DDS.

## v1 반증 판정 — 계획 안 렌즈(2026-10-06 02시 · **「다시 짠다」 — §2 미국 공시 갈래** · `rebut1/`)
1. **공시 갈래 술어가 수렴하지 않는다** — `report_code`(US 208,752행 전부 `form:accn` · 최신 아닌 판 남음 · Q4 유도 행은 FY accn)는 「소비한 공시」가 아니라 「행을 만든 공시」 · 행 없이 사라지는 길 넷(값 같으면 판 접힘 `SecEdgarFacts:899-902` CNP 10-Q/A · 같은 날 접기 `:830-858` · 창 밖/SPAC 전신 MGY 2018·HIMS 2019 · 표지만 있는 정정) → DB에 없는 accn 505건 · 251종목(us-gaap 있음 232 · 127종목 = 보충 대상 아님 → 영구 대상 · dei만 222 · 148종목(10-K/A 185) = 보충해도 0 · IFRS 시절 52 · 8종목) · 83 표본만으로 보충 뒤에도 FE·GRMN·CNP·DUK·WES·ZWS·BE·KO·ONB·CDNS·EIX·INCY 영구 대상 · 창을 좁혀도(2022 뒤 153 · 2025 뒤 60 · 2025-10 뒤 36) 남음 → 매 평일 약 250종목 FR · 감시 ② 영구 울림 → **키를 「소비」로**(마지막 성공 FR의 입력 accn 집합 저장 또는 그 FR이 쓴 submissions의 받은 시각 워터마크) · 수용 기준: 0콜 재현을 1,000종목에 — 「동기 1회 뒤 대상 = 새 공시뿐」.
2. **보충 술어(`isXBRL ∧ us-gaap 0`)가 80보다 넓다(오류 2)** — GEHC 10-K FY2022(dei 2) · DSGX 40-F 셋 · 「표지만 있는 정정」과 「companyfacts가 빠뜨린 공시」가 안 갈림 → 값이 바뀌는 범위가 80 밖 · RS0(ii)는 보충 대상이 있는 전 종목(80 + 148 + 8 이상) · 실패 범위(83 CIK 중 41이 2018까지 페이지 · 85장 · JPM 52장) → **보충 결과를 accn마다 영구 저장**(평생 한 번).
3. **RS2 대조군 없음** — 「BE /A 404 → 삭제 0」은 실패하면 안 쓰니 당연히 초록 → 같은 주입을 「그 공시만 건너뜀」 계약으로 돌려 삭제 > 0(`FinancialWriter:283-349`)을 대조군 · `complete=false`도 안전 아님(`:300-304` — 유도 라벨은 그래도 회수 · 10-K 보충 실패면 Q4 판이 지워짐 · 코드로 읽음).
4. **시각** — G 날 15:30 전 여유 약 2분(`PipelineScheduler:417-421`) · submissions `recent` 1,000콜만 200초(`application.yaml:318` 300/분) · 「14:17 + 25분」이 절대 시각이면 풀 1이라 무거운 잡 날엔 단계가 아예 안 돎 · 목요일 정기 FR이 느는 몫 · 하루 캐시의 키·무효화 미정.
5. **국내 공시 갈래** — [첨부추가] 8/8은 DB `published_at` = `rcept_no` 앞 8자리(`DartReportPeriods:42-53`) · [기재정정] 7/7은 DB에 아직 없음 → 접두어 파싱 없이 (라벨, `rcept_dt`) 대 `published_at` · 200행 중 정정 197이라 원본 보고서 규칙은 미검증 · 신영증권(001720 · 3월 결산)은 DB 라벨이 12월 근사라 「(YYYY.MM) → 라벨」 미정의 · 「마지막 확인일」 저장 미정 · 지나가면 끝나는 방식이라 FR 실패·DART 재무 지연이면 공시를 잃음 → **상태 기반**(DB가 맞출 때까지 대상) · **계획 밖 보고**: 국내 정정 행(`revision_no > 0`) 59 중 30(30종목)이 직전 판과 핵심 다섯 칸이 같음 — `FinancialWriter:492-521`이 값 비교 없이 개정 행을 쌓고 지표를 무효화.
6. RS 공백: RS1 예측 「약 80」은 1과 모순 · `files` 페이지 RS 없음 · 국내 실데이터 RS 없음 · 감시 ② 거짓 경보율 RS 없음 · RS0(i)은 dei 있고 us-gaap만 빠진 갈래(GEHC 꼴) 안 탐.
- 재지 않음: 표본 밖 917종목 submissions(위 수는 하한 · 캐시 09-15~27) · dei만 정정의 인스턴스에 us-gaap이 있는지(KO·DUK 10-Q/A) · Archives 404·5xx 비율 · 1,000종목 페이지 수 · `list.json` 기간 상한·DART 재무 지연 · [첨부정정] · CIK 매핑 빠진 종목 · submissions 1,000 캐시 힙(평균 207KB · 정기공시 행 3.1%).

## v1 반증 판정 — 계획 밖 렌즈(2026-10-06 02시 · **「다시 짠다」 — 묶음 근거(PLAN.md:3)와 §2 술어** · `refute1/`)
1. **[오류 3] 미국 공시 갈래 술어는 80 + HOLE을 정확히 못 고른다** — 값 같은 /A는 행을 안 만들어 영영 대상(AMP 10-K/A `-26-000016` us-gaap 835인데 DB FY2025는 원본 `-000011` 하나) · 117종목 · 247건(정정만 62 · 원본만 41 · 둘 다 14 · 2021 뒤로 좁혀도 77 · `q02_cf_amend.py` 0콜) — 하한(submissions에만 있는 /A 83 중 6 `q09` · IFRS 시절 8종목 39 `q08` — 그중 15는 companyfacts에 dei가 이미 있어 합칠 때 중복 · RS0 밖) · 감시 ②가 117+에서 상시 붉어 진짜 HOLE(AOS·CHRD·CIFR·DSGX·ORKA·SPGI·VAL·WULF)이 묻힘 · 창 전체면 files 페이지(83 CIK 85장) → 하루 약 2,000콜 → **술어를 증분으로**: 「acceptanceDateTime > 그 종목 FR 표지」 · `recent`만 · 또는 처리한 접수번호 원장 · RS0(ii)는 캐시 1,218 전수.
2. **묶음: 설계 공유는 서고 한 번에 배포는 안 선다** — 「시계를 안 미는 적용 경로는 14:17뿐」은 거짓 — **10-30 정기 FR**이 그 경로(SCHEDULER 성공 0 → 다음 연체 10-30 · `reload-gate.md:55`) → **(가) 어댑터만 먼저 = 무해한 선행 배포**(그 사이 값이 바뀌는 곳은 토요일 꼬리 APPEND_ONLY와 10-30 FR뿐) · 1을 고치면 14:17이 80을 소급해 안 잡음 → 묶을 근거 없음 · 순서 (가) 어댑터 → B → (나) · **B의 10-12 마감이 소리 없이 빠짐** → 수와 함께(21종목 최신일 944칸/일 상한 · R6 69칸/일).
3. **10-10과 OBSERVED** — 현 편입 쪽 위험 작음(10-01 종가 × 저장 주식수 근사로 경계에 가장 가까운 것 EXLS +7.2% · VFC +8.6% · 경계 51.99억 · 결손 없는 822종목 Q1→Q2 주식수 변화 p90 2.5% · 7.2% 넘는 것 24 · `q03`·`q04`) · 「10-17이 바로잡는다」는 두 군데서 거짓: (a) 이미 Q1 주식수로 판정된 전환 — OBSERVED VFC 09-25 재편입 · RESTORED VFC 08-28 편출 · EXLS 08-21 편입(`q07`) — OBSERVED는 원시 SQL로만 되돌림 (b) **후보 쪽은 아무 잡도 갱신 안 함** — 편입 이력 없는 미국 3,317종목의 재무는 #182(08-30)가 마지막 · 그중 290이 6월 분기 결손 꼴(`q05`) · GTX(약 1017위 · 경계 −2.5%)는 companyfacts 최대 공시일 04-30 · 14:17·정기 FR은 현 편입만 → 동기가 생기면 현 편입은 최신 · 후보는 낡음의 비대칭이 커진다.
4. **15:30 산수** — 무거운 잡 최악일에 불가능: G 15:15:39 + ①4:08 + ②7:57 = 단계 0이어도 15:27:44 · 남는 2:16보다 submissions 훑기(300/분 3:20 · 페이지까지 약 6:45)가 김 · 25분 상한을 다 쓰면 약 15:53 · `00-precheck.sh:9`(15:10) · README 「15:12 이후」 · `data-pipeline.md` 13:30~15:40도 함께 → 시작 시각 컷오프 + 이월, 또는 15:53을 받고 창 숫자를 전부 다시.
5. **ADR-0135 펜스** — 지금 52행 붉음 · 전부 복원 상한(08-31) 앞의 진짜 낡음(`q06`) · `published_at ≤ 상한`을 안 걸어 시즌엔 복원에 안 닿는 새 10-Q가 매일 붉힘 → 펜스에 `published_at ≤ max(RESTORED effective_to)`.
6. **생존 판정을 끌어온 것은 회귀(코드·주석으로 읽음 · 재지 않음)** — `application.yaml:62` `minimum-idle: 0` → 풀 커넥션의 세션 advisory lock은 커넥션이 걷히면 풀림 → 다른 JVM이 산 행을 닫음 · 전용 상시 커넥션은 ADR-0163이 없앤 종료 대기(63.1초 > 60초)를 되살림 → **별도 루프**(실제 JVM으로 잼).
7. 결정 1.5 — 「데이터가 들어온 것과 같다」는 P1·[S]가 관문 대기인 지금 일관(80도 920과 같은 P1 꼴을 받음) · 다만 동등성을 보충이 발화하는 전 모집단에서 재고(IFRS·/A 포함) · 사정거리(과거 편입 14는 관문 재적재에서만 · 후보 290은 끝내 안 고쳐짐)를 적어야 선다.

# 계획 v2 (2026-10-06 02시 30분) — 둘로 나눈다
**나누는 근거(v1 계획 밖 판정 2)**: (가)의 적용 경로가 14:17만이 아니다 — **10-30 정기 FR**(SCHEDULER 성공 0이라 그날 연체 · 첫 SCHEDULER 성공이라 시계를 정당하게 시작)도 시계를 안 민다. 그래서 (가) 어댑터만 먼저 내면 **무해한 선행 배포**다 — 그 사이 값이 바뀌는 곳은 토요일 꼬리의 `APPEND_ONLY`(새 편입 · 완전한 재무를 받는 쪽으로)와 10-30 FR뿐. 묶음은 설계 공유로만 남긴다.
**B 마감의 정직한 기록**: B(F6)의 「월 10-12 13:30」은 빠졌다 — 그 대가는 21종목 최신일 944칸/일(상한) · A의 새 대표 셋 69칸/일(R6 실측)이 2부 배포까지 · 10-30 FR이 그 몫을 덮는다.

## 1부 — (가) SEC 공시 보충 어댑터 v2
1. **보충 대상**: 적재 창(`fromFiscalYear` 1월 1일 뒤 `filingDate`) 안 정기공시(`PERIODIC_FORMS`) 중 `isXBRL == 1` ∧ companyfacts에 그 accn의 us-gaap 팩트 0. 목록은 submissions `recent` + 창 시작에 닿을 때까지 `files` 페이지(83 CIK 중 41이 페이지 · 85장 · JPM 52 — 월 1회 FR 기준 미국 1,000이면 약 +2,000콜 · 5/초 약 7분 · 재지 않은 917종목은 RS0이 센다).
2. **보충 결과를 접수번호마다 영구 저장**(새 표 `sec_filing_supplement(accession PK, cik, form, filed, fetched_at, us_gaap_facts int, facts jsonb)` · ADR-0173) — 공시는 안 바뀌므로 평생 한 번 받는다(dei만 있는 정정 222건을 FR마다 다시 받지 않음 · 실패 표면 축소). 저장 뒤 FR은 저장분을 합친다 · 0건(dei만)도 저장한다.
3. **합치기**: 인스턴스(네임스페이스 URI로 us-gaap·dei · `unitRef` 있는 것 · 축 없음 · 컨텍스트 식별자 = CIK · 같은 키 중복은 가장 정밀한 것 · 같은 정밀도 다른 값은 그 종목 MALFORMED) → companyfacts 노드 모양 → **이미 같은 (택소노미 · 개념 · 단위 · accn · 기간)이 있으면 안 더한다**(companyfacts에 dei가 있는 15건의 중복 방지).
4. **실패 계약**: 보충 경로의 어떤 실패(페이지·색인·인스턴스 404/5xx · XBRL인데 인스턴스 없음 · 파싱)도 **그 종목 실패 — 아무것도 안 씀**(`completeVintageHistory=true`가 판을 지우는 것을 막음 · `complete=false`는 유도 라벨을 그래도 회수해 안전하지 않다).
5. **설정 뒤에 안 둔다 — 조건 둘을 달고**: ① 동등성을 보충이 발화하는 **전 모집단**(현 편입 1,000 + 과거 편입 208 = 캐시 1,218)에서 잰다(RS0) ② 사정거리: 현 편입은 10-30 FR(또는 2부)로 · 과거 편입은 관문 재적재에서만 · **후보(편입 이력 없는 3,317 · 6월 분기 결손 꼴 290)는 어느 잡도 재무를 갱신 안 한다 — 별도 축(「후보 쪽 재무 낡음」)으로 재고에 올린다.**
6. **근거 문장**: 「보충된 트리의 출력 = companyfacts가 그 공시를 실었을 때의 출력」 — 옛 라벨의 비교열 판(840 중 120종목 155행 꼴)과 HOLE 해 복원(SPGI·AOS)은 그 정의에 든다. **SEC가 나중에 companyfacts를 채우면** 술어가 안 맞아 저장분 대신 companyfacts가 쓰인다 — 중복 고르기가 다르면 가짜 정정이 생길 수 있다(재지 않음 · 감시: FR note의 교정 수).
7. **감시**: FR note에 「보충 공시 수 · 저장분 사용 수」 · 펜스 「현 편입 미국 빈 회계연도」(SPGI·AOS 꼴 · 예외는 구성원을 열어 정함) · **ADR-0135 「복원이 재무보다 먼저」 펜스에 `published_at ≤ max(RESTORED effective_to)`**(시즌마다 새 10-Q가 거짓으로 붉히는 것을 막고 (가)의 80행을 진짜 신호로 남김).
8. **소비자**: `SecEdgarFinancialAdapter` · `SecEdgarFacts`(트리 읽기 — 무변경) · 스윕 둘(`SecEdgarFactsSweepTest` · `EarlierVintageReplayHarnessTest` — 보충 저장 표를 캐시로 읽게) · `docs/api-spec/sec-edgar-financial.md` · 꼬리 `initial-load`(같은 어댑터 · APPEND_ONLY).

### 1부 드라이런 규칙 v2(대조 전 커밋 · 0콜 우선 · 실콜은 평일 13:20~15:45 밖 · 순차)
- **RS0 동등성(캐시 1,218 · SEC 1회 수집 뒤 0콜)**: 종목마다 submissions(+페이지) · 보충 대상 인스턴스를 받아 저장 → 보충 전/후 스윕(`SecEdgarFacts.build`) → 바뀐 출력 칸을 **원인 갈래로 전수 귀속**: (a) 보충 공시의 새 기간 (b) 옛 라벨의 비교열 판 (c) HOLE 해 복원 (d) 그 밖 → (d)는 하나씩 연다 · **보충 대상 0인 종목은 출력이 바이트까지 같다**(대조군) · 같은 정밀도 다른 값 중복 수 · dei만 정정은 출력 무변화.
- **RS0' 제거 대조(0콜)**: companyfacts에 있는 공시(AAPL·MSFT·JPM + 비교열 판 종목 GOOGL·HIG + **dei 있고 us-gaap만 빠진 꼴**(GEHC 10-K FY2022 실물))에서 그 공시의 us-gaap를 지운 트리 + 보충 = 원래 트리 출력 · 지운 트리(보충 없음)는 그 분기가 없다(원래 결함 재현).
- **RS1(사본 · FR)**: 80 + SPGI + AOS에 FR(새 코드) → 6월 분기 · HOLE 해 생김 · 대조군 AAPL·MSFT·JPM 신규·정정·채움·교정 0 · 그 뒤 화해 → 바뀐 칸(최신일 · 창).
- **RS2 실패 계약**: BE /A 인스턴스 404 주입 → 그 종목 실패 · 판 삭제 0 / **대조군**: 같은 주입을 「그 공시만 건너뜀」 계약으로 → 삭제 > 0(`FinancialWriter:283-349`).
- **RS3 단위**: 보충 술어(`isXBRL` · us-gaap 0 · 창) · 페이지 따라가기 · 인스턴스 찾기(인라인 · 비인라인 — 실물 하나를 RS0에서 찾아 픽스처로) · 네임스페이스 · `unitRef` · CIK · 정밀도 · 중복 안 더하기 · 저장·재사용(2회째 0콜) · 실패 계약.

## 2부 — 재무 동기 단계(B · (나)) 개요(1부 뒤 다시 짓고 반증)
- 표지 = 종목별 FR 성공 시각(그 FR이 submissions를 받은 시각) · **미국 공시 신호 = submissions `recent`의 정기공시 `acceptanceDateTime` > 표지**(수렴: FR 성공이 표지를 그 시각으로 올림 · `recent`는 최근 1년 이상을 덮음 — 실측 ABT 2018~ · AAPL 2015~ · JPM 1년 26,325건) · **국내 = DART `list.json` 정기보고서 `rcept_dt` > 표지 날짜**(상태 기반 · 기간 매핑 불필요) · 편입 신호(F6) · 국내 FSR 후속.
- 미국 초기 표지 = NULL(보충 가능한 어댑터로 동기된 적 없음 — 정직한 초기값) → 첫 주에 상한으로 나눠 전원 → (가)를 10-30 전에 적용.
- 시각: 시작 컷오프(14:40 넘으면 이월) · 실행 상한 · 15:30 문장들 다시 씀.
- 따로 뺀 것: 배치 행 생존 판정(풀 `minimum-idle: 0` · ADR-0163 종료 대기 — 별도 루프 ⑥) · 후보 쪽 재무 낡음(별도 축) · 국내 동일값 정정 행 누적(`FinancialWriter:492-521` · 59 중 30 — 계획 밖 보고).

## 1부 구현 진행 — 인스턴스 리더(2026-10-06 03시 · worktree `wt-sync` · 초안 사본 `draft/` · main 미반영)
- `XbrlInstanceReader`(StAX · DTD·외부 엔티티 끔 · 네임스페이스 URI로 us-gaap·dei · `unitRef` 있는 것 · 축 없음 · 컨텍스트 CIK 단언 · 정밀도 고르기 · 같은 정밀도 다른 값 MALFORMED · 합칠 때 같은 accn·기간 슬롯 안 더함) · 단위 시험 5/5(러너 XML로 확인).
- **실물 대조(0콜 · 저장 원자료 · Java)**: AAPL `…26-000020` 리더 팩트 283 = companyfacts 283 · **283/283** · JPM `…26-054343` 788 중 787 · MSFT `…26-323660` 601 중 597 + 다름 1 — 어긋남 다섯은 전부 companyfacts 쪽 정규화(JPM `PaymentsToAcquireBusinessesGross` · MSFT `GoodwillImpairmentLoss` 셋 = 유량을 시점으로 실은 것 · 인스턴스엔 그 날짜 키 없음 / MSFT dei 액면가 0.00000625 → companyfacts 6e-06 반올림) · **셋 다 `SecEdgarColumn` 매핑 밖**(grep 0) — 우리 출력에 닿지 않는다 · 결손 종목 ABT·KO·NEE·DUK·CLX 인스턴스는 오류 없이 읽힘(311~620팩트 · 같은 정밀도 충돌 0).

## v2 1부 반증 판정 — 계획 밖 렌즈(2026-10-06 03시 · 「수정 후 진행」 · `refute2/`)
1. **[순위] 10-30은 [A56] 결함이 만든 날짜** — 마지막 전량 FR #683(09-28 01:14) 기준 연체일은 10-26(월) · #710(10-03 21:17 · IOVA 1종목 MANUAL)이 폴백(`JobFreshnessPolicy:213`)으로 10-30(금)으로 밀었다(DB로 확인) · 그 사이 손 FR이 또 돌면 또 밀린다 · 「시계를 안 미는 길을 기다린다」는 결함을 피해 가는 처방 · v1이 적은 「[A56] 먼저」를 v2가 근거 없이 뺐다 → **1부 앞에 [A56] 전진 수정**(좁힌 손 실행을 폴백에서 안 셈) → 1부 배포 직후 `--tickers=<80+SPGI+AOS>` FR로 바로 적용해도 정기 FR이 안 밀린다 · 이득 상한 (10-30 − 적용일) × 4,035칸.
2. **「값이 바뀌는 길 둘」은 거짓(오류 2)** — `FinancialCollector` 호출은 `InitialLoadJob` · `FinancialRefillJob` · 그 위 다섯 갈래(`BootstrapRunner` 손 FR·initial-load(`04-refill.sh`) · 리밸런싱 크론과 화면 버튼 → `MembershipGapFiller:308-316` · `PipelineScheduler:340` · 관문 재적재) · 개황(`SecEdgarCompanyProfileAdapter`)·미국 거버넌스(`AlpacaDividendAdapter`)는 `SecEdgarFacts`를 안 탐 · 「무해한 선행 배포」라는 이름이 틀림(1부는 FR마다 값을 바꾼다).
3. **10-30 FR의 사정거리 과장** — 정기 FR은 5-인자 `runAndReport`(insertEarlier=false · `PipelineScheduler:340`) → `FinancialWriter:425-477`이 기존 라벨의 최신 판보다 이른 소스 판을 건너뜀 → 새 라벨(80의 6월 분기 · DB 0행인 SPGI·AOS FY2024 · GEHC FY2022)은 들어가고 이미 있는 라벨의 원 판은 관문의 `--insert-earlier-vintages`에서만(건수 재지 않음) → 결정 5② 고침 · RS1은 정기 FR과 같은 플래그 · RS0 (b)를 「늦은 판」·「이른 판」으로 가름.
4. **보충 목록이 P2의 8-K 수용과 엮임(회귀 위험)** — 보충 대상을 `PERIODIC_FORMS`로 고르는데 `reload-gate.md:44`가 8-K 수용의 뿌리로 이 집합을 짚음 · 0콜(83 CIK · 2018 뒤 `recent` · `q03_8k_fires.py`): 정기공시 발화 87 · 8-K 중 XBRL 7,032/8,315 · 그중 companyfacts us-gaap 0이 7,012(CIK당 중앙 77) → 이 집합에 8-K를 더하면 보충 대상 약 81배 → **보충 폼 집합을 따로 두고 독립성을 시험으로 단언** · 관문 재고의 수(S0 1,325라벨 · [S] · P3a 584·362 · ADR-0151 114행)는 보충 없는 트리에서 잰 것 → 1부 뒤 다시 잴 대상(소비자 목록에).
5. **스윕 원천(오류 3)** — 저장 표가 덮는 범위: 현 편입은 적용 뒤 · 과거 편입 208은 관문 때만 · 후보는 영영 → 표를 스윕 캐시로 쓰면 관문 2단계에서 과거 편입 스윕과 재적재가 갈림 · 하네스는 운영 DB에 못 씀(ADR-0071 펜스) → `.factcache`와 같은 날 모은 **파일 캐시**를 스윕 원천으로 · 합치기는 (companyfacts, 저장분)만의 순수 함수.
6. **B 기록 틀림** — 「10-30 FR이 덮는다」는 그날 현 편입에만 · FR 대상은 `activeUniverseMemberIds` · 꼬리는 재무 0행만(`StockRegistrar` `no_financial`) → 10-31 리밸런싱부터 재무가 낡은(0행 아닌) 신규 편입이 다시 쌓임(미국 신규 09-04~10-02에 31·2·5·9·10·13) · 10-30 FR 뒤 첫 리밸런싱은 **10-31(토)** · 최근 4주 평일 13:30 발화 20/21.
7. ADR-0135 펜스 고침은 옳게 뺀다(새 조건이 빼는 행은 복원 순위 `published_at ≤ asOf`에 닿을 수 없음) · 다만 적용 뒤 80 + SPGI·AOS 행이 관문 복원까지 계속 붉어 지금 52행과 한 수로 합쳐짐 → 「알려진 대기」를 따로 표시.
- 재봤더니 문제 없음: ADR-0162 하류((가) 79종목 6,124행 `share_basis_status` 전부 NULL · 미국 전체 NULL 아닌 종목 2) · ADR-0071 펜스(국내 전용) · V63은 `00-precheck.sh`를 새로 안 깸(이미 V56~V62가 `8198fe0..HEAD`) · 10-30 시각 모형(US FR 16분 + 보충 약 4,100콜 13.7분 + KR FR 23.4분 → 약 15:08 · 화해까지 약 15:21 · DART FR KR 약 14,000/18,000).
- **내가 잼(03시 · 코드)**: `JobFreshnessPolicy.lastSuccessAt`(`:205-221`)의 폴백은 `triggered_by <> 'GAP_FILL'`인 SUCCESS·PARTIAL_SUCCESS의 `max(started_at)` — 범위(티커·유니버스 좁힘)를 안 본다 · `batch_execution`에 실행 인자가 없어 이력의 범위는 target_count로만 짐작된다(FR 전량 기본 경로 = 수집 시장 활성 편입 1,350인데 이력의 MANUAL FR은 1,000·200·150 유니버스별뿐).

## v2 1부 반증 판정 — 계획 안 렌즈(2026-10-06 03시 · 「수정 후 진행」 · `rebut2/`)
- 선다(0콜): 매핑 태그 68만 대조하면 리더 = companyfacts — AAPL 79/79 · JPM 65/65 · MSFT 68/68(`ident_mapped.py`) · 중복 키는 companyfacts도 가장 정밀한 쪽(MSFT `UnrecognizedTaxBenefits` 3/3 · `dup_choice.py`) — 인라인 10-Q·10-K 모양에서 「나중에 채우면」 전환 때 값이 안 갈림 · 인스턴스 8개 같은 정밀도 다른 값 0 · 컨텍스트 식별자 전부 자기 CIK · 「같은 키면 안 더함」은 us-gaap엔 해당 없음(보충은 그 accn us-gaap 0일 때만 · 키에 accn).
1. **[오류 4] 실패 계약의 근거가 꼬리 `initial-load`에 없다** — `FinancialWriter:186`은 `APPEND_ONLY`에서 `retractStaleVintages`를 안 부름 → 새 편입 영구 실패면 main의 「공시 하나 빠진 재무」가 「재무 0행」이 됨 → `StockRegistrar:280-288` 둘째 갈래·`MembershipGapFiller:311-316`이 매주 다시 돌려도 같은 실패 · 15:30 매일 붉힘(코드로 읽음) · 꼬리 모집단 08-31 뒤 미국 편입 92 중 캐시 밖 2(IOVA·KOD) — RS0 「전 모집단 1,218」 밖이라 결정 5①은 거짓 → **쓰기 모드마다 계약**: `FILL_MISSING` = 종목 실패 · `APPEND_ONLY` = companyfacts 몫만 쓰고 실패 기록(main과 같은 출력) · RS2에 그 갈래.
2. **RS2 대조군이 원리적으로 못 붉음** — BE `-050247`·`-050325` 둘 다 companyfacts·DB에 없고 BE 최신 행 2026/Q1 · 회수는 출력에 있는 라벨의 keep 밖 행만(`:313-328`) → 「건너뜀」으로 돌려도 지울 행 없음 → 두 단계: FR#1 보충 성공 → 판 저장 → FR#2 404 주입 · 그 판이 안 접혀야(`SecEdgarFacts:899-902`) · HOLE(SPGI 2024)은 건너뛰면 출력에서 사라져 회수가 안 닿음 → 대조군은 RS0 (b) 종목 · 선택 계약 쪽 단언 = 「FAILED · 신규·정정·교정 0 · 실패 기록 · 재시도 성공 시 FR#1 상태와 같음」.
3. **저장 내용이 「규칙 적용 뒤」라 판 표시 필요** — 리더 규칙이 바뀌면 옛 규칙 저장분과 새 규칙 신규분이 섞임(관문 규칙이 막는 형태) · 「0건도 평생 저장」은 잘못 고른 인스턴스·리더 결함을 영구화 → `reader_version` 컬럼(다르면 다시 읽음) 또는 축 없는 원 팩트를 `decimals`째 저장하고 고르기는 읽을 때 · 읽은 파일명·크기 저장 · 불변식: 원본 10-K·10-Q의 us-gaap 0은 MALFORMED로 저장 안 함 · 83 표본 companyfacts에 dei만 있는 6건(GRMN·INCY·WES 10-K/A · DUK·KO 10-Q/A · GEHC 원본 10-K) 중 GEHC는 인스턴스에 us-gaap이 있어야 함.
4. **창 술어와 라벨 창 어긋남(기제 있음 · 지금 적중 0)** — 라벨 창은 회계연도 ≥ `from`(`SecEdgarFacts:252`) · 술어는 `filingDate ≥ from-01-01` → 놓침 from=2018에서 3(VFC 2017-08·11 · CLX 2017-11) · 2019에서 5(QRVO) — 전부 companyfacts에 있어 지금 0 · 넘침 40(출력에 안 쓰이는데 실패 표면) → `reportDate`·`fiscalYearEnd`로 회계연도(`edge.py`).
5. **RS1이 차분 오라클(a1→b→a2)을 버림** — 마지막 전량 FR 09-28(#683) 뒤 companyfacts 변화가 새 코드 FR에 같이 실림 → 보충 때문인지 못 가름 · 스케줄러는 이른 판 안 넣음(`FinancialWriter:189`) → 기대값은 스윕이 아니라 `EarlierVintageReplayHarnessTest`로 · 2회째 FR(저장분 · 0콜) 멱등성 단언.
6. **[오류 1] RS0은 귀속만 하고 반증 안 함** — (a)~(c)는 정의상 통과 · 값 대조는 RS0'뿐 · 83 표본 보충 대상 87 = 2026 인라인 10-Q 78 · 10-K/A 3 · 10-Q/A 2 · BE /A 1 · GEHC 1 · CLX 1 · SPGI 1 · 정정 쌍·10-K/A 모양에 제거 대조 없음 → companyfacts에 있는 같은 모양 10-Q/A 5 · 10-K/A 4가 후보 · GEHC는 companyfacts에 원래 행이 없어 제거 대조도 오라클도 아님 · (d) 판정 기준 없음 → 기계적 분류 규칙을 대조 전 커밋 · 실행 조건: `from` = FR과 같은 asOf−8(스윕 기본 2015 `SecEdgarFactsSweepTest:83`) · companyfacts를 submissions와 같은 날 새로(캐시 09-15·16 1,214 · 09-27 4 → 그 뒤 제출분이 거짓 보충 대상).
7. **페이지가 실패 표면** — 종목당 콜 1 → 1 + submissions + 페이지(JPM 52) + 대상×2 · FR에 재시도 없음(`AdminJobLauncher:266-267`) → 보충 대상 0 종목도 5xx 한 번이면 그 달 FR을 잃음 → TRANSIENT 한정 재시도 · RS0에서 콜당 실패율 · 감시 「보충 실패 종목」.
8. **[오류 2] 소비자** — `SecEdgarFactsReplayTest` · AOS 재현 javadoc(`SecEdgarFacts:563-567`) · 「companyfacts가 판 이력 전부」 문장 두 곳(`FinancialFetch:31` · 어댑터 `:134-135`) — 80종목이 반례 · ADR-0135 펜스는 `created_at`으로 세고 미국 복원 상한 08-31(1,684행) → 80종목 행이 펜스를 붉힘 · 그 처방(universe-restore + 파생층 백필)이 결정 5② 사정거리에 없음 · 전환 감시는 「교정 수」 대신 저장분과 companyfacts에 둘 다 있는 accn의 매핑 태그 직접 대조.

# 계획 v3 (2026-10-06 03시 30분)
## 0부 — [A56] 범위 표지(1부 앞 · 별도 배포 가능)
- **뿌리(쟀다)**: `JobFreshnessPolicy.lastSuccessAt`의 폴백(`:205-221`)이 GAP_FILL만 빼고 MANUAL·ADMIN 성공을 범위와 무관하게 센다 — #710(IOVA 1종목)이 FR 연체일을 10-26 → 10-30으로 밀었다 · 스케줄러 성공 0인 잡(FR·FSR·G·시장 달력)만 폴백을 탄다.
- **결정**: `batch_execution.scope`(`FULL`·`NARROW` · NULL = 모름 · V63) — 시작 호출 25곳 중 **티커·유니버스로 좁힐 수 있는 잡**이 시작 때 스스로 찍는다(좁힘을 아는 것은 잡뿐) · 폴백은 `scope = 'NARROW'`를 뺀다(NULL은 센다 — 이력 보존) · **이력은 V63 안에서 재구성**: FR·FSR·G의 MANUAL·ADMIN 행 중 `target_count` < 그 시각 열린 활성 편입 수(수집 시장 합 · `created_at ≤ started_at`) → `NARROW`, 같으면 `FULL`(재구성 방식은 B v5·v6 반증이 검증 · #683 1,000 < 1,350 → NARROW).
- **예측(쟀다 · 운영 SELECT · 재구성 = `target_count` ≥ 그 시각 열린 활성 편입 수)**: FR — FULL 1(#30 08-10 350/350 · 국내만이던 때) · NARROW 88(마지막 #710 IOVA 1/1,350 · #683 1,000/1,350 · #658 150/1,350) → 폴백 시계 10-03 → **08-10** → **즉시 연체**(지금 연체일 10-29~30) / FSR — FULL 9(마지막 #415 09-14 1,350 · 다만 FY만) · NARROW 29(마지막 #664 09-23 129) → 시계 09-23 → **09-14** → 연체 10-19 → **10-12(월)** / G — FULL 9(마지막 #632 09-20 15:18) · NARROW 9(#633 09-20 16:14 337) → 시계 거의 그대로 → 연체 **10-16(금)**. → 0부를 화 10-06 15:40 뒤 배포하면 **수 10-07 14:15에 FR이 SCHEDULER 전량으로 돈다**(수요일 배정 FS는 연체 아님 → 가장 오래 밀린 FR) — 그 FR은 1부 전이라 80종목은 그대로 · 시계가 10-07에서 정당하게 시작(다음 11-02) · 1부는 그 뒤 좁힌 손 FR(NARROW)로 시계를 안 밀고 적용.
- 주의(재구성의 한계): FSR #415는 대상은 전량이지만 기간이 FY만(`보고서=[FY]`) — 정기 FS(분기 포함)와 같은 일이 아니다 · 범위 표지는 「대상」만 본다(기간은 보지 않는다) — 사정거리로 적는다.
- **반증·드라이런**: 두 렌즈 · 사본에서 V63 → `JobFreshnessPolicy.stale`·`daysSinceLastSuccess`·`heavyJobDueToday`를 요일별로 · 15:30 배너 변화 · 손 좁힌 실행 뒤 시계 불변(대조군 = 고치기 전 코드에서 같은 실행이 시계를 민다).

## 1부 — (가) 공시 보충 어댑터 v3 (v2 두 렌즈 반영 · 이름: 「무해한 선행 배포」가 아니라 **FR마다 값을 바꾸는 어댑터 변경 · 관문 규칙 밖(근거 v2 결정 5)**)
1. 보충 폼 집합 = **정기 원본·정정(10-K · 10-Q · /A · 20-F · 40-F)만 · `PERIODIC_FORMS`와 따로 두고 독립을 시험으로 단언**(8-K 수용 P2와 안 엮임).
2. 창 = 라벨 창과 같게 — submissions `reportDate`·`fiscalYearEnd`로 회계연도 ≥ `from`.
3. 저장 표 `sec_filing_supplement`: accn · cik · form · filed · 파일명 · 크기 · `reader_version` · 축 없는 원 팩트(`decimals`째 · 고르기는 읽을 때) · us-gaap 수 — **원본 10-K·10-Q의 us-gaap 0은 MALFORMED · 저장 안 함** · 판이 다르면 다시 읽음.
4. 실패 계약 = **쓰기 모드마다**: `FILL_MISSING` → 종목 실패(아무것도 안 씀) · `APPEND_ONLY`(꼬리) → companyfacts 몫만 쓰고 실패 기록 · TRANSIENT 한정 재시도(2회) · 감시 「보충 실패 종목」.
5. 적용: **0부 배포 뒤** 1부 배포 → 결손 종목(80 + SPGI·AOS + RS0이 찾은 것)에 좁힌 손 FR(`NARROW` → 시계 안 밂) · 정기 FR은 insertEarlier=false라 **새 라벨만** 들인다(기존 라벨의 이른 판은 관문의 `--insert-earlier-vintages`) · 과거 편입·후보는 사정거리 밖(재고 행).
6. 「companyfacts가 판 이력 전부」 문장 두 곳(`FinancialFetch:31` · 어댑터 `:134-135`) 고침 · ADR-0135 펜스 = `published_at ≤ max(RESTORED effective_to)` + 「알려진 대기」(보충 적용으로 생긴 행) 따로 · 그 처방(복원 재실행 · 파생층 백필)은 관문 몫으로 명시 · 관문 재고의 수(S0·[S]·P3a·ADR-0151)는 1부 뒤 다시 잼(소비자).
7. 스윕 원천 = **파일 캐시**(companyfacts · submissions · 인스턴스 · 같은 날 수집) · 합치기는 (companyfacts, 저장분)만의 순수 함수 · 소비자 `SecEdgarFactsSweepTest` · `EarlierVintageReplayHarnessTest` · `SecEdgarFactsReplayTest` · AOS javadoc.
### 1부 드라이런 규칙 v3(대조 전 커밋)
- **RS0 반증형(0콜 · 파일 캐시 · `from` = asOf−8 · companyfacts·submissions 같은 날 새로)**: (i) 제거 대조 — companyfacts에 있는 공시에서 그 accn us-gaap 제거 + 보충 = 원래 출력 · 모양마다(인라인 10-Q · 10-K · 10-Q/A 5 · 10-K/A 4 · 비교열 판 GOOGL·HIG · 비인라인 하나) (ii) 전 모집단 보충 전/후 출력 차이를 **대조 전 커밋한 기계 규칙**으로 분류(새 기간 · 늦은 판 · 이른 판 · HOLE 해 · 그 밖) · 「그 밖」 0이 합격 · 보충 0 종목 바이트 같음 (iii) 콜당 실패율 · 같은 정밀도 충돌 수 · GEHC us-gaap 존재.
- **RS1 차분(사본)**: a1(main FR) → b(새 코드 FR · 정기와 같은 플래그) → a2(main FR) · a1 = a2 종목만 · 기대값 = `EarlierVintageReplayHarnessTest` · b 2회째(저장분 · 0콜) 멱등.
- **RS2 실패 계약**: 두 단계(FR#1 성공 → FR#2 404 주입 · RS0 (b) 종목 · 안 접히는 판) — FILL_MISSING 단언 「FAILED · 신규·정정·교정 0 · 실패 기록 · 재시도 성공 시 FR#1과 같음」 · 대조군 「건너뜀」 계약 → 삭제 > 0 · APPEND_ONLY 갈래(companyfacts 몫 씀 · 실패 기록).
- **RS3 단위**: 폼 집합 독립 · 창(reportDate·fiscalYearEnd) · 페이지 · 인스턴스 찾기(인라인·비인라인) · 저장 판 · 원본 us-gaap 0 MALFORMED · 재시도 · 쓰기 모드별 계약.

## v3 반증 판정 — 계획 안 렌즈(2026-10-06 04시 · 0부 「수정 후 진행」 · 1부 「수정 후 진행」 · `rebut3/`)
**0부**
1. **[오류 1·3] 범위를 「대상」 한 축만** — G #119~#123(08-23)은 350/350인데 각 38초 · `written_row_count` = 표 하나의 행 수(DIVIDEND 5,208 · SHARE_TOTALS 1,283 · OUTSIDE_DIRECTOR 350 · CONTROL_CHANGE 1,712 · AUDIT_OPINION 948 — `governance_report_row.created_at` 귀속 · `g_endpoint_attrib.txt`) → 표 하나짜리를 FULL로 셈 · #234 5,864/5,864 · #632 2,805/2,807이 DIVIDEND(정황) · #626은 8표 전부 · FSR #415는 `보고서=[FY]`인데 정기는 전 분기(`PipelineScheduler:335`) — FY만 받은 실행이 시계를 미는 것은 [A56]과 같은 꼴 · 「기간은 안 본다 — 사정거리로」는 한계로 수정을 갈음한 것 → **잡이 스스로 「정기 실행과 같은 인자인가」**(대상 ∧ `--financial-years` ∧ `--quarters`/`--all-quarters` ∧ `--governance-endpoints`/`-span`/`-year` · `BootstrapRunner:313-324,362-366`) · 이력은 note의 `연도=`·`보고서=` · G 행 귀속으로 재구성 · 증명 안 되면 NARROW · **예측 표 뒤집힘**: FSR의 전 축 FULL은 #190·#191(08-30) → 이미 연체 → 수 10-07에 배정 요일이 먼저라(`:296`) **FSR** · FR은 목 10-08 · G는 #626이면 10-16 · 계획 자기 분류(#632)라도 10-16 14:15 시점 25일 22시간 → `toDays` 25 → 연체 아님 → 10-19(월).
2. **NULL을 세면 [A56]이 남음** — 찍는 자리를 이름으로 안 적음 · 안 찍히는 자리 `startRetry`(`BatchExecutionRecorder:61-68` · ADMIN 박힘 · 실패분만) · 0건 즉시 finish 7곳 · 새 잡 → **`start`·`startRetry`의 scope를 오버로드 없는 필수 인자**(컴파일러가 24+1곳 강제) · `startRetry` = NARROW · V63 뒤 행 NOT NULL CHECK · 같은 술어를 SCHEDULER 갈래에도(「스케줄러는 전량」은 호출자 규율뿐 · javadoc `:43-45`에 initial-load SCHEDULER target 2 실례).
3. **0부가 실제로 바꾸는 것은 재구성 하나** — SCHEDULER 성공이 생기면 폴백을 안 탐(`:146-147`) → 10-07·08 뒤 손 FR은 scope와 무관하게 시계를 못 민다 → **0부 배포 = 정기 전량 FR(과 FSR)을 3주 앞당기는 일** → 사본에서 그날 `heavyJobDueToday`가 고를 잡을 실제로 돌려 무효화 수와 14:20 화해 종료 시각을 잰다 · 계획 밖: FSR 기본 대상이 미국 1,000 포함 · #410·#415에서 1,000건 전부 `US NOT_FOUND` → 첫 정기 FSR은 PARTIAL + 실패 1,000행 · **V63의 이력 UPDATE는 운영 행 수정 → 승인 항목**.
4. 소비자: `AdminOverviewView.vue:290-292`(「수동 실행까지 함께 셉니다」) · `types.ts:854`(`scheduledOnly`) · javadoc `:34-36`·`:47-48` · `JobFreshnessPolicyTest`.
5. 드라이런 대조군이 티커 축만(확증) → FSR `--all-quarters` 없이 · G `--governance-endpoints=DIVIDEND` — 옛 코드에선 시계를 밀고 새 코드에선 안 민다.
**1부**
6. **「원본 us-gaap 0 = MALFORMED」 실물 반례 후보** — 원본인데 companyfacts에 dei만 있는 창 안 공시 6(BTU 10-Q 2018 둘 · GEHC 10-K 2023-02 · MAIN 10-Q 2022-08 · OBDC 10-Q 2022-08 · OTF 10-K 2023-03) · GEHC 현 편입 · 인스턴스에 축 없는 us-gaap이 나오는지는 재지 않음 · 0이면 GEHC 매 FR 종목 실패(main보다 후퇴 · 결정적이라 재시도로 안 풀림) · 리더의 다른 결정적 MALFORMED(`forever` 컨텍스트 · CIK 불일치가 인스턴스 전체를 던짐) → RS0 「MALFORMED 종목 수」 합격선 0 또는 구성원마다 판정.
7. **저장 표가 있으니 FILL_MISSING의 「종목 실패」는 과잉** — 회수(`FinancialWriter:283-349`)가 지울 수 있는 것은 전에 쓴 판뿐 · 전에 쓴 보충 판은 저장 표에서 다시 나오고 submissions 없이도 쓸 수 있다 → **아직 저장 안 된 공시의 보충 실패 = 「그 공시만 빠진 출력 = main + 기존 저장분」 + 실패 기록(회수 0)** · 종목 실패는 저장분을 못 쓸 때만(판이 달라 다시 읽다 실패 · 저장 자체 실패).
8. RS2는 본체에 안 닿음 — 저장분이 있으면 FR#2는 인스턴스를 안 받음 → 주입 자리 = 「저장 행의 판을 올린 뒤 다시 읽기에서 404」 · 대조군 「건너뜀」은 그 자리에서 삭제 > 0.
9. RS1 a2는 중립 관측자가 아님 — a2(main FR)가 b가 기존 라벨에 넣은 늦은 판을 회수하고 새 라벨은 안 건드림(`:296`) → 「a1 = a2」로 고르면 80종목 6월 분기가 통째로 빠짐 → a2를 a1 직후 사본에서 갈라 돌리거나 a1·b 시점 companyfacts 해시로 drift를 가름.
10. 창(§2)이 라벨 회계연도의 두 번째 정본 — 라벨 회계연도는 팩트로(`SecEdgarFacts:243-256`) · submissions `reportDate` + 현재 `fiscalYearEnd`로 따로 매기면 회계연도를 바꾼 종목에서 갈림(캐시에 10-KT/10-QT 낸 종목 14 · FERG 2026-02 등) → 창 시작 한 해 앞까지 받고 자르는 것은 `build()`에.
11. 술어 밖 부분 적재 — 원본인데 us-gaap 1~29개뿐인 공시 9 · CEG 10-K 2022-02는 6개 · CEG 현 편입인데 DB에 FY2020·2021 0행(같은 꼴인지는 재지 않음) · IFRS 20-F·40-F 약 32건은 술어에 걸려도 0으로 저장(해 없음) · DSGX 40-F 셋은 dei만이라 보충이 FY 셋을 살릴 수 있음.

## 실측 — companyfacts에 dei만 있는 원본 공시의 인스턴스(2026-10-06 04시 · SEC 19콜 · `ga/out3` · Java 리더)
- GEHC 10-K FY2022(`0001932393-23-000025` · 인라인 · **19KB**): 컨텍스트 3 · 축 없음 · **us-gaap 팩트 자체가 0**(표지만) → 원장이 그 공시에 구조화 재무를 안 준다 — **진짜 한계**(XBRL 경로로는 · HTML만).
- BTU 10-Q 2018 Q1·Q3(`…18-000016`·`…18-000043` · **비인라인** — 인스턴스 찾기 규칙 실물 확인: `btu-20180331.xml`) · 2.6MB·4.7MB · 컨텍스트 축 없음 2 · 축 745 · **재무 값이 전부 `us-gaap:StatementScenarioAxis` 아래**(443) → 축 없는 us-gaap 0 — companyfacts도 0.
- CEG 10-K FY2021(`0001868275-22-000020` · 4.9MB · 공동 등록) · us-gaap 대부분 `dei:LegalEntityAxis` 아래(659) · 축 없는 수치 6 = companyfacts 6 · CEG는 DB에 FY2020·2021 0행.
- MAIN 10-Q 2022-08(8KB) · OBDC 10-Q 2022-08 · OTF 10-K FY2022(17KB) — 축 없는 us-gaap 0.
- **결론**: (i) 「원본 10-K·10-Q의 us-gaap 0 = MALFORMED」는 일곱 실물 전부를 영구 실패로 만든다 — **뺀다**(0은 「원장이 축 없는 수치를 안 줌」의 사실로 저장 · 감시로 셈). (ii) **BTU·CEG 꼴 = 「재무가 축(시나리오·법인)에만 실림」은 별도 축**(가짜 한계 후보 — 값은 원장에 있다 · companyfacts·축 없는 리더 둘 다 버린다) → 재고에 올린다(1부 밖 — 축 붙은 팩트를 받는 규칙은 ADR-0099 ② 클래스·법인 축과 같은 질문).

## v3 반증 판정 — 계획 밖 렌즈(2026-10-06 05시 · 0부 「수정 후 진행 — A+A2와 같은 jar는 안 된다」 · 1부 「수정 후 진행」 · `refute3/q01~q05`)
1. **관문 원칙과 충돌 안 함 — 조건 둘**: `git log --since=09-21` 재무 경로 커밋 18(q05) · 미국 #683 뒤는 faeb2d77 하나(설정 뒤 · 꺼짐) · 국내 FR 뒤 fbe8dec6 하나(국내는 `Set.of()`라 무동작 `DartFinancialAdapter:287-296`) · FSR 경로 2c6dc51f·29ae6241은 설정 밖(10-04 규칙 전) → 첫 정기 FSR이 KOSDAQ 21종목(150 − #664의 129)에 처음 적용 · 「정기 FR = 유지」는 에이전트 판단(`reload-gate.md:10` · `progress.md:84-88`) · ADR-0166 §규칙 「유지이지 통로가 아니다」가 조건 → 10-07 FR은 유지 · 남는 것: LNT 2018·2019 Q4 판(`reload-gate.md:56` 「?」 8년 창 무변화 게이트)이 관문이 재기 전에 바뀜 · 1부 뒤 다음 정기 FR(11-02~03)이 설정 밖 어댑터 변경을 1,000종목에 실어 나르는 통로 → ADR-0173이 그날을 명시 · `progress.md:308-312` MUST NOT은 사유(즉시 연체 → 상한 백필)가 ADR-0166으로 사라짐 · 그 줄의 「전량 정의」에 v3은 답 안 함(09-21 #643+#645+#646은 41분 안에 1,350을 덮었는데 셋 다 NARROW).
2. **[순위·오류 3] A+A2와 같은 jar는 안 된다** — ① 10-07 FR은 A2의 `SecTickerCikCache`를 타는 첫 FR(첫 적재 실패는 던짐 `:174-198`) · A2 드라이런은 명부 경로만 ② 미국 전원 실패 + 국내 성공 = PARTIAL_SUCCESS(`BatchExecutionRecorder:175-176`) → SCHEDULER 성공으로 26일 시계 시작 · 0부 FULL은 「요청」 기준이라 이것도 FULL ③ V63(운영 이력 UPDATE)이 기동을 세우면 A+A2(10-10 20:00 마감)가 같이 막힘 · 사용자 승인도 A+A2 범위 → **10-07 15:40 뒤 따로 배포** · FR은 배정일 목 10-08(10-10 전) · scope를 SCHEDULER 행까지 넓히면 「시장별 달성 커버리지」로.
3. 그날: DART FR 국내 12,600 + 14:00 수집 · 라벨 고치면 10-07 FSR 실측 15,844(#647+#648) · 상한 18,000까지 1,456 이하 · `PipelineScheduler:247-250` 「최대 10,970」 낡음 · 시각 최악 약 15:17(FSR 날 약 15:20) — 15:30 전.
4. **ADR-0135 펜스 순서** — 미국 52행 전부 `published_at ≤ 08-31`(q03) · 상한 수정(1부)보다 FR이 먼저 돌면 펜스가 부풂 → **상한 수정을 0부로**.
5. [오류 2] 날짜 소비자 — `progress.md:55`(10-19 G · 10-20 FS · 10-30 FR) · `:218` · `:239` · `reload-gate.md:55`(「배포 즉시 연체 0」) · `CIK-REP-PLAN.md:291`(10-21) · 시즌 FR도 10-30·11-25 → 11-02~03·11-30.
6. **[1부 회귀·오류 4] 6-K를 보충 폼에서 뺐다** — 폼을 나눈 사유는 8-K인데 6-K까지 · 리더는 6-K를 받음(`SecEdgarFacts:69-71,82`) · 83 CIK 캐시 6-K 46건 중 XBRL 0(q01) · 현 편입 FPI 24 중 6-K 행 있는 것 8 · 2025 뒤 분기 행 0인 것 12(CAMT CHKP ESLT FSV GLBE MGA NVMI QGEN SOBO STM TSEM WSE) → 6-K·6-K/A를 넣거나 RS0에서 FPI 24 누락을 잼.
7. 기타: PLAN 절 시각이 커밋 시각보다 앞섬(**내 기록 결함 — 절 제목의 시각은 대략이었다 · 커밋 시각이 정본**) · V63을 0부가 가져가 1부는 V64 · 저장 표 크기(공시당 283~788팩트 · DB 10,094MB)는 무시할 만(바이트 재지 않음).

# 계획 v4
## 0부 v4 — [A56] 범위 표지 (**A+A2와 다른 배포 · 10-07 15:40 뒤**)
1. **범위 = 「정기 실행과 같은 요청」 ∧ 「달성」** — 요청: FR(티커 없음 ∧ 유니버스 없음 ∧ 연수 = 기본) · FSR(티커 없음 ∧ `allQuarters` ∧ 연수 = 기본) · G(엔드포인트 전부 ∧ span·year 기본) · 그 밖 잡(티커·유니버스 없음). 달성: 끝날 때 **수집 시장마다 성공 종목이 그 시장 대상의 과반**이 아니면 `NARROW`로 내린다(시장 하나가 통째로 실패한 실행 — #415 FSR 미국 1,000 NOT_FOUND · 「미국 전원 실패 + 국내 성공」 — 을 전량으로 안 셈 · 과반은 「종목 단위 실패」와 「시장 단위 실패」의 경계로 고른 것이지 결론에 맞춘 수가 아니다 — 이력의 정상 전량 실행 실패율을 RD0에서 재서 경계가 그 분포와 먼지 확인).
2. `start`·`startRetry`의 scope는 **필수 인자**(오버로드 없음 — 컴파일러가 24+1곳 강제) · `startRetry` = NARROW · V63 뒤 `scope NOT NULL`(이력은 재구성으로 채움) · **SCHEDULER 갈래에도 같은 술어**.
3. **이력 재구성(V63 안)**: 요청 축은 note(`연도=`·`보고서=`)와 G 행 귀속(`governance_report_row.created_at` ∈ 실행 창 · 표 전부를 건드렸나)으로 · 달성 축은 target_count·failure_count와 시장별 편입 수로 · **증명 안 되면 NARROW** · 이것은 운영 `batch_execution` 행 UPDATE → **사용자 승인 항목**(배포 확인과 함께).
4. **FSR 기본 대상 = 전체 재무제표 소스가 있는 시장(국내)만**(`FullStatementRefillJob:192` 한 자리 · 다른 `activeUniverseMemberIds` 호출 5곳 불변 단언) — 0부 뒤 첫 정기 FSR이 미국 1,000 NOT_FOUND를 안 남김.
5. **ADR-0135 펜스 상한**(`published_at ≤ max(RESTORED effective_to)`)을 0부로 당김 · 1부 적용 행은 「알려진 대기」로 따로.
6. 소비자: `AdminOverviewView.vue:290-292` · `types.ts:854` · `JobFreshnessPolicy` javadoc `:34-36`·`:47-48` · `JobFreshnessPolicyTest` · `PipelineScheduler:247-250`(「최대 10,970」 → FSR 15,844 실측) · 날짜 문장(`progress.md:55`·`:218`·`:239` · `reload-gate.md:55` · `CIK-REP-PLAN.md:291`) · `progress.md:308-312` MUST NOT(사유가 사라졌음을 적고 「전량 정의」를 이 결정으로 답함).
7. **예측**(RD0이 재구성으로 다시 낸다): FR 마지막 전량 #30(08-10 · 국내만이던 때) → 즉시 연체 · FSR 마지막 전량(요청 축 · 분기 포함) #190·#191(08-30) → 즉시 연체 · G #626(09-20) → 10-19(월) · 10-07 15:40 뒤 배포 → **목 10-08 14:15 FR**(배정) · **금 10-09 FSR**(가장 오래 밀린 것) · 10-19 G.
### 0부 드라이런 규칙 v4(대조 전 커밋 · 사본 · 실키는 평일 13:20~15:45 밖 · 운영 무거운 잡이 없는 날)
- RD0: V63 재구성 → 잡별 FULL/NARROW 목록과 예측 날짜(위 7) · 정상 전량 실행의 시장별 실패율 분포(과반 경계 확인).
- RD1: `heavyJobDueToday`를 목·금 날짜로 → FR·FSR · **그 잡을 사본에서 실제로 돌림**(FR 국내 DART 12,600 + 미국 SEC 1,000 · 운영 무거운 잡 없는 날 밤) → 정정·교정·무효화 수 · 그 뒤 화해 소요(14:20 종료 시각 추정).
- RD2: 대조군(옛 코드에서 시계를 미는 실행이 새 코드에선 안 민다): 티커 좁힘 · FSR `--all-quarters` 없이 · G `--governance-endpoints=DIVIDEND` · 시장 하나 통째 실패(주입).
- RD3: 단위 — 필수 인자(컴파일) · `startRetry` NARROW · 달성 내림 · FSR 국내 한정 + 다른 호출 불변 · 펜스 상한.

## 1부 v4 — (가) 공시 보충(0부 뒤 · V64)
v3에서 고침: **실패 계약** = 아직 저장 안 된 공시의 보충 실패 → 출력 = main + 기존 저장분 + 실패 기록(회수 0) · 종목 실패는 저장분을 못 쓸 때만(판 다시 읽기 실패 · 저장 실패) · `APPEND_ONLY`도 같은 계약 · **「원본 us-gaap 0 = MALFORMED」 삭제**(실물 일곱 전부 0이 원장의 사실 — GEHC 표지만 · BTU·CEG 축에만 · 위 실측) — 0은 저장하고 감시로 셈 · **보충 폼 = 정기 원본·정정 + 6-K·6-K/A**(8-K는 안 넣음 · `PERIODIC_FORMS`와 독립을 시험으로) · **창** = 라벨 창 시작 한 해 앞까지 받고 자르는 것은 `build()`(회계연도 두 번째 정본 안 만듦) · **RS2** 주입 자리 = 저장 판을 올린 뒤 다시 읽기의 404 · 대조군 「건너뜀」은 그 자리에서 삭제 > 0 · **RS1** a2는 a1 직후 다른 사본에서 갈라 돌림(또는 a1·b 시점 companyfacts 해시로 drift 가름) · ADR-0173에 「1부 뒤 다음 정기 FR(11-02~03)이 어댑터 변경을 현 편입 전원에 싣는다」 명시 · 나머지는 v3 그대로.

## v4 반증 판정 — 계획 밖 렌즈(2026-10-06 · 0부 「수정 후 진행 — 10-07 배포일은 근거가 안 선다」 · 1부 「수정 후 진행」 · `refute4/q01~q05`)
1. **[순위] 0부는 「3주 앞당김」이 아니라 무거운 잡의 위상 이동이고 국내 3분기에서 손해** — 26일 규칙(`PipelineScheduler:289-309`) 날짜 계산(q05) · 현 편입의 2025년 같은 철 첫 공시일(q03·q04 · 국내 3분기 FR 쪽 334/346이 11-03~11-24 · FSR 쪽 330이 11-03~11-16) · 새 위상 FR 10-08 → 11-03 → 11-30 · FSR 10-09 → 11-04 → 12-01 · 옛 위상 FR 10-30 → 11-25 · FSR 10-20 → 11-17 → **국내: FSR 330종목 14일 · FR 334종목 5일 늦어짐(약 −6,300종목-일)** · 미국은 섞임(179·10·25종목 약 22일 당김 · 249·440종목 4~5일 늦음 · 순 약 +1,500종목-일) → 2부(14:17 동기)가 11-13 전에 오는 것을 0부의 조건으로 적거나 배포일을 이 수로 · 「1부 적용보다 앞」은 서지만 1부가 10-07을 강제하진 않는다(10-08에 SCHEDULER FR이 한 번 생기면 그 뒤 손 FR은 scope와 무관하게 못 민다 `JobFreshnessPolicy:146-147`).
2. **[회귀] 미국 한 번 실패 → 달성 규칙 때문에 FSR·G가 계속 밀림** — A2 캐시는 첫 적재 실패를 10분 기억하고 네트워크 없이 던짐(`SecTickerCikCache:174-177` · 어댑터 `:113`) → 미국 1,000 몇 초 만에 전원 실패 · javadoc `:85` 「두세 번 다시 묻는다」는 첫 적재 경로에서 거짓(코드로 읽음) · 실패하면 NARROW → FR은 MAX라 매 평일 다시(국내 350도) · FSR도 MAX면 `Map.of` 순회 순서가 고름(명세상 미정 `:290,302`) · G 계속 밀림 · 그동안 국내 새 판은 FSR 칸이 빈 채 → 달성을 시장 단위로 저장해 실패 시장만 다시 · 동률 순서 결정적으로 · FR 시작 때 CIK 맵 미리 받고 재시도.
3. **[묶어서 판정] 예측 표가 계획 자신의 규칙과 어긋남** — #30 note 빔 → 「증명 안 되면 NARROW」로 FR FULL 0 · #190·#191은 `연도=2021~2026`이라 지금 기본 8년(`FullStatementRefillJob:48` · 5 → 8은 `4ef9dbd1`)이면 NARROW — 「기본」이 당시 값인지 지금 값인지 V63이 정해야 · G #626(09-20 02:41)이면 26일 11시간 지난 **10-16(금)**(10-19 아님 · q05).
4. **V63 승인 요청에 실을 것** — UPDATE 대상은 NOT NULL이라 전 행(738) · FULL id 목록 · 고친 예측 날짜 · 되돌리기: 옛 jar(A+A2) INSERT는 scope를 안 넣음(`BatchExecutionRecorder:52-56,63-67`) → 기본값 없는 NOT NULL이면 옛 jar의 모든 배치 시작 실패 → DDL(DROP NOT NULL) 먼저(원시 SQL · 승인) · 덤프(`deploy.sh`엔 덤프 없음 → `ops/dump-db.sh`) · V63 실패면 앱 기동 실패 → 옛 jar 재배포 · 기본값을 두면 되돌리긴 쉽지만 시험 픽스처가 조용히 받음 → 어느 쪽인지 적는다.
5. **[소비자]** SQL INSERT 픽스처 4 중 3이 목록 밖·컴파일러 못 잡음(`BatchExecutionClosingTest:235` · `PipelineEndToEndTest:727` · `AdminBatchApiTest:313`) · `StaleJobResponse:20` javadoc · ADR-0116 premise(scope 없이 SCHEDULER를 셈 → 달성으로 내린 행에서 「배너와 같은 것을 말한다」가 깨짐) · 이력 API `BatchExecutionReader:40`·`types.ts:771`에 scope 없음 → 배너(`AdminOverviewView.vue:291`)가 「스케줄 실행이 아직 없어」라고 거짓.
6. **[달성의 단위]** 종목으로 셀 수 없는 예산 잡(daily-update 2,703/1,350 · universe-rebalance t=3 · derived-rebuild t=272) · FSR 국내 한정과 겹치면 미국 대상 0 → 0/0을 「과반 아님」으로 읽으면 FSR 영구 NARROW → 「수집 시장 = 그 실행 대상의 시장」 · 이력 정상 실패율 최대 15/151(#261) · 52/350(#40) — 과반 경계는 선다.
7. **[펜스 상한]** 상한 조건은 FR이 옛 기간에 새로 만드는 행을 못 거름 — `published_at ≤ 08-31`이면서 주식수 있는 행: #683 384/393 · #643 304/312 · #710 52/52 → 10-08 FR이 펜스를 52 → 52+N(재지 않음) → ADR-0135 「적재 뒤 복원을 언제 다시 도는가」(progress [V2])를 그 FR 날에 답해야.
8. **[RD1·배포 조건]** FR 12,600 + FSR 15,844 = 28,444 > DART 20,000/일 → 같은 KST 날 못 돎 → 분할이 계획에 없음 · 0부 jar는 main이라 A+A2를 포함 → 10-07 13:30·14:00에 A+A2 jar 성공을 배포 조건으로.
9. **[1부 회귀]** 「원본 us-gaap 0 = MALFORMED」를 지우면서 잘못 고른 파일(`_lab`·`_pre`)이 「원장이 안 줌」으로 영구 저장되는 것을 막는 장치가 0 · GEHC 실물은 컨텍스트 3·dei가 있어 잘못 고른 파일과 구분됨 → 지운 규칙 대신 「자기 CIK 컨텍스트 ≥ 1 ∧ `dei:DocumentType` = 폼」 · 6-K XBRL 비율 재지 않음.
- 작다(쟀다): 10-08 FR이 10-10 순위에 더하는 비대칭 — 작년 09-27~10-08에 공시가 있었던 현 편입 12/1,000 · 경계 ±15% 띠 안 1/96(q01) · 후보 쪽은 DB 시세가 13/3,951뿐이라 못 그림.

## v4 반증 판정 — 계획 안 렌즈(2026-10-06 · 0부 「다시 짠다」 · 1부 「수정 후 진행」 · `rebut4/q01~q05`)
**0부** 1. [오류 1] 달성 규칙 하나를 BUDGETS 12잡·SCHEDULER 전부에 — 단위가 「종목 × 시장」이 아닌 잡(rebalance 2~3 지수 · derived-rebuild 272 거래일 · origin·derive·adjustment 1,350~1,683) · rebalance #278(SCHEDULER 09-05) 지수 1/3 실패인데 `countExternally`라 실패 행 0 · indicator-backfill #10 failure_count 84,716 / 대상 350(작업 단위) · 「수집 시장」= `CollectedMarkets.ALL`(`:45`) → 대상 0 시장은 0/0 → 영구 NARROW. 2. [임계] 「과반」은 비율 — DART 한도 소진은 종목별 RATE_LIMITED(`DartFinancialAdapter:121-122` · `DartResponse:105` · 치명은 AUTH뿐 `DataSourceException:60-62`) → 그 꼬리가 50% 미만이면 FULL로 26일 · 이력 분포 두 덩어리(정상 최대 1.4% · #261 9.9% · 시장 통째 100%) → 10~100% 어느 경계든 통과 → RD0으로 못 가름 · company-profile #40 UNEXPECTED 52/350(14.9% · 우리 버그)도 통과 · RATE_LIMITED 실패 행 전 이력 0 → 종류로 가르라(RATE_LIMITED·AUTH·UNEXPECTED 1건이면 NARROW). 3. [오류 3] G 재구성의 증거가 「요청」이 아니라 「새로 생긴 행」 — upsert라 created_at 안 바뀜(`GovernanceRawWriter:38-46`) · #632 소요 54:58 = 12,050콜 실측(지금 기본)인데 새 행이 DIVIDEND뿐이라 NARROW · #626(44:47 · 그 전 기본)은 표마다 새 행이 우연히 생겨 FULL · G·FR #30은 note가 없어 증명 불가 → 「증명 안 되면 NARROW」면 FR·G 시계 NULL · 「기본 연수」 시점 미정 · NULL 둘 이상이면 동률을 `Map.of` 순서가 가름(`:290`). 4. FSR 국내 한정은 달성의 전제 — 같은 배포·같은 시험 · 다른 호출 5곳 수 맞음. 5. RD1 DART 예산이 한 날에 안 들어감(28,444 > 약 20,000 · 사본 리미터는 인메모리 따로) · 실행 창은 10-06 밤 · 10-07 00:00~13:20. 6. [오류 2] `-1`의 뜻이 「한 번도 성공 없음」에서 「전량 성공 없음」으로 — `JobFreshnessAlerter:115-116` · `StaleJobResponse:18` · `AdminOverviewView.vue:285` · NOT NULL이면 원시 INSERT 픽스처 셋(PipelineEndToEndTest:727 · BatchExecutionClosingTest:235 · AdminBatchApiTest:313) · 「stale() 배너 변화」가 RD에서 빠짐. 7. 펜스 상한: 지금 DB에서 빼는 행 0 · 남는 52행 전부 IOVA #710 · 펜스는 created_at만 세서 제자리 채움(주식수 NULL → 값)은 안 보임(`FinancialWriter:593`) → 펜스 수는 하한. 8. 요청 축에 asOf 없음(러너 기본 now() `BootstrapRunner:278` · 이력 153실행 위반 0).
**1부** 9. 「판 다시 읽기 실패 → 종목 실패」는 main보다 후퇴(그 종목 companyfacts 정정까지 막음 · 결정적 MALFORMED면 영구) → 옛 판 저장분으로 「main + 저장분 + 실패 기록」. 10. **보충 성공·실패 순서가 결과를 바꿈** — 처음 실패하면 그 라벨이 다른 판으로 만들어지고 다음 FR에서 보충이 성공해도 그 공시는 「이른 판」이라 건너뜀(`FinancialWriter:425-453` · 정기 insertEarlier=false) → 성공이 먼저 온 경우와 다른 상태로 관문까지 · 회수(`:186-188`)는 companyfacts가 뺀 판을 보충 실패 때 지움 → 「회수 0」은 main 대비일 뿐 → 보충 실패 종목은 그 실행에서 회수 건너뜀 · 실패 accn 기록 → 다음 성공 때 이른 판이어도 넣음 · RS2 「실패 → 성공 = 성공 한 번」 대조. 11. 「실패 기록」 자리 없음 — 성공으로 센 종목에 FINANCIAL 실패 행이면 failureCount 안 늘어 알림 없음 · 다음 성공 때 해결로 지워짐(`BatchExecutionContext:654-660` → `BatchExecutionRecorder:322-327`) → 저장 표 상태 컬럼(또는 단계 — `ck_failure_phase` 마이그레이션). 12. RS1이 국내까지(정기 플래그로 세 번이면 DART 37,800) → `--universe=US_TOP1000`.

# 계획 v5 (2026-10-06)
## 0부 v5 — [A56] **전진 수정**(이력 재구성 · 달성 규칙 버림)
**버린 근거(두 렌즈 · 수)**: 이력 재구성은 무거운 잡의 위상을 옮겨 국내 3분기에서 약 −6,300종목-일(v4 계획 밖 1) · 그 증거가 「요청」을 증명 못 함(G upsert · note 없음 — v4 계획 안 3) · 달성 규칙은 단위·임계가 안 섬(v4 계획 안 1·2). [A56]의 해악은 「**앞으로** 좁힌 손 실행이 시계를 민다」이고 그것은 이력 없이 막힌다.
1. `batch_execution.scope`(`FULL`·`NARROW` · **NULL 허용** · V63 = ADD COLUMN만 · 이력 UPDATE 없음 → 운영 행 수정 승인 불요 · 옛 jar INSERT도 그대로 됨).
2. `start`·`startRetry`에 scope **필수 인자**(오버로드 없음 · 컴파일러가 25곳 강제) · 잡이 자기 인자로 정한다: FR = 티커·유니버스 없음 ∧ 연수 ≥ 기본 ∧ 이른 판 안 넣음 무관 · FSR = 티커 없음 ∧ `allQuarters` ∧ 연수 ≥ 기본 · G = 엔드포인트 전부 ∧ span·year 기본 · 그 밖 잡 = 티커·유니버스 없음 · **asOf가 오늘(KST)에서 7일 안**(러너 기본 now() · 스케줄러는 당일·직전 평일 · `--as-of` 과거면 NARROW) · `startRetry` = NARROW · GAP_FILL = NARROW.
3. 폴백과 SCHEDULER 갈래 둘 다 `scope IS DISTINCT FROM 'NARROW'`(NULL 이력은 예전처럼 셈 — 위상 불변: FR 10-30 · FSR 10-20 · G 10-16).
4. **FSR 기본 대상 = 국내만**(`FullStatementRefillJob:192` 한 자리 · 다른 호출 5곳 불변 단언) — 10-20 첫 정기 FSR이 미국 1,000 NOT_FOUND를 안 남김.
5. ADR-0135 펜스 상한(`published_at ≤ max(RESTORED effective_to)`) — 지금 빼는 행 0이지만 시즌 새 10-Q가 거짓으로 붉히는 것을 막음 · 펜스 수는 하한(제자리 채움 안 보임)임을 펜스 문장에.
6. 소비자: 화면 문구(`AdminOverviewView.vue:290-292` 「수동 실행까지 함께 셉니다」 → 「전량 수동 실행만」) · `types.ts:854` · `StaleJobResponse:18-20` · `JobFreshnessPolicy` javadoc · `JobFreshnessPolicyTest` · 이력 API(`BatchExecutionReader:40` · `types.ts:771`)에 scope를 싣는다 · `PipelineScheduler:247-250` 「최대 10,970」 → FSR 실측 15,844 · `progress.md:308-312` MUST NOT(사유 소멸 · 「전량 정의」는 이 결정).
7. **따로 넘김(별도 결함 · 재고)**: 「PARTIAL_SUCCESS가 시장 통째 실패·RATE_LIMITED 꼬리를 안고도 성공으로 셈해져 시계를 되돌림」(#410·#415 FSR 미국 1,000 · 종류로 가르는 처방 후보 — v4 계획 안 2) — 기존 결함이고 0부 전진 수정과 독립.
### 0부 드라이런 규칙 v5(대조 전 커밋)
- RD0(0콜): 사본에 V63 → 12잡 `stale()`·`daysSinceLastSuccess`가 **배포 전과 같다**(이력 NULL = 예전처럼) · `heavyJobDueToday` 요일별 같다.
- RD1(대조군): 옛 코드에서 시계를 미는 실행(티커 좁힘 FR · `--all-quarters` 없는 FSR · `--governance-endpoints=DIVIDEND` G · `--as-of` 과거)이 새 코드에선 안 민다 · 전량 손 실행은 민다.
- RD2(단위): 필수 인자 컴파일 · `startRetry`·GAP_FILL NARROW · FSR 국내 한정 + 다른 호출 불변 · 펜스 상한 · 이력 API scope.

## 1부 v5 — (가) 공시 보충 (0부 뒤 · V64)
v4에 더해: **실패 계약** = 보충 실패(처음 · 판 다시 읽기 둘 다) → 출력 = main + 쓸 수 있는 저장분(옛 판 포함) + **그 종목은 그 실행에서 회수 건너뜀** + 실패 accn을 저장 표 상태 컬럼에(다음 성공 때 이른 판이어도 넣음) · 종목 실패는 저장 자체 실패뿐 · 잘못 고른 파일 방어: 인스턴스는 「자기 CIK 컨텍스트 ≥ 1 ∧ `dei:DocumentType` = 그 폼」이어야 저장(아니면 그 공시 실패로 기록) · **적용 = 0부 배포 뒤 1부 배포 → 결손 종목에 좁힌 손 FR(`--universe=US_TOP1000 --tickers=…` · NARROW라 시계 안 밂)** · RS1은 `--universe=US_TOP1000`로 좁힘 · RS2에 「실패 → 성공 = 성공 한 번」 대조 · 나머지 v4 그대로.

## v5 반증 판정 — 계획 밖 렌즈(2026-10-06 · 0부·1부 「수정 후 진행」 · 일정은 선다 · `refute5/q01~q04`)
- 일정: 0부 10-07 15:40 뒤(A+A2의 10-07 13:30·14:00 확인 뒤) · 1부 10-08 저녁 이후 · 10-19 전 무거운 잡 0 → 시계 충돌 없음 · (가) 대기 약 거래일 2 × 4,035칸(상한).
1. **[순위] 2부 마감이 v5에서 사라짐 — 가장 큰 축** — 현 편입 2025 같은 철 라벨 첫 공시일(`published_at` 최소 09-27~11-29 → 2026으로 · US 961 · KR 344 라벨 · `q04_part2_deadline.sql`) · v5 위상 FR만의 지연 US 15,499 · KR 3,927 라벨-일 · 2부를 10-30에 켜면 16,721 줄고 한 주 늦출 때마다 10-30 → 11-06 1,165 · 11-06 → 11-13 3,657 · 11-13 → 11-20 6,009 · (가)는 주 약 560(80 × 7) → **「2부 ≤ 11-06」** · 국내 갈래는 1부에 안 매이므로 병행 · FSR 칸 빠져 하한.
2. **#710은 회피 처방이 아님 — 근거를 수로** — FR 지연 합(`q03`): v5(10-30 → 11-25) 19,426 · #683 기준(10-26 → 11-23) 20,248 · 재구성(10-08 → 11-03 → 11-30) 19,468 · 뿌리는 앞으로 막히고 (가)는 10-30을 안 기다림 → v2 계획 밖 판정 1 처방은 이행 · #710 산물은 우연히 822 나음(US +1,504 · KR −682) · [오류 1] 「버린 근거」 −6,300 중 4,620은 FSR 몫(330 × 14) · FR 재구성만 보면 중립(−42) → 잡마다 수로 · 「위상 불변」→「FR 10-30은 #710 산물 · 대안 대비 수」.
3. **[수 오류] G는 10-19(월)** — v5에선 NULL인 #633(09-20 16:14)을 셈 · 10-16 14:15 경과 25일 22시간 → `toDays` 25 → `isOverdue`(`>25` · `:307`) 거짓 → G 10-19 · FSR 10-20(10-19엔 G가 더 오래 밀려 먼저).
4. **[1부 하류] 좁힌 손 FR 뒤 화해가 계획에 없음** — 손 FR 뒤 자동은 주식수 기저 유도뿐(`BootstrapRunner:409-411`) · 정정·교정·이른 판 종목은 지표가 지워지고 알림은 「다음 화해까지 빈다」(`FinancialRefillJob:175-181,283`) · 80종목 새 라벨은 무효화 없이 다음 평일 13:30까지 옛 값(`:155`) · 금요일 밤이면 10-10 주말을 넘김 → `--job=indicator-reconcile`을 같은 승인에(10-05 00:29 선례) · 무효화 수 재지 않음.
5. **[1부 하류] 관문 재적재가 보충 실패를 못 봄** — v5 계약에서 보충 실패 종목은 SUCCESS · 실패는 저장 표 상태 컬럼에만 · 관문 도구의 재시도·멈춤은 `batch_execution_failure`만 읽음(`ops/apply-window/04-refill.sh:15-24` · `env.sh:40`) · 관문은 11년 · 과거 편입 `--tickers`(`reload-gate.md:115`)라 보충 표면 최대 → 「회수 건너뜀 + 공시 빠짐」이 관문을 OK로 통과 → 관문 절차(`reload-gate.md:107`)에 「보충 실패 accn 0」 단언 + 재시도, 또는 보충 실패도 단계 붙은 실패 행으로.
6. **[0부.7 이월] PARTIAL 결함의 자리** — `reload-gate.md:68` 「?」·「재지 않음」 · 재적재가 닫는 결손 아님 · 「독립」은 후순위 사유 아님 · 크기는 지금 잼: FR·G 이력의 시장 통째 실패 0(`rebut4/q01`) · FSR 3실행은 구조적 원인이고 0부.4가 닫음 · 노출은 첫 SCHEDULER 세 판(10-19 G · 10-20 FSR · 10-30 FR) → 실패 종목을 다음 평일에 집는 자리는 종목별 표지를 갖는 2부 → 2부 수용 기준으로 · G는 2부 밖이라 마감 따로.
7. 화면 문구 「전량 수동 실행만」은 NULL 이력을 세는 동안 거짓(FR 「마지막 성공」은 #710 1종목) → 「표지 전 이력은 범위를 모르고 센다」.
- 선다(쟀다): FSR 국내 한정은 꼬리(`MembershipGapFiller:329`가 티커를 넘김) · `04-refill`·`08-gate`(FR만)에 영향 없음 · 스케줄러 asOf 최대 3일 전(`DailyUpdateJob:171-177`) → 7일 규칙 안 걸림 · 보충 실패는 종목 실패가 아니라 시장 통째 실패를 새로 안 만듦 · 0부·1부를 한 jar로 내도 순서 제약은 지켜짐.

## v5 반증 판정 — 계획 안 렌즈(2026-10-06 · 0부·1부 「수정 후 진행」 · `rebut5/q01~q06`)
**0부** 1. G는 **10-19(월)** · FSR 10-20 · FR 10-30(`q01`·`q03` — #633 NULL을 셈 · 10-16 14:15는 25일 22시간 → `toDays` 25) · `progress.md:55`가 맞음. 2. **[오류 1] G의 FULL에 「티커 없음」 빠짐** — `GovernanceLoadJob.run`이 `tickers`를 받음(`:112-114` · `BootstrapRunner:362-366`) · G 손 실행 MANUAL 18 중 9가 티커 좁힘(8·4·32·6·4·2·29·2·337 · `q05`) → 정의대로면 「`--tickers` + 엔드포인트 전부」가 FULL → [A56] 그대로 → G에 「티커 없음」 · RD1에 `--tickers` G. 3. **NULL 유입을 막는 장치 없음** — 「필수 인자」라도 null은 컴파일됨 · DB NULL 허용 · 술어는 NULL을 전량으로(실패 시 열리는 방향) · 「0건 즉시 finish」는 8곳(CorporateActionJob:101 · InitialLoadJob:105 · GovernanceLoadJob:120 · CompanyProfileJob:70 · FullStatementRefillJob:85 · FinancialRefillJob:129 · GovernanceDeriveJob:43 · AdjustmentRebuildJob:119) → `BatchExecutionRecorder.start`·`startRetry`에 `requireNonNull` + 시험(옛 jar 되돌리기를 살리려면 DB 제약이 아니라 앱에서). 4. **[반대 방향] 정기 호출이 NARROW로 찍히면** SCHEDULER 갈래가 그 행을 버려 폴백 시계(#710)가 남고 FR이 매 평일 다시 돎 — 지금 인자로는 셋 다 FULL(쟀다: G `(null,null,List.of(),List.of())` `:332` · FSR `(null,List.of(),false,true)` `:335` · FR `(null,List.of(),null)` `:340` · asOf `targetDateFor` 3일 이하) · RD2에 「정기 호출 전부(13:30 · 14:00 사슬 · 14:15 셋 · 14:18 · 14:20 · 토요일 리밸런싱과 꼬리)가 FULL로 찍힌다」 시험. 5. [오류 2] 화면 문구 「전량 수동 실행만」은 배포 직후 거짓(시계를 만드는 행이 범위 기록 전 NULL · 내용은 좁힌 실행 #710·#664·#633) → 이력 API에서 NULL은 「기록 전」 · scope를 API enum으로 내보내면 `src/test/resources/contract/wire-contract.json`(`:79-87` TriggeredBy 옆) · 시험 쪽 시작 호출 28곳(5파일)은 컴파일러가 잡음. 선다: NULL 이력은 `IS DISTINCT FROM`으로 예전과 같음 · `startRetry`는 daily-update(`:235`)만 쓰고 그 잡은 SCHEDULER 성공 37 → 폴백 안 탐 · GAP_FILL은 두 갈래에서 이미 빠짐(`:214-216`) → `startRetry`·GAP_FILL NARROW는 누구의 시계도 안 바꿈(이력 표시용). **0부의 효과 창**: FR은 1부를 10-29 전에 좁힌 손 FR로 적용할 때만 · FSR·G는 10-19·20 전의 손 실행에만 — 그 뒤엔 SCHEDULER 행이 생겨 폴백을 안 탄다.
**1부** 6. **「종목 회수 건너뜀」은 지킬 것이 없는데 막는 것만 있다** — 회수(`FinancialWriter:315-327`)는 `published_at`이 keep에 없는 행을 지움 · 보충 실패 공시 X에서 잃을 수 있는 것은 X의 `filed` 날짜 행뿐 · 보충 대상 87건의 filed 날짜에 그 종목 행 **0/87**(대조군 companyfacts 공시 491/491 있음 · `q04`·`q06`) → 첫 실패면 출력·회수가 main과 같음 · 종목 전체를 건너뛰면 다른 라벨의 정당한 회수(AIG 꼴)까지 막음 · 결정적 실패면 영구 → 「X의 filed 날짜 행이 그 종목에 있을 때만」 건너뜀 · 결정적 실패는 종료 상태 + 구성원마다 판정 · RS0 합격선에 「결정적 실패 종목 수」 · 초안 리더의 결정적 MALFORMED(쓰지 않는 forever 컨텍스트도 던짐 `XbrlInstanceReader:267` · 남의 CIK `:121` · 같은 정밀도 둘 `:146`). 7. **「이른 판이어도 넣음」 운반체 없음** — `insertEarlier`는 호출 단위 불리언(`:189`·`:455`) · 종목 단위로 켜면 그 종목의 모든 이른 판이 정기 FR에 들어감(`:178` MUST 위반) → `published_at ∈ {회복된 공시의 filed}`일 때만 · 그 경로에도 번호 재매김·무효화 · 「실패 → 적용」 상태 전이는 종목 쓰기와 같은 `REQUIRES_NEW`(따로 커밋되면 롤백 때 표시가 사라져 순서 의존이 돌아옴) · RS2에 「같은 종목에 다른 이른 판 결손이 있는 표본」(그 판은 안 들어가야). 8. 판별 규칙(자기 CIK 컨텍스트 ∧ `DocumentType` = 폼)은 표본 15/15 통과(`q02` · GEHC 3 · OTF 3 · MAIN 2 · OBDC 75 · BTU 431·747 · CEG 915 · AAPL 161 · JPM 2,058 · MSFT 446 · 거짓 거절 0) · 표본에 /A·6-K·20-F·40-F·10-KT 없음(캐시 보충 대상 87 중 /A 6) · 링크베이스·xsd 음성 대조 없음 → RS0에서.

## v6 고침(구현 반영 — 기제 불변 · 두 렌즈 수렴)
- 0부: G FULL = 티커 없음 ∧ 엔드포인트 전부 ∧ span·year 기본 · 기록 계층 `requireNonNull` · 정기 호출 전부 FULL 시험 · 이력 API·`wire-contract.json`에 scope(NULL = 「기록 전」) · 화면 문구 「표지 전 이력은 범위를 모르고 센다」 · 날짜는 G 10-19 · FSR 10-20 · FR 10-30(#710 산물 — 대안 대비 수는 v5 계획 밖 판정 2) · **배포 10-07 15:40 뒤(A+A2 10-07 13:30·14:00 확인 뒤 · 사용자 확인)**.
- 1부: 회수 건너뜀 = 「실패 공시 X의 filed 날짜 행이 그 종목에 있을 때만」 · 이른 판 운반 = `published_at ∈ 회복된 공시의 filed` · 상태 전이는 같은 REQUIRES_NEW · 결정적 실패 종료 상태 · 좁힌 손 FR 뒤 `indicator-reconcile`을 같은 승인에 · 관문 절차에 「보충 실패 accn 0」 · 2부 마감 ≤ 11-06(국내 갈래 병행).

## 0부 드라이런 실행 규칙(2026-10-06 04시 · **대조 전 커밋** · 코드 = `wt-p0` 미커밋 작업 트리 · 시험 1,769/0)
**RD0(0콜 · 운영 이력)** — 운영 `batch_execution`을 표 하나만 떠서 빈 사본(`stock_screener_sc_p0rd0`)에 넣고 V63을 그대로 적용한다. 코드의 질의 문자열(옛 = main `JobFreshnessPolicy.lastSuccessAt` · 새 = `wt-p0`)을 글자 그대로 옮겨 12잡 × 두 갈래를 잰다.
- 기대: 12잡 전부 옛·새의 `lastSuccessAt`이 초 단위까지 같다(이력이 전부 NULL). 무거운 잡 선택 모사(10-07~11-06)가 같다 — G 10-19 · FSR 10-20 · FR 10-30.
**RD1(대조군 · 사본 `stock_screener_sc_c` · 실키 · 순차 · 평일 13:20~15:45 밖)** — `boot2.sh <wt-p0> stock_screener_sc_c --job=...`. 실행마다 새 행의 `scope`와 그 잡의 새 술어 시계(위 질의)를 앞뒤로 잰다.
| # | 인자 | 기대 scope | 기대 시계 |
|---|---|---|---|
| a | financial-refill `--tickers=IOVA` | NARROW | 안 움직임 |
| b | financial-refill `--universe=US_TOP1000 --tickers=IOVA`(1부 적용 모양) | NARROW | 안 움직임 |
| c | full-statement-refill `--tickers=005930 --quarters=true` | NARROW | 안 움직임 |
| d | full-statement-refill(인자 없음 · `--all-quarters` 없음 · FY만) | NARROW | 안 움직임 · 대상 = 국내 활성 편입 수 · 미국 실패 0 |
| e | governance-load `--tickers=005930` | NARROW | 안 움직임 |
| f | governance-load `--governance-endpoints=DIVIDEND` | NARROW | 안 움직임 |
| g | governance-derive `--tickers=005930` | NARROW | 안 움직임 |
| h | governance-derive `--as-of=2026-09-01` | NARROW | 안 움직임 |
| i | governance-derive(인자 없음) — 양성 대조 | FULL | 이 실행으로 움직임 |
| j | company-profile(인자 없음) — 양성 대조 | FULL | 이 실행으로 움직임 |
- 예산: DART 약 d 3,150 + f 1,400 + c·e 수십 · Alpaca f 약 1,000 · SEC a·b 수 콜. 운영은 오늘 무거운 잡이 없다(셋 다 연체 전) → 14:00 CA 약 700뿐.
- 대조군이 원래 결함을 재현하는지: 옛 술어(main 질의)로 같은 표를 재면 a~h 중 SUCCESS인 것은 시계를 민다 — 그 수를 함께 적는다(안 밀면 대조군이 결함에 안 닿은 것).
- 실패 처리: 잡이 FAILED면 scope 단언만 하고 시계 단언은 「SUCCESS 아님이라 원래 안 셈」으로 따로 적는다 · 기대와 다르면 멈추고 원인을 코드 결함/대조군 결함으로 가른다.
**RD2(단위)** — 시험 1,769/0 · 변이체 9/9 붉음(ADR-0172 §검증) · ADR-0135 펜스 상한을 운영에 돌려 출력이 고치기 전과 같다(52행 · 빠지는 행 0).
**RD1 정정(04시 10분 · k·l 대조 전 커밋)** — 사전 등록의 i·j 기대(「이 실행으로 움직임」)가 **내 등록 결함**이었다. `governance-derive`(10-05 14:17)·`company-profile`(09-26 20:00)은 SCHEDULER 성공이 있어 시계가 스케줄러 갈래에서 나오고, 손 실행은 범위와 무관하게 원래 안 센다 — 두 대조군이 폴백 갈래에 안 닿았다. g·h도 같은 이유로 시계 단언은 정보가 없다(scope 단언만 정보다). 폴백 갈래의 양성·음성 대조는 SCHEDULER 성공이 0인 싼 잡 `market-calendar`(사본 마지막 성공 #172 08-29 · 거래소 2콜)로 다시 낸다.
| # | 인자 | 기대 scope | 기대 시계 |
|---|---|---|---|
| k | market-calendar `--from=2026-09-01` | NARROW | 새 술어 안 움직임 · 옛 술어 움직임 |
| l | market-calendar(인자 없음) — 폴백 갈래 양성 대조 | FULL | 새 술어도 이 실행으로 움직임 |

## 1부 구현과 RS0 규칙(2026-10-06 04시 40분 · **대조 전 커밋** · 코드 = `wt-p1` 미커밋 · 0부 커밋 4d0d2a47 위)
**구현(v6 그대로)**: 리더(원 팩트를 정밀도째 · 고르기는 읽을 때 · 기간 없는·남의 CIK 컨텍스트 건너뜀 · 신원 확인 = 자기 CIK 컨텍스트 ≥ 1 ∧ 문서 종류 = 폼(/A 뗌)) · `SecFilingClient`(submissions + 창에 닿는 페이지 · 색인 · 인스턴스 = `.xml` 중 FilingSummary·링크베이스 뺀 하나 · TRANSIENT·5xx만 두 번 다시) · V64 `sec_filing_supplement`(OK·FAILED · 원 팩트 jsonb · reader_version) · `SecFilingSupplement`(대상 = 보충 폼 ∧ isXBRL ∧ companyfacts us-gaap 0 · 창 = from−1년 1월 1일 · 결정적 실패는 판이 같으면 안 다시 받음 · 실패 = 그 제출일 보호 + FAILED · FAILED → OK = 회복 · 목록 실패 = 저장분만 + 회수 정지 · 종목을 안 세움) · Writer `VintageGuard`(보호 날짜는 회수 keep에 · 회복 날짜는 정기 경로에서도 이른 판으로 · 불확실이면 회수 정지) · FR note `보충받음·보충실패·미해결보충실패` · 시험: 리더 8 · 보충 9 · Writer 판 보호 4(각 대조 포함).
**RS0 규칙**(0콜 · 같은 날 수집 · `from` = 2018 = FR 기본(asOf−8)):
- 수집: `SecFilingSupplementCacheLiveTest` · 모집단 = DB의 `US_TOP1000` 편입 이력 전 티커 1,209(현 1,000 + 과거) · companyfacts·submissions 같은 날 · 제거 대조군 17(`p1rs/controls.txt` — 모양마다 최신: AAPL·MSFT·JPM 인라인 10-Q·10-K · GOOGL·HIG 10-K · AAPL 비인라인 10-Q 2017-02 · 10-Q/A 5 · 10-K/A 4 = 모집단 티커 순 첫 것).
- (i) **제거 대조**: 17건 각각 companyfacts에서 그 접수번호 us-gaap 제거 + 보충 = 제거 없는 보충 출력과 그 티커 행이 같다 · 대조군 = 제거 + 보충 없음은 그 공시의 판이 빠진다(원래 결함 재현) · 하나라도 다르면 그 칸을 연다.
- (ii) **전/후 분류**(보충 없음 B · 보충 A · 키 = 티커·fy·fp·pub · 기계 규칙): A에만 있는 행 → B에 그 라벨 없음 = 「새 라벨」(FY이고 B에 fy−1·fy+1 FY가 있으면 「HOLE 해」) · 있으면 pub > B 최대 = 「늦은 판」 · < 최소 = 「이른 판」 · 사이 = 「사이 판」 / B에만 있는 행 = 「사라진 판」 / 같은 키 값 다름 → 유도 행 = 「유도 재계산」 · 유도 아님 = 「그 밖」. **합격**: 「그 밖」과 「사라진 판」 중 비유도는 하나씩 열어 원인을 적는다 — 보충이 원장 사실(그 공시의 값)로 바꾼 것이 아니면 실패 · 보충 대상 0 종목은 행이 바이트까지 같다.
- (iii) 수: 보충 대상 · 콜 · 실패(종류별) · 결정적 실패 종목(구성원마다 판정) · 문서 종류 불일치 · 같은 정밀도 충돌 · 후보 둘 이상 · 스윕의 「원자료 없음(404)」 = 0.
- 보충 환경변수 없는 스윕은 0부 트리와 바이트까지 같다(`.factcache` · from 2018).

## 0부 드라이런 판정(2026-10-06 05시 · **통과** · 코드 = 보류 브랜치 `hold/p0-run-scope` 4d0d2a47과 같은 트리)
- **RD0**: 운영 `batch_execution` 738행을 빈 사본에 떠 V63 적용 · 코드 질의 문자열을 글자 그대로 옮겨 12잡 × 두 갈래 — **12/12 같다** · 음성 대조(NARROW 성공 행 하나 삽입) → 옛 술어 FR 시계가 그 행으로 · 새 술어 10-03 그대로(도구가 본체에 닿음) · 무거운 잡 모사 G 10-19 · FSR 10-20 · FR 10-30.
- **RD1**(사본 `sc_c` · 실키 · 순차): a FR `--tickers=IOVA` · b FR `--universe=US_TOP1000 --tickers=IOVA` · c FSR `--tickers=005930 --quarters` · d FSR 인자 없음(FY만 · **대상 350 = 국내만 · 실패 0** — 소스 없는 미국이 실제 실행에서 빠짐) · e G `--tickers=005930` · f G `--governance-endpoints=DIVIDEND`(1,350 · 2,540초) · h governance-derive `--as-of=2026-09-01` · k market-calendar `--from=2026-09-01` — **전부 scope NARROW · 옛 술어는 시계를 그 실행으로 밀고 새 술어는 그대로**(대조군이 원래 결함을 재현) · l market-calendar 인자 없음 = FULL · **두 술어 다 그 실행으로 밂**(폴백 갈래 양성 대조) · g·i·j는 SCHEDULER 이력이 있는 잡이라 시계 단언은 정보 없음(04시 10분 등록 결함 정정 · scope는 g NARROW · i·j FULL로 기대대로).
- **RD2**: 시험 1,769/0 · 변이체 9/9 붉음 · ADR-0135 펜스 상한 운영 출력 불변(52행 · 빠지는 행 0).

## RS1 규칙(2026-10-06 05시 · **대조 전 커밋** · v5의 「라이브 FR 세 번」을 0콜 재생으로 바꿈)
**바꾼 이유**: 라이브 FR 둘을 시간차로 돌리면 그 사이 companyfacts 변동이 보충의 몫과 섞인다(v3 반증 9 · v4 「a2는 중립 관측자가 아니다」). 같은 날 캐시(`p1rs/cache-1006`)를 같은 시작 상태의 사본 둘에 Writer째 재생하면 차이가 보충의 몫뿐이다. 라이브 경로(어댑터 → 저장 표 → 수집기 → Writer)의 맞물림은 `SecFilingSupplementWriteContractTest`(RS2)가 묻는다.
- 사본: `stock_screener_q4v_rs1a`·`rs1b` = `sc_a` 복제(운영 10-05 단면 · 둘 다 같은 시작 상태).
- 재생: `EarlierVintageReplayHarnessTest` · 대상 = 모집단 1,209 중 캐시에 companyfacts가 있는 미국 종목 · from 2018 · **이른 판 끔**(정기 플래그) · rs1a = 보충 없음 · rs1b = 보충(`Q4V_REPLAY_SUPPLEMENT_DIR`) · rs1b 두 번째 재생.
- **기대(합격)**: ① 보충 활동이 없는 종목의 `financial_statement` 행은 rs1a = rs1b(값·번호·최신까지) ② rs1b에만 있는 판은 RS0 분류의 「새 라벨 · 늦은 판 · HOLE 해」로 전부 설명된다 · 「이른 판 · 사이 판」은 rs1b에 **없다**(정기 플래그가 건너뜀 · 회복 표지 없음) ③ 같은 판의 값 차이는 유도 행(Q4 등)이거나 RS0에서 연 「그 밖」 구성원뿐 ④ rs1a에만 있는 판(보충이 회수를 막은 것 또는 반대)은 하나씩 연다 ⑤ rs1b 두 번째 재생은 신규·정정·채움·교정·이른판 0.

## 1부 구현 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut6/`)
1. **XBRL 없는 공시를 영구 실패로 박제** — `isXBRL=1`인데 폴더에 .xml·.xsd가 없는 것 15/242(6.2% · 6-K 2017 14 · ESLT 2025-06) · 후보 0이 MALFORMED → 결정적 → 영구 FAILED·영구 보호·미해결에 영원히 · 원장이 「XBRL 없음」을 확정 = 진짜 한계 → 후보 0(그리고 .xsd·링크베이스도 없음) = 종료 상태(OK · 팩트 0).
2. **「미해결」이 셋을 못 보고 하나를 영원히 셈** — 목록 실패는 표에 안 남음 · 옛 판 사용은 수 0 · 대상에서 빠진(창 밖 · companyfacts가 나중에 실은) FAILED는 영원히 → 목록 실패·옛 판 사용을 보이게 · 미해결 = 지금 대상의 FAILED ∪ 옛 판 ∪ 목록 실패.
3. **회복 표지가 「FAILED였다」에 매여 순서 무관이 아님** — 배포 시점처럼 늦은 판 Y가 이미 있으면 「성공 먼저」는 X를 이른 판으로 건너뛰고 「실패 먼저」는 넣는다 · APPEND_ONLY가 표지를 소비 · 대조군 `successOnce`가 빈 DB에서 출발 → 회복을 무상태로(그 실행에서 합친 보충 공시의 제출일 전부 · 멱등은 `findVintage`) · 대조군 「Y 선적재 + 성공 먼저」 · 크기: 표본 50 중 주 라벨 행 0 · 비교열 사이 판 상한 2(AOS·ALNY).
4. **NOT_FOUND·응답 수준 MALFORMED까지 결정적** — 받지 못한 것이지 해석이 아님 → 결정적 = 받은 바이트의 해석 실패(읽기·고르기·신원·후보 둘 이상)만.
5. **RS1 ⑤가 저장분 경로에 안 닿음** — 재생마다 새 메모리 원장 → rs1b는 V64 사본에서 실제 저장소를 Writer와 같은 트랜잭션에.
- 선다(쟀다): 트랜잭션 경계(FR·InitialLoad 둘 다 `REQUIRES_NEW` 안) · 리더 규칙 캐시 인스턴스 227 — 문서 종류 227/227 · 남의 CIK 팩트 0 · 축 없는 scenario 0 · 같은 정밀도 충돌 0 · 날짜 단위 보호(X 제출일 행 0/50 · 날짜가 판의 키) · note 파서 충돌 없음 · 공동 등록 접수번호 0/502 CIK — **다만 PK가 접수번호 하나라 잠복 결함**(내가 받아 고친다: PK = 접수번호 · CIK).

## 1부 구현 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute6/`)
1. **XBRL 없는 6-K를 영구 실패로** — 색인 후보 0인 공시 28(현 편입 24 · 14 CIK · 전부 6-K 2017-07-17~08-04 + ESLT 2025-06-03) · 28 전부 `.xsd` 없음 · 후보 1인 476은 전부 `.xsd` 있음 → 진짜 한계 · `미해결` 24+가 모든 FR note에 영구 · api-spec 「6-K 154 전부 isXBRL 0」은 표본 사실을 모집단처럼 읽게 쓴 것(현 편입 6-K 대상 77 · AER·BNT·DSGX·ICLR은 us-gaap 인스턴스가 있어 외국 발행사 새 분기 라벨) → 상태 NO_XBRL · 문서 고침.
2. **ADR-0135 펜스의 「알려진 대기」 분리 빠짐** — 손 FR 뒤 약 +86행 · 펜스 처방(복원 + 파생층 백필)이 관문 순서와 어긋나는 중간 재실행을 권함.
3. **측정 도구가 보충 없이 조용히 돔** — 스윕·하네스가 변수 없으면 보충 전 출력 · `missing()` 단언 없음 · 관문 재고의 수(S0·[S]·P3a·ADR-0151)는 보충 없는 트리에서 잼 → 명시 필수 · `missing()==0` 단언 · 재고에 「보충 전 트리 수」 표시.
4. (작음) APPEND_ONLY가 FAILED→OK를 소비 · 관문 재시도 FR은 `--financial-years=11 --insert-earlier-vintages=true` 명시.
- 순위: 선다(더 큰 축 못 찾음) · 「후보 쪽 재무 낡음」에 날짜가 없다 — 2부가 현 편입만 동기하면 11월 시즌부터 비대칭이 후보 전체로(칸 수 재지 않음). 설정 뒤에 안 두는 판단: 선다(3을 고친 뒤).
- 선다(쟀다): 보충 없는 스윕 = 0부 트리(cmp) · 신원 476/476 · 후보 둘 이상 0 · dei만 10-K/A 158 출력 무변화 · 07-judge 무관 · REQUIRES_NEW · 손 FR NARROW · 화해 상한 없음(그래서 `FinancialRefillJob:128` MUST NOT 사유는 낡음 — 따로 보고) · 10-30 FR 약 +8분(페이지 513장).

## 두 렌즈를 받아 바꾼 것(2026-10-06 05시 30분 · 구현 v2)
- **XBRL 없음 = `NO_XBRL`**(폴더에 인스턴스 후보·스키마·링크베이스가 다 없을 때 · 날짜 보호 없음 · 미해결 아님 · 팩트 `[]`) · 스키마·링크베이스는 있는데 인스턴스가 없으면 해석 실패.
- **결정적 실패 = 해석 실패(`UNREADABLE`)만**(후보 둘 이상 · 읽기 · 신원 · 같은 정밀도 충돌) · 404·5xx·본문 이상·한도는 다음 실행이 다시 받는다. 폴더 받기와 인스턴스 고르기를 갈랐다(`SecFilingClient.folder` · `SecFilingSupplement.pickInstance`).
- **회복을 무상태로**: 「이번 실행에서 실은(노드를 더한) 보충 공시의 제출일」 전부를 `supplementedVintages`로 → Writer가 정기 경로에서도 이른 판으로 넣음(`findVintage`가 멱등) · 실패였다가 companyfacts가 실은 공시는 실패 행을 걷고 그 날짜를 엶 · 대상에서 빠진 실패 행은 걷음 · 대조군 「옛 코드가 늦은 판만 쌓은 상태 + 보충 첫 성공」(통합 시험).
- **미해결 = FAILED ∪ `reader_version` < 지금** · 목록 실패는 Writer `Outcome.retractionStopped` → FR note `회수정지=N종목` · note 칸 `보충받음·보충실패·미해결보충·회수정지`.
- **저장 키 = (접수번호, CIK)**(공동 등록 잠복 결함).
- **측정 도구**: 스윕·하네스는 보충 설정(경로 또는 `off`)이 없으면 실패 · 경로를 주면 `missing()==0` 단언 · 하네스 `Q4V_REPLAY_SUPPLEMENT_STORE=db`면 사본의 V64 표를 원장으로(RS1 ⑤가 저장분 경로를 탐).
- **ADR-0135 펜스**(main `db409d7f`): 출처 분리 대신 처방을 「관문 10단계의 복원이 닫는다 · 관문 전에 따로 안 돈다」로 · §규칙에 재실행 시점 = 시장별 관문(2026-10-04 사용자 결정) — 출처 분리는 V64가 운영에 없을 때 전제를 깨므로 안 함.
- 시험: 1부 대상 118/0(보충 13 · 통합 5 포함).

## RS0 판정(2026-10-06 06시 · **통과** · 코드 = 구현 v2 + SWX 고침 · 같은 날 수집 `p1rs/cache-1006` · 0콜)
- **수집**: 모집단 1,209(CIK 없음 2) · companyfacts·submissions 같은 날 · 보충 대상 547 · 콜 4,102 · 실패 0 · 4.6GB.
- **(i) 제거 대조 17**: 제거 + 보충 출력 = 제거 없는 보충 출력 — **`A_rm.tsv` = `A.tsv` 바이트 동일** · 대조군(제거만)이 원래 결함을 재현한 것은 7티커 = AAPL(인라인 10-Q · 10-K · 비인라인 2017 10-Q) · MSFT · JPM · GOOGL · HIG · 정정 ACI(10-K/A) · AVAV(10-Q/A) · **정정 7건(ABCB·ACHR·ACLS 10-K/A · ADM·BE·BMRN·BRO 10-Q/A)은 지워도 출력이 안 바뀌어 본체에 안 닿음** — 그 모양은 이 대조로 재지 못했다.
- **(ii) 전/후 분류**(B 61,880 → A 62,031): 보충 활동 종목 313 · **보충 0 종목 출력 차이 0** · 새 라벨 133(108종목) · 늦은 판 14(13) · HOLE 해 4(AOS·SPGI·EMAT) · 사이 판 3 · 사라진 판 3(비유도 2 · 유도 1) · **「그 밖」 0 · 유도 재계산 0** · 연 것: 사라진 판·사이 판은 같은 기제 둘 — 보충 공시(AOS FY2024 10-K 2025-02-11 · ALNY 2026-04-30 10-Q)의 비교열이 같은 값을 먼저 다시 실어 나중의 같은 값 재게재(2026-02-10 · 2026-07-30)가 접힘 · 값은 어느 날짜에서도 동일(매출·순이익).
- **(iii) 수**: 받은 공시 544 · 더한 노드 45,850 · 실패 1 → **SWX FY2016 10-K(CIK 1692115 폴더 · 컨텍스트 704 전부 전신 Southwest Gas Corp 92416)** — 다른 파일이 아니라 「이 공시는 다른 등록자의 팩트만 싣는다」는 원장 사실 → **자기 CIK 컨텍스트 0 = 팩트 0(실패 아님)으로 고침** · 다시 돌린 A2 = A 바이트 동일 · 실패 0 · XBRL 없음 37(전부 후보 0 · 2017 6-K 36 + 2025 1) · 문서 종류 불일치 0 · 같은 정밀도 충돌 0 · 후보 둘 이상 0 · 원자료 없음(404) 0.

## RS1 판정(2026-10-06 06시 · **통과 — 기대 ② 하나가 회수+정정 갈래를 빠뜨렸다**(08시 정정 · 계획 안 v2 렌즈 3) · 사본 `q4v_rs1a`·`rs1b` = `sc_a` 복제 · 같은 날 캐시 · 정기 플래그)
- 재생: rs1a(보충 off) 행 +31(신규 24 · 정정 7 — 사본 단면 10-05와 캐시 10-06 사이 companyfacts 변동 · 두 사본 공통) · rs1b 첫째 +182(신규 152 · 정정 33 · 교정 3) · **rs1b 둘째 0**(DB 원장 OK 508 · NO_XBRL 37 · `fetched_at` 최대 = 첫째 끝 → 둘째는 저장분 경로만).
- ① 보충 활동 없는 종목의 행 차이 **0**.
- ② rs1b에만 있는 판 154 = **RS0 분류와 정확히 같다**(새 라벨 133 · 늦은 판 14 · HOLE 해 4 · 사이 판 3 · 분류 없음 0). **사전 기대 「이른 판·사이 판은 rs1b에 없다」가 회수+정정 갈래를 빠뜨렸다** — 사이 판 3은 같은 값을 나중에 다시 실은 판(2026-02-10·07-30)이 회수로 걷히고 보충 판이 정정 갈래로 들어온 것이다(AOS: 교정 2 · 정정 7 · 이른판 0). **06시에 「v2가 보충 판을 정기 경로에서도 열어서」라고 적은 귀속은 틀렸다**(08시 정정): b1의 Writer `inserted_earlier` 합이 **0**이다 — RS1에서 v2 열림 갈래는 한 번도 안 탔고, 그 기제의 증거는 통합 시험(`SecFilingSupplementWriteContractTest`)뿐이다.
- ③ 같은 판의 값 차이 **0** · 번호·최신만 바뀐 것 14 = 늦은 판 14의 앞 판(최신 표지가 넘어감).
- ④ rs1a에만 있는 판 3 = RS0 「사라진 판」(ALNY 2025 Q1 2026-07-30 · AOS 2023 FY·Q4 2026-02-10 — 값 동일 재게재).
- ⑤ 둘째 재생 신규·정정·채움·교정·이른판 **0**.
- 결론: Writer 층 출력 = 어댑터 층 출력(RS0) · 보충 없는 종목 무변화 · 멱등.

## v2 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · 새 결정은 선다 · `refute7/`)
1. **[순위] 손 FR 시점이 A의 10-10 명부와 어긋남** — `--universe=US_TOP1000 --tickers`는 열린 편입만(`StockRegistrar.oneUniverseMemberIds:538-541`) · RS1 Writer 차이 109종목(현 91 · 과거 18) · A가 10-10에 들일 새 대표 11 중 PPL(2026 Q2 새 라벨)·TAP(2026 Q1)는 보충 차이가 있는데 지금 편입 밖 → 10-10 전 손 FR은 이 둘을 못 잡고 곧 떠날 PPLC·TAP.A를 고침 · 10-10 뒤 꼬리는 재무 0행만 → BTSG 16 · NWSA 45 · UHAL.B 51행이 08-30 상태로 10-30까지 → **10-10 20:00 리밸런싱 뒤 손 FR 한 번 더**((q03 ∩ 그 시각 열린 편입) ∪ 새 대표 11 · NARROW).
2. **[② 소비자] 보충 설정 필수가 도구 둘만** — `SecEdgarFactsReplayTest`(`data-pipeline.md:135` 검증 사다리 ①)·`SecEdgarLabelDiffProbeLiveTest`·`SecEdgarSharesProbeLiveTest`가 보충 없이 조용히 돎 · api-spec `:387-388`·progress `:836-837`의 「AOS 2024/FY가 없다」 재현은 1부 뒤 어댑터와 반대 답 · 명령 줄 셋(api-spec `:335` · progress `:1109` · 스윕 javadoc)은 이제 예외.
3. **[② 소비자] `회수정지=`가 수만** — 관문 10단계는 종목을 판정하라는데 실행 기록에 종목이 없다 → `회수정지종목=` 나열.
4. **[③ 커버리지] 관문 창(11년 · 2015~)의 보충 대상 안 잼** — RS0 창은 2017~ · 그 사이 새 대상 ≥151(31종목 · 6-K 138 · 276 CIK 페이지 안 받아 하한) → 관문 수집 `SUPP_FROM=2015` 명시.
5. **[① 기록] ADR-0173 §결과 예외 크기** — RS1(1,207종목)에서 예외는 한 번도 안 터짐(이른판 0) · 사이 판 3은 회수 뒤 정정 갈래 · 보충 몫 무효화 15종목(현 편입 10: AEP·ALNY·AOS·ED·FTV·GD·JCI·LII·RRC·SPGI) · 무효화 없는 새 라벨 108종목.
6. **(잠복 · 지금 0) 실패 행 정리가 표지를 한 번에 소비** — 지우고 그 실행에만 날짜를 넘김 → 그 실행이 APPEND_ONLY면 사라짐 → 「companyfacts가 실음」 상태로 남겨 매 실행 넘김.
- 선다(쟀다): 예외와 관문 원칙 충돌 구성원 0(관문 이른 판 재고 36종목과 겹침은 VRSK 2판 · 손 FR 기본 거짓) · 10-30 정기 FR: 손 FR이 현 편입 91을 먼저 덮으면 보충 몫 무효화 ≈0 · 「일부만 싣는」 누락 0(2025-10 뒤 4,630건) · note 정규식 소비자 둘 다 새 칸 안 읽음 · NO_XBRL 소비자 정합 · 관문 켠 갈래도 제거 대조가 덮음 · main 쪽 base 뒤 변경은 문서뿐.

## v2 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut7/` · 0콜 · DB는 SELECT만)
1. **[① 묶어서 판정] 정리 갈래는 아직 상태에 기댄다** — 「회복을 무상태로」라는 근거 하나가 두 갈래에 달렸는데 무상태는 보충 성공 갈래뿐 · 실패 행을 지우고 그 제출일을 **한 번만** 엶 → 그 열림을 쓰는 것은 `FinancialWriter`의 FILL_MISSING뿐이라 APPEND_ONLY(`InitialLoadJob`)와 같은 날 다른 공시 실패(`removeAll`)가 열림을 효과 없이 소비 · 창이 좁은 손 실행이 기본 창의 UNREADABLE 행까지 지움 · 시험이 이 갈래에 안 닿음 · 지금 FAILED 0행(코드 경로로 판정).
2. **[① 묶어서 판정] 「결정적 = UNREADABLE」의 열쇠가 원인을 덮지 못함** — 다시 받지 않는 열쇠는 `READER_VERSION` 하나인데 그 정의가 읽기 규칙뿐 · 고르기 충돌·인스턴스 고르기·신원은 판 밖 · v2 자신이 신원 규칙(SWX)을 바꾸고도 판을 1로 뒀다(v1이 배포돼 있었다면 SWX는 영구 UNREADABLE) · 지금 UNREADABLE 0.
3. **RS1 기대 ② 귀속이 틀렸다** — 위 RS1 판정에서 고쳤다.
4. **[① 묶어서 판정] 제거 대조가 「출력을 바꾸는 모집단」으로 층을 안 나눴다** — 대조군은 17이 아니라 **18** · 노드를 더하는 보충 공시 115건(10-Q 102 · 6-K 7 · 10-K 4 · 20-F 1 · 10-Q/A 1) · 10-K/A 244건은 전부 더하는 것 0이라 본체에 안 닿은 정정 대조 7건은 그 모집단 밖 모양 · **진짜 구멍은 6-K·20-F 8건에 대조 0**(AER·BNT·TSEM·DSGX 새 분기 · DOX FY·Q4 · RGC FY2024 늦은 판).
5. (작음) 미해결이 표 전체를 셈 — rs1b 원장 545행 중 현 편입 밖 107행·69 CIK · 편입 종료 월 58~79 → 관문 사이에 0으로 못 내려옴.
- 선다(쟀다): 같은 날 열림이 자기 날짜 밖으로 여는 날짜 0 · 「us-gaap 0인데 노드를 더함」 0 · 멱등(b2 0 · RS1 ③을 41열 전부로 다시 재 같은 판 값 차이 0) · 재번호가 값에 영향 0 · 페이지 실패는 저장분만 갈래 · 남의 CIK 컨텍스트는 SWX 704뿐 · 대상 547(313종목) 재현.

## v3 고침(2026-10-06 08시 · 두 확인 반증 반영 · 보류 브랜치 `bd25a0e1` · 시험 1,802/0)
- **실패 행을 안 지운다**(안 1 · 밖 6): companyfacts가 실은 실패 행은 `COVERED`로 남기고 **매 실행** 그 제출일을 연다 · 대상에서 빠진 실패 행(창 밖)은 그대로 둔다 · V64 상태 `COVERED`(팩트 없음 · 실패 종류 없음).
- **판 = 해석 규칙 전체**(안 2): 읽기·고르기·인스턴스 고르기·신원 중 하나라도 바꾸면 올린다(javadoc·V64 주석 · 「고르기를 고쳐도 다시 받지 않는다」 두 문장도 맞춤).
- **미해결 = 이 실행이 본 행**(안 5): V64 `last_seen_at` · note `미해결보충=N(표 전체 M)` — 관문은 표 전체를 본다.
- **note `회수정지종목=`**(밖 3) · **`SecEdgarFactsReplayTest`도 보충 설정 필수**(밖 2 · 검증 사다리 ①).
- 변이: V7(COVERED 대신 한 번만 엶) · V8(미해결이 실행 범위를 안 봄) 붉음.
- 덤: 전체 시험에서 `IndicatorBackfillParallelTest` 종료 시험이 붉었다(단독 3/3 초록) → 탐침이 DB 행을 보고 신호는 등록부에 닿는 **시험 결함** · 등록 직전 200ms 변이로 재현 · main `600ad83f`로 고침(보류 브랜치에 집어 옴).

## RS0 보강 규칙(2026-10-06 08시 · **대조 전 커밋** · 안 4 · 코드 = `bd25a0e1`)
- 수집: 6-K·20-F 대조 넷(AER `0001378789-25-000017` 6-K · BNT `0001837429-25-000019` 6-K · TSEM `0001178913-26-000671` 6-K · DOX `0000950170-24-137381` 20-F)을 별도 폴더에 받아(36콜 · 실패 0 · 07시 58분) **그 공시 폴더만** `cache-1006`에 옮김 — 같은 날 캐시의 companyfacts·submissions는 안 건드림 · 대조 22(`p1rs/controls.txt` · 옛 18은 `controls-18.txt`).
- (a) **A3 = A 바이트 동일**(v3가 어댑터 출력을 안 바꿈 — 메모리 원장 첫 실행에는 실패·덮인 행이 없다).
- (b) **A_rm22 = A3 바이트 동일**(제거 + 보충 = 제거 없는 보충).
- (c) **대조군 B_rm22가 새 넷 각각에서 B와 다르다**(그 공시의 판이 빠짐 = 원래 결함 재현 · 본체에 닿음) — 다르지 않은 것은 「이 대조로 재지 못했다」로 적는다.
- 하나라도 어긋나면 그 칸을 연다.


## RS0 보강 판정(2026-10-06 08시 30분 · **통과 — (b)의 한 칸은 연 결과 보충 밖 어댑터 결함** · 코드 = `bd25a0e1` · 0콜)
- (a) **A3 = A 바이트 동일** — v3는 어댑터 출력을 안 바꾼다.
- (b) **A_rm22 ≠ A3 한 칸** → 열었다: BNT 2025 Q2(6-K `…25-000019`) `sharesOutstanding` A3 202,267,754 · A_rm22 203,406,212. 둘 다 그 공시가 실은 `WeightedAverageNumberOfSharesOutstandingBasic`이다(6개월 2025-01-01~06-30 · 3개월 04-01~06-30 — dei·`CommonStockSharesOutstanding`이 그 공시에 없어 넷째 후보로 내려감). `SecEdgarFacts.factFor`의 `ANCHORED_INSTANT`가 같은 보고서에서 `max(end)`만 보고 동점은 `HashMap` 순서가 정한다 — 같은 사실 집합이 경로(companyfacts · 인스턴스)에 따라 다른 쪽을 낸다. **원인 확인**: 동점 규칙을 임시로 결정적으로(창 일치 → 늦은 기말 → 늦은 시작) 바꾸면 **A_rm22 = A3 바이트 동일** — 보충은 companyfacts와 같은 사실을 냈고 차이는 순서뿐. 보충의 결함이 아니다 · 주식수는 `overwritable()` 거짓이라 1부가 기존 저장값을 뒤집지 않는다(새 행의 무작위는 companyfacts 경로와 같은 분포).
- (c) **대조군 B_rm22가 새 넷 모두에서 그 판을 잃는다**(AER Q2 · BNT Q2 · TSEM FY+값 1 · DOX FY·Q4) — 본체에 닿음 · 옛 18의 티커별 효과는 B_rm과 같다.
- **새 결함(1부 밖 · 관문 재고로)**: 동점 계측(`shares-tie-probe.patch` · `ANCHOR_TIE_OUT`)으로 B(companyfacts만 · from 2018)에서 동점 **1,905칸**(희석 1,890 · 기본 15) · 그중 **1,055칸**이 창과 맞는 3개월 대신 누계 값 · 결정적 규칙이면 B에서 **1,389행 · 178종목**의 주식수만 바뀐다(다른 컬럼 0 · 상대차 중앙 0.56% · p90 4.6% · 5% 초과 123) · **최대는 Up-C**(UWMC ×16 · RKT ×14 · OWL ×2.4 — 희석 가중평균이 기간마다 전환 단위를 넣고 뺀다) → 「창 일치」만으로는 닫히지 않는다 · DB 저장값 중 몇 칸인지는 재지 않음.

# 2부 계획 v1 (2026-10-06 09시 30분) — 14:17 재무 동기 단계(B 편입 갈래 + (나) 공시 갈래 + 국내 FSR 후속)
**순서 근거**: (나) 10-30까지 46,751칸-일 · 시즌 587,458칸-일(v1 모형 — 재지 않음) · B 13,216칸-일(상한) · 주식수 동점(관문 재고) 최신 행 58종목 — 이 묶음이 가장 크다. 마감 ≤ 11-06(v5 계획 안 반증 1 · 미국 같은 철 첫 공시).

## 실측(10-06 · 내가 잼)
1. **하중**(운영 DB SELECT · 현 편입 · `financial_statement.published_at` 2025-10-01~12-15 · 종목-일): 미국 48일 958 · 하루 최대 113 · p90 72 · 중앙 7 / 국내 11일 343 · **2025-11-14 하루 256** · 11-13 57 — 국내는 분기보고서 마감일(분기말 + 45일) 하루에 몰린다.
2. **SEC 일일 색인**(3콜 · `p2/dailyidx`): D의 색인은 D 22:04 ET에 뜬다(10-02 실측 · 10-01 22:02) = D+1 11:04 KST → **14:17 KST엔 어제(ET) 색인이 있다** · 아직 안 뜬 날짜는 **403**(`form.20261005.idx` · 10-06 08시 KST) — 「없음」과 「아직」은 분기 목록 `daily-index/YYYY/QTRn/index.json`이 가른다(10-01·10-02만 실음) · 형식 `CIK|이름|폼|제출일|edgar/data/<cik>/<접수번호>.txt` · 비시즌 하루(10-02) 10-Q·10-K 8건 · 파일 0.6~1.4MB.
3. **DART 공시검색 `list.json`**(5콜 · `p2/dartlist`): corp_code 없으면 **기간 3개월까지**(07-01~11-14 → `status 100`) · 마감일 하루(2025-11-14) `pblntf_ty=A` **2,484건 · 25페이지**(100건씩) · 08-15~11-14 3,418건 · 필드 `corp_code`·`stock_code`·`corp_cls`(Y·K·N·E)·`report_nm`(`[기재정정]`·`[첨부정정]` 접두 · 「분기보고서 (2025.09)」)·`rcept_no`(날짜 + 일련)·`rcept_dt`·`rm` · 대조군 삼성전자(corp_code 지정) 그날 1건 · **운영 코드는 이 엔드포인트를 안 쓴다**(새 외부 연동).
4. **시간표**(코드 `PipelineScheduler`): 13:30 일일 갱신 · 14:00 사슬 · 14:15 무거운 잡 · 14:18 화해 입력 유도 · 14:20 화해 · 15:30 점검 · 풀 1(순차 — 14:17 단계는 14:15 뒤 · 14:18 앞에 선다).
5. **KNOWN_ON 정렬**: 14:20 화해의 asOf는 시장의 어제다 → 14:17에 「제출일 ≤ 어제」인 공시를 받으면 그날 화해가 정확히 그 집합을 본다. 미국 14:17 KST = 01:17 ET(같은 날짜) — 어제 날짜 색인은 그 전 22:04 ET에 떴다 · 국내 `rcept_dt` ≤ 어제는 그 전에 접수됐다.
- **기록(옮김 · 재지 않음)**: 종목당 DART FR 41콜 · FSR 49.4콜 · 소요 KR FSR 4.82~6.90초 · KR FR 3.9~4.1초 · US FR 0.96초(B v7 계획 밖 반증 `q03`) + 보충 약 +0.5초(1부 계획 밖 반증 「10-30 FR 약 +8분」) · DART 한도 설정 18,000(`application.yaml:271`) · 보통 평일 잔량 16,300(B v7 계획 밖 2) · G 날 최악 약 15:43(B v7 계획 안 7).

## 결정
1. **표지 = 「제출일 어디까지의 공시를 받았나」**(워터마크): `stock_financial_sync(stock_id FK ON DELETE CASCADE, job_name, synced_through date, synced_at timestamptz, PK(stock_id, job_name))`(V65 · 새 ADR).
   - 그 종목에 성공한 FR(연수 ≥ 8 · FILL_MISSING)이 `synced_through` = **그 실행이 확인한 원장 상한**을 쓴다 — 정기·손 FR은 시작 시각의 시장 날짜 − 1(그 시각 전에 접수된 공시는 제출일이 그 날짜 − 1 이하를 전부 포함 — 미국 17:30 ET 뒤 접수는 다음 영업일 날짜라 「당일」은 안전하지 않다) · 단계는 아래 asOf.
   - 안 쓰는 것: 실패 · APPEND_ONLY(꼬리) · 연수 < 8 · 시세만. FSR은 같은 꼴 + 분기 포함 ∧ 정정보류 0(B v7 결정 1) · `synced_at`은 FSR 변경 갈래(`created_at > synced_at`)가 쓴다.
2. **대상**(시장마다 · asOf = 단계 시작 시각의 시장 날짜 − 1): 현 편입 ∧ (표지 없음 ∨ `synced_through` < asOf − 31일 ∨ **원장 공시 목록에 제출일 ∈ (synced_through, asOf]인 정기 공시가 있음**).
   - 미국 원장 = **SEC 일일 색인**(날짜마다 1콜 · 구간의 영업일 전부 · 목록에 없는 영업일 = 「아직」 → 그 날짜 앞까지만 확인한 것으로 친다) · 폼 = `SecFilingSupplement`의 보충 폼 집합(10-K·10-Q·20-F·40-F·6-K와 정정) · CIK로 맞춤.
   - 국내 원장 = **`list.json`**(`pblntf_ty=A` · 구간 ≤ 3개월 · 페이지 전부) · `stock_code`로 맞춤 · 정정 접두 포함.
   - **B 편입 갈래는 따로 없다** — 신규·재편입은 표지 없음 또는 낡은 표지(31일)로 잡힌다(후보는 FR 표지를 안 받으므로).
   - 원장 목록을 못 받으면 그 시장의 공시 갈래는 그날 0(표지 없음·낡음 갈래는 돈다) · 감시가 센다.
3. **단계**(`FinancialSyncStep` · 14:17 @Scheduled · 스케줄러 전용 · GAP_FILL · NARROW): 시장마다 FR 한 실행 → 국내 FSR 한 실행(그 FR이 행을 만든 종목 ∪ FSR 표지 없음·낡음 · B v7 변경 갈래) · 순서 = 미반영 공시가 오래된 종목 먼저 → 표지 없음 · 0건이면 안 부름. FR이 성공한 종목에만 `synced_through` = asOf.
4. **상한 — 시각과 콜 둘**: ① 단계 하나가 25분을 넘거나 15:05가 지나면 새 종목을 시작하지 않는다(남은 것은 다음 평일 · 15:30 점검 앞) ② 국내는 이 JVM `usedToday` 잔량 − 여유 1,000 안에서(FR 41 · FSR 49.4) · 넘친 수는 WARNING. **국내 마감일 피크(256 × 약 90콜 ≈ 23,000 > 한도)는 이틀 넘게 나뉜다** — 원장 한도라는 진짜 한계 · 그동안 그 종목의 지표는 KNOWN_ON보다 늦다(감시 ①이 센다 · 순서가 오래된 것 먼저라 수렴).
5. **초기 표지**(V65): 국내 FR = 마지막 FULL 범위 FR 성공의 시작일 − 1(그 실행 대상 중 실패 기록 없는 종목 · B v7 RB0 재구성) · 미국 FR = **NULL**(보충 가능한 어댑터로 동기된 적 없음 — 정직한 초기값) → 2부 배포 뒤 첫 평일들에 시각 상한으로 나눠 전원(US FR 약 1.5초 × 1,000 ≈ 25분 — 재지 않음) · 국내 FSR = B v7 그대로.
6. **월 정기 FR은 둔다** — 신호가 못 보는 변경(공시 없는 companyfacts 정정 · 8-K 재작성 · 원장 회수)의 그물 · 성공하면 표지를 쓴다.
7. **감시(15:30 경로만)**: ① 공시 갈래 대상이 3평일 연속 남음(상한 · 실패) ② 원장 목록 확인 실패 2평일 연속(미국 색인 · 국내 list) ③ 국내 FSR 변경 갈래 3평일 연속 ④ 오늘 동기 FR이 돌았는데 최신 지표일 지표 0행(화해 안 돎) ⑤ 표지 없는 현 편입 수(첫 주 뒤 0이어야).
8. **스위퍼·생존**: `WEB_OWNED_GAP_FILL_JOBS` += `financial-refill` · FSR GAP_FILL 고아는 ⑥ 생존 판정 항목 — 노출 수를 적는다(B v7 계획 밖 3).
9. **`DartCorpCodeCache`**: 하루 한 번 갱신 시도 · 실패 기억 · 옛 맵 + 나이 WARN · 첫 적재 실패만 던짐(B v7 계획 안 1).
10. **새 외부 연동 둘을 api-spec에**: SEC 일일 색인(`sec-edgar-financial.md`) · DART `list.json`(`dart-financial.md`) — 위 실측 그대로 + 실패 형태(403 = 아직 · `status 100` = 기간 초과 · `013` = 결과 없음 — 재지 않음).
11. **소비자**: B v7 결정 12(14:17 문장 13곳 · `BatchExecutionReader:202` · `admin.ts`·`types.ts` GAP_FILL · `00-precheck.sh` · `PipelineScheduler` · 15:30 javadoc · ADR-0135 펜스) + `PresetResultSnapshotTest` MUST(14:17 귀속) + `data-pipeline.md` 손 쓰기 창 13:30~15:40 + 1부 FR note 칸.

## 2부 드라이런 규칙 v1(대조 전 커밋 · 실키는 평일 13:20~15:45 밖 · 순차)
- **RF0**(0콜): V65 재구성 표지 vs `financial_column_absence.observed_at` 흔적(B v7 RB0) · 미국 NULL 1,000.
- **RF1 작년 시즌 재연(실키 · 쓰기 없음)**: 2025-11-17(월) 14:17이라고 치고 대상 판정을 돌린다 — 국내 = 11-14·11-15·11-16 `list.json` ∩ 현 편입 stock_code · 미국 = 11-14 색인 ∩ 현 편입 CIK → **DB에서 그 날짜에 `published_at`이 생긴 종목과 구성원마다 대조**(256 · 미국 그날 수) — 어긋남은 하나씩 연다.
- **RF2(사본 · 차분 오라클 a1 → b → a2)**: b에서 단계 1회 · a에서 같은 대상에 손 FR(+ 국내 FSR) → `financial_statement` 행 단위로 같다(id·created_at 제외 · a1 = a2인 종목만).
- **RF3 상한**: 표지를 서로 다른 과거 날짜로 UPDATE한 사본에서 시각 상한(가짜 시계) · DART 잔량 상한(가짜 리미터) → 남은 것이 다음 실행에 오래된 것 먼저.
- **RF4 단위**: 워터마크 규칙(성공만 · APPEND_ONLY·연수 < 8 안 씀) · 색인 「아직」(403 + 목록에 없음) → 그 앞까지만 · `list.json` 3개월 쪼개기 · 정정 접두 · CIK·stock_code 매칭 · 감시 축 다섯 · Dart 캐시.
- **RF5 가드**: 사본 아닌 이름 → 마이그레이션 전 실패(B v7 RB7).

## 1부 v3 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut8/` · 0콜 · DB는 SELECT만)
1. **[① 묶어서 판정] 표 전체 미해결 M의 「옛 판」이 안 쓰이는 행까지 센다** — `reader_version < 지금`이 상태를 안 가려 COVERED·companyfacts가 덮은 OK·과거 편입 행이 판을 올린 뒤 영구히 남는다(rs1b 기준 과거 편입 107행 · 69 CIK) · 옛 판 FAILED가 COVERED로 넘어가는 실행에서 N에도 거짓 +1 · 관문 10단계의 손 FR은 그 행을 대상으로 못 만든다 → 「판정 없이 남은 것 0」에 원리적으로 못 닿는다.
2. **[① 묶어서 판정] OK → 덮임 전이에서 순서 무관이 깨진다** — `settle`이 FAILED·COVERED만 연다 · X가 OK로 받아진 실행이 APPEND_ONLY뿐이었거나 같은 날 실패로 열림이 지워진 뒤 companyfacts가 X를 실으면 X 판이 이른 판으로 건너뛰어진다(처음에 실패였으면 들어온다) · 실측 전이 1건(EXE `0000895126-26-000047` · 09-15→10-06) · 실림 → 빠짐 0 / 41,197.
3. **[① 기제] 판이 상수라 관문 설정 뒤의 해석 규칙에 못 닿는다** — 축에만 실린 재무(`reload-gate.md` 대기 항목)는 리더 규칙 변경이라 꺼진 설정 뒤에 들어갈 것 · 판을 올리면 꺼진 정기 FR이 옛 규칙으로 다시 읽고 새 판 도장을 찍는다(현 편입 438행 · 845콜) · 안 올리면 영영 안 닿는다 · 판의 범위 목록에 `SecFilingClient`의 후보 규칙 셋이 빠졌다(② 소비자).
4. **[시험 미도달] v3의 새 갈래 셋** — (a) `touch`를 지워도 초록(현재 판 UNREADABLE 건너뜀 · 옛 판 재사용 행이 둘째 실행부터 N에서 사라짐) (b) FR note 배선(`since` · `회수정지종목=`)을 단언하는 시험 0 (c) `SecEdgarFactsReplayTest`가 `missing()`을 단언 안 함 — ADR-0173은 세 도구 다 단언한다고 적었다.
- 선다(쟀다): COVERED가 다시 대상이 되면 다시 받음 · APPEND_ONLY와 COVERED(V7 붉음) · 목록 실패 뒤 다음 실행이 엶 · 좁힌 창의 창 밖 FAILED · COVERED 매 실행 엶의 멱등 · 같은 날 보호와 열림(실제 충돌 0) · 시계(`since` = DB `now()` ≤ 행 시각) · note 소비자 · 판 올림 크기(현 편입 438행 · 845콜) · 커버리지 이동 1건.

## v4 처방(계획 밖 렌즈를 받은 뒤 한 번에 넣는다)
- **미해결 = 실패 ∪ 옛 판을 실제로 쓴 행**: V64 `stale_used_at` — 옛 판 저장분을 합칠 때(다시 받기 실패 갈래 · 목록 실패 갈래) 찍고, 저장(OK·NO_XBRL·FAILED)과 COVERED 전이가 지운다 · N = (이 실행이 본 FAILED) ∪ (이 실행에 옛 판을 쓴 행) · M = FAILED ∪ `stale_used_at` 있음 · 판 기준(`reader_version <`)은 미해결에서 뺀다 — 안 쓰이는 옛 판 행은 관문 재적재가 다시 받는다.
- **덮인 공시는 상태와 무관하게 COVERED**: `settle`이 대상 밖이고 companyfacts가 싣는 행을 OK·NO_XBRL·FAILED 어느 것이든 COVERED로 바꾸고(팩트를 비움 · 모양 제약) 매 실행 연다.
- **판 고정 시험**: 읽기 · 고르기 · 신원 · 후보 파일 규칙 셋 · 인스턴스 고르기의 출력을 고정 재료(`instance-mini.xml` + 파일명 목록)에서 해시로 내고 판마다 박는다 — 규칙을 바꾸고 판을 안 올리면 붉다 · 판 javadoc 범위에 `SecFilingClient` 후보 규칙 셋 · ADR §규칙에 「관문 설정 뒤에 두는 해석 규칙은 판을 그 설정의 함수로」(MUST — 지금 그런 규칙은 0이라 배선은 그 규칙과 함께 들어간다 · 배선만 먼저 넣으면 읽는 쪽 없는 인자다).
- **시험**: `touch` 대조(UNREADABLE 행 둘째 실행 N = 1) · FR 단(실행 전 시각의 FAILED 행 → `미해결보충=0(표 전체 1)` · 목록 실패 표지 → `회수정지종목=`) · ReplayTest `missing()` 단언 · 성공 먼저 짝(Y만 → X OK를 APPEND_ONLY로 → companyfacts가 X를 실음 → FILL_MISSING에서 X 판).
