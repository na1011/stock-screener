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

## 1부 v3 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute8/` · 0콜)
1. **[순위 · 가장 큼] 손 FR 술어가 (가)가 쫓아낸 종목을 못 잡는다** — `--universe=US_TOP1000 --tickers`는 열린 편입만(`oneUniverseMemberIds`) · DOX(편입 2025-09-24~2026-10-02)는 FY2025 20-F(2025-12-15)를 companyfacts가 안 실어 최신 기말 2024-09-30 → 주식수 나이 거름 2년(`UsMarketCapUniverseAdapter:147`)에 10-02 편출 · rs1b는 108,448,276주·기말 2025-09-30 · 시총 약 6.3B > 편입 최저 4.55B · 세 실행(손 FR 둘 · 10-30) 다 못 잡음 · 창 안 과거 편입 18의 FUNDAMENTAL 칸 상한 22,872 · 「리밸런싱 전 손 FR은 PPL·TAP을 못 잡는다」는 술어가 만든 거짓 → `--tickers`만(창 편입 술어 · 내가 코드로 확인: `activeUniverseMemberIdsByTicker` = `IN_RETENTION_WINDOW`).
2. **관문 10단계 재시도 명령이 과거 편입에 안 닿는다**(4475c000 회귀) → `--tickers`만.
3. **손 FR 시점** — 토 낮 두 jar 재생(`StoredShareCounts`를 읽음) 전에 끝내거나 20:00 뒤 · 둘째는 리밸런싱 뒤 꼬리 사슬(10-03 20:00~21:14)과 21시 확인 뒤.
4. ADR-0173 「+86행」 → RS1 78 → 231(+153 · 현 편입 124행·90종목).
5. **`ops/apply-window/r3nk.py`** 이른 판 기대값이 「플래그 거짓이면 0」을 전제 → 고쳐 쓰기 목록에.
6. 표 전체 미해결의 판 기준이 영구로 부푼다(계획 안 1과 같음).
7. 보충 설정 필수가 세 도구에만 — `data-pipeline.md:135` · `SecEdgarLabelDiffProbeLiveTest` · `SecEdgarSharesProbeLiveTest`(ADR-0126이 SharesProbe로 어댑터 출력을 판정).
8. (작음) 첫 편입은 RS1 모집단 밖 → 손 FR #2 술어로.
- 선다(쟀다): 주식수 비덮어쓰기(새 판 17 다 앞 판과 같은 주식수 · 같은 판 변화 0) · 14:18 기저 유도 무관 · note 소비자 · 수동 화해가 `deriveInputs` 포함 · ADR 전제 146 사본 대조(뒤집힌 것 ADR-0052 하나 — 사본에 화해 안 돌림) · FR과 화해 사이 공백(현 편입 10 지표 0행 → 한 승인·한 번) · 덤프 전 테이블 · `merge-tree` 충돌 없음 · 설정 뒤에 안 두는 판단 선다.

## 1부 v4(2026-10-06 · 보류 브랜치 `ba6d6767`·`ef44708e` · 시험 1,809/0)
- 미해결 = 실패 ∪ 옛 판을 실제로 쓴 행(V64 `stale_used_at` · 받기 실패·목록 실패 두 갈래가 찍음 · 저장·COVERED가 지움) · 덮인 공시는 상태와 무관하게 COVERED · 판 고정 시험(`ReaderVersionPinTest` · 출력 해시를 판마다 · 변이 `_ref` 뺌 → 붉음) · 판 범위에 후보 파일 규칙 셋 · ADR §규칙에 「관문 설정 뒤 해석 규칙은 판을 설정의 함수로」(그런 규칙이 들어올 때 배선 — 지금 0) · 시험 넷(touch 대조 · stale 사용만 셈 · 목록 실패 옛 판 · FR note) + 성공 먼저 짝(통합) · 탐침 둘 `PROBE_SUPPLEMENT=live|off` 필수 · 검증 사다리 ① · 문서(손 FR `--tickers`만 · 시점 · +153 · 관문 재시도 · `r3nk.py`).
- 변이 여섯 붉음(V9 정리가 실패 행만 · V10 touch 없음 · V11 판 기준 미해결 · V12 목록 실패 안 남김 · V13 FR since 처음부터 · V14 받기 실패 안 남김) · **A4 = A 바이트 동일**(어댑터 출력 불변).

## 2부 v1 반증 판정 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행 — 1·2·3은 술어와 표지를 고쳐 다시 받는다」 · `rebut-p2v1/` · SEC 4콜)
1. **EDGAR 휴무일에 미국 공시 갈래가 영구히 멈춘다(④)** — 2025 QTR4 `index.json`에 10-13(콜럼버스) · 11-11(재향군인) · 12-24 · 12-26 없음 · `market_calendar`엔 거래일 · 반대로 2026-04-03(성금요일) 색인 있고 NYSE 휴장 · 「없음과 아직은 목록이 가른다」는 휴일 없는 표본에서 적은 문장 → 확인 상한 = 목록의 마지막 날짜 · 그 앞 빠진 날 = 공시 없음 · 목록에 있는 날의 403 = 실패 · 실물 `index.json` 픽스처.
2. **표지가 「FR 성공」으로 전진하고 소비를 안 묻는다(① · ④ 1부 실패 계약의 이식)** — 미국: 보충 실패·목록 실패도 종목 성공 → companyfacts가 안 실은 새 공시에 보충까지 실패하면 표지가 넘어간다 · 국내: DART 재무 API가 T+1에 어제 접수분을 싣는지 재지 않음 · FSR 표지는 공시없음을 못 막음 → `synced_through = min(asOf, 이번 입력에 없던 목록 공시 중 가장 이른 제출일 − 1)` · 국내 입력 = 어댑터가 받은 `rcept_no` · 종료 상태를 원장에서 재고 정함 · 주입 대조군.
3. **「FR 전부 → FSR」 순서와 단계 상한이 PBR 기저가 어긋난 중간 상태를 만든다(②)** — 국내 FR 행엔 지배주주 자본이 없다(FSR 매퍼만) → FR만 든 행은 `equityAttributableToShareholders`·`controllingEquityThroughout`가 연결로 물러남 · 국내 피크 256에 FR 17분 → FSR 약 80만 → 약 176종목이 화해를 기저 어긋난 채 지남 · 최신 분기 지배 < 총 × 0.9가 86/332 → 종목 단위 (FR → FSR) 짝 · 상한은 짝 사이에서 끊음 · RF3 「FR만 든 종목 0」.
4. **상한 산수의 한계 오분류** — 시각 상한(약 151종목)이 DART 잔량(약 169)보다 먼저 묶임 · 41콜은 우리가 고른 8년 창 비용 → 보고서 단위 FR을 대안으로 잼 · 첫 주: 국내 `financial_column_absence` 0행이라 RF0이 국내에 안 닿음 · 국내 전원을 덮은 FR은 #30(08-10)뿐 · V65가 10-30 전이면 국내 350이 첫날 낡음 + 미국 NULL 1,000 · 시장 순서 미정.
5. RF1·RF2 — 미국 최대일 10-30(113)을 더함 · 국내는 「DB ⊆ 목록 · DB에만 0」(뒤 정정이 원본 날짜를 대체 17종목) · RF2의 a는 0부+1부 jar · 음성 대조 · 원래 결함 대조군.
6. 폼 집합은 「FR이 소비하는 집합」(`SecEdgarFacts.PERIODIC_FORMS`)에서 유도 · 같음을 시험으로.
- 선다(쟀다): 색인 Date Filed = DB `published_at`(10-30 113/113 · 11-14 8/8 · 색인에만 있는 것은 6-K) · CIK 매핑 1,000/1,000 · 17:30 ET 뒤 접수는 다음 날 색인(색인일보다 늦은 Date Filed 0) · 색인 뜨는 시각 22:05 EDT · 22:08 EST · 국내 `rcept_dt` = `rcept_no` 앞 8자리 204/204 · 수렴(표지는 FR로만 움직임) · 놓친 공시는 지연(화해 ①이 창 전체를 다시 씀) · KNOWN_ON 정렬.

## 2부 v1 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v1/` · 0콜)
1. **EDGAR 휴무일**(안 1과 같음 · DB로: 콜럼버스·재향군인 3년 다 미국 행 0 · 성금요일 행 있음 2024 197) → 확인 상한 = 그 분기·앞 분기 목록의 마지막 날짜 · 403은 실패 · 감시 「확인 상한 < asOf − 2 EDGAR일」 · RF1 단면 넓힘.
2. **소비**(안 2와 같음) — 국내 `DartFinancialAdapter:113-115`가 013을 정상 null로 · v1 계획 안 반증 5를 2부 v1이 답하지 않았다 → 미확인 = 보충 FAILED·uncertain 접수번호 · 국내 013 보고서.
3. **국내 피크 「진짜 한계」는 가짜** — `list.json`의 `report_nm`이 회계연도를 준다 → 공시 갈래는 그 연도만(약 4~8콜) · 2026 달력: 11-14가 토 → 마감 11-16 · 물려받은 위상으로 G 11-16 · FSR 11-17 → 11-16 단계 0 · 11-17 국내 약 5종목 → 11-18~19에야 닫힘 → 목록 연도로 좁힘 · 큰 날은 단계를 무거운 잡보다 앞에 · RF3에서 2026 11-12~20 모의.
4. **2부가 보충 경로를 「새 공시의 첫 소비자」로 바꾼다** — 주식수 동점 · OK→COVERED가 시즌마다 일상 · companyfacts 3일 이상 지연 0/20(시간 단위는 재지 않음) → note에 「제출 ≤ 2일 공시 보충 수」.
5. **미국 초기 NULL의 근거가 배포 순서에서 거짓** — 10-30 FULL FR이 1부 어댑터로 돈다 → 국내와 같은 재구성 · 시장별 몫 · 2부를 10-30 뒤에 둘 근거 없음(모형: 활성 10-26이면 미국 초과 지연 1,144 · 11-06이면 2,597 라벨-일).
6. **GAP_FILL 알림 문구(B v7 결정 9)가 안 넘어옴** · `data-pipeline.md:189`·`:182` · `batch-load/SKILL.md:579`·`:398` · `reload-gate.md:118-119` · 「14:17 문장 13곳」을 목록으로.
7. 후보 쪽 비대칭이 커진다 — 순위 띠의 후보를 색인 CIK 매칭으로 넣을지를 그 띠의 수로.
8. 기록 셋(마감 인용 「계획 밖 반증 1」 · 머리글 시각 · 600ad83f 본문의 Sweeper 문장은 GAP_FILL FSR엔 안 선다).
- 선다(쟀다): 순위 — 2부가 가장 큰 미결 축(없으면 같은 철 초과 지연 미국 15,065 · 국내 3,086 라벨-일) · 마감 ≤ 11-06은 꺾이는 점(미국 11-06→11-13 +3,338) · 시간표(풀 1 큐 · 15:05 상한이면 G 날 약 15:31) · DST 뒤 색인 여유 · B 흡수 · 국내 편입 · 해외 발행사 약 17 · 웹 중단 0 · companyfacts 3일 이상 지연 0/20.

# 2부 계획 v2 (2026-10-06 09시 · v1 두 렌즈 반영 · 「표지 = 소비한 공시」)
**뿌리 셋(두 렌즈 수렴)**: ① 「목록에 없는 날 = 아직」이 EDGAR 휴무일마다 갈래를 얼린다 ② 「FR 성공 = 공시 소비」가 서지 않는다(1부 실패 계약 · DART 013) ③ 국내 비용·순서는 우리가 고른 것(8년 창 · FR 전부 → FSR · 무거운 잡 먼저)이라 진짜 한계가 아니다.

## v2 실측(10-06 · 내가 잼)
1. **DART 재무 API가 정정본의 접수번호를 준다**(`fnlttSinglAcnt` 3콜 + `list.json` 3콜 · `p2/dartcons`): 휴온스글로벌 2025 3Q — 목록 원본 `20251114000715` → 같은 날 `[기재정정]` `…003116` · API `rcept_no` = `…003116`(CFS·OFS) · TCC스틸 `…000996` → `…003110` · API `…003110` · 대조군 롯데지주(정정 없음) 목록 `…003092` = API `…003092` → **국내 소비 = 그 (사업연도, 보고서코드)의 API `rcept_no` ≥ 목록 `rcept_no`** · 실시간 반영 지연은 재지 않음(지금 재면 이미 반영된 뒤다).
2. **DART FR 구조**(코드 `DartFinancialAdapter:93-141`): 연도마다 보고서코드 넷 · 한 콜이 CFS·OFS를 같이 줌 → 연도당 4콜 · FSR은 연도·코드마다 CFS 먼저 → 비면 OFS(`DartFullStatementAdapter:130-162`).
3. **미국 종목별 마지막 수집 시각**(`financial_column_absence.observed_at` · 종목마다 지우고 다시 씀): 1,000종목 09-28(#683) · 208 09-22 · 1 10-03 — 다만 APPEND_ONLY 적재도 쓴다.
4. **무거운 잡 고르기**(`PipelineScheduler.heavyJobDueToday`): 배정 요일(화 G · 수 FSR · 목 FR) → 없으면 가장 밀린 것 · DART 잔량과 단계 순서를 안 본다.
- 두 렌즈가 잰 것(옮김): EDGAR 휴무 = 연방 공휴일(콜럼버스 · 재향군인 · 12-24·26 색인 없음 · 성금요일 색인 있음 · NYSE와 다름) · 색인 Date Filed = DB `published_at`(10-30 113/113) · 17:30 ET 뒤 접수는 다음 날 색인 · 국내 `rcept_dt` = `rcept_no` 앞 8자리(204/204) · 국내 FR 행엔 지배주주 자본이 없다(FSR만) · 최신 분기 지배 < 총 × 0.9가 86/332.

## 결정
1. **표지 하나**: `stock_financial_sync(stock_id FK ON DELETE CASCADE PK, synced_through date, synced_at timestamptz)`(V65 · 새 ADR). 뜻 = 「제출일 ≤ `synced_through`인 정기 공시는 전부 소비했다」. 종목마다 FR(미국) · FR→FSR 짝(국내)의 결과로만 움직이고 **앞으로만** 간다.
2. **확인 상한(시장마다 · 원장이 정한다)**: 미국 = 이번·앞 분기 `daily-index/…/index.json`이 싣는 **가장 늦은 날짜**(그 앞에 빠진 날 = 그날 공시 없음 — 휴무 · 달력을 따로 안 쓴다) · 국내 = `list.json`을 받은 날의 어제(KST). **목록에 있는 날짜의 403·5xx = 실패**(1부와 같게 한도일 수 있다 — api-spec `sec-edgar-financial.md:572`) → 그 시장의 공시 판정을 그날 하지 않고 감시가 센다.
3. **목록 공시**: 미국 = 일일 색인의 폼 ∈ `SecEdgarFacts.PERIODIC_FORMS`(FR이 소비하는 집합 · 보충 폼과 같음을 시험으로 · CIK 매칭) · 국내 = `list.json` `pblntf_ty=A`(정정 접두 포함 · stock_code 매칭).
4. **소비 판정(종목마다 · 그 FR의 입력으로)**:
   - 미국: 목록 공시의 접수번호가 companyfacts us-gaap에 있거나 보충이 실었거나(OK·NO_XBRL·COVERED) submissions가 `isXBRL=0`이라 할 것이 없으면 소비 · 보충 FAILED · 목록 실패(uncertain) · submissions에 없음 = 미소비. → 어댑터가 `FinancialFetch`에 「소비한 접수번호 · 미소비 접수번호」를 낸다.
   - 국내: 목록 보고서의 (사업연도, 보고서코드)를 FR·FSR이 둘 다 받았고 API `rcept_no` ≥ 목록 `rcept_no`면 소비 · 013 · 더 이른 `rcept_no` = 미소비. → 두 어댑터가 받은 `rcept_no`를 낸다.
   - **새 `synced_through` = min(확인 상한, 가장 이른 미소비 공시의 제출일 − 1)** · 목록 공시가 없으면 FR 없이 확인 상한으로 간다.
5. **초기 표지 = 없음 → 확인 상한 − 90일로 친다**(국내 `list.json` 3개월 한도 안) — 실행 이력을 재구성하지 않는다(국내 흔적 0 · 미국 흔적은 APPEND_ONLY와 섞임). 그 90일 안의 목록 공시를 소비 판정으로 덮는다. 90일보다 오래된 공시는 지난 FR이 소비했다고 본다 — 근거: 국내는 09-21·22 전 편입 FR(#645·#646·#658) · 미국은 RS0/RS1(보충이 바꾸는 종목은 첫 손 FR이 덮음). 신규·재편입·후보에서 온 종목도 같은 규칙(B 편입 갈래 흡수).
6. **국내는 종목 단위 FR → FSR 짝 · 목록 연도로 좁힘**: 목록 보고서의 기말 연도와 그 앞 해(`bsns_year` 함정 — 결산월이 다른 회사 · `DartFinancialAdapter:308-323`)만 받는다(FR 약 8콜 · FSR 약 8~16콜 — 재지 않음 · RF에서 잰다) · 초기 90일 판정도 같은 폭 · 짝 안에서 FR만 들고 FSR이 실패하면 그 종목은 미소비(표지 안 감 · 다음 날 짝 전체를 다시) — 「FR만 든 종목」이 화해를 지나는 일을 없앤다(PBR 기저). 미국은 FR 하나(연수 기본 · companyfacts가 한 콜이라 좁힐 이득 없음).
7. **단계와 무거운 잡의 순서**: 단계를 **14:15**에(공시 소비가 먼저) · 무거운 잡을 그 뒤(14:16)로 · 무거운 잡은 ① 단계가 이월을 남기지 않았고 ② 남은 DART 잔량 ≥ 그 잡의 필요량(기록: G 국내 8,050 · FSR 15,844 · FR 국내 약 14,350)이고 ③ 시작 시각 ≤ 14:25일 때만 돈다 — 아니면 다음 평일(연체 알림은 그 사유를 문구에) · 14:18 유도 · 14:20 화해는 그대로 뒤.
8. **단계 상한 = 시각 하나**(25분 · 15:05 뒤 새 종목 안 시작) · 큐는 두 시장을 한 줄로 · **가장 이른 미소비 공시 먼저**(이월이 오래된 것부터 닫힘) · DART 잔량은 국내 짝마다 확인 · 넘친 것은 이월(WARNING).
9. **판정된 진짜 한계의 출구**: 원장이 재무 API에 그 보고서를 끝내 안 주는 종목(국내 재무 0행 088980 — 판정 근거를 적어 등록)은 소비 판정에서 빼고 표지를 확인 상한으로 보낸다 · 등록은 코드 상수 + 근거 javadoc(구성원마다 원장에 물은 기록).
10. **감시(15:30)**: ① 미소비 공시가 3평일 넘게 남음(구성원·접수번호를 문구에 — 판정 대상) ② 확인 상한 < asOf − 2(미국 EDGAR일 · 국내 평일) ③ 이월 3평일 연속 ④ 무거운 잡 미룸 3평일 연속 ⑤ 표지 없는 현 편입(첫 주 뒤 0).
11. **단계 note**: 시장별 대상 · 소비 · 미소비 · 이월 · DART 콜 · **「제출 ≤ 2일 공시를 보충으로 소비한 수」**(companyfacts 지연이 보충 경로를 첫 소비자로 만드는 빈도 — 주식수 동점이 경로에 매이므로 관문 전까지 잰다).
12. **알림**: GAP_FILL FR도 「14:20 화해가 채운다」 문구(B v7 결정 9) · 손 명령 안내를 뺀다 · dedupe 키를 날짜 단위로.
13. **소비자(목록으로 적는다 · 구현 첫 커밋에 grep 결과를 붙임)**: `data-pipeline.md:182·189` · `batch-load/SKILL.md:398·579` · `reload-gate.md:118-119` · `PipelineScheduler` javadoc(14:15·14:18·15:30 최악 시각) · `BatchExecutionReader:202` · `admin.ts`·`types.ts` GAP_FILL · `00-precheck.sh` · `PresetResultSnapshotTest` MUST · ADR-0135 펜스 · 1부 FR note · `BatchExecutionSweeper.WEB_OWNED_GAP_FILL_JOBS`(단계 실행 이름 추가 — 600ad83f 본문의 「Sweeper가 정리」는 GAP_FILL엔 이것이 있어야 선다).
14. **새 외부 연동 둘을 api-spec에**: SEC 일일 색인 · DART `list.json`(위 실측 · 휴무일 · 403의 두 뜻 · 3개월 한도 · 정정본 `rcept_no`).
15. **배포 목표 ≤ 10-26**(미국 파도 시작 10-21 · 모형 초과 지연 10-26 활성 1,144 대 11-06 2,597 라벨-일 — 계획 밖 반증) · 첫 날은 초기 90일 판정이 전 편입을 덮는다(미국 FR 약 1.5초 × 1,000 · 국내 짝 × 350 — 시각 상한으로 이틀 넘게 갈 수 있음 · 그동안 무거운 잡은 7의 규칙으로 미룸).
- **따로 둔 것**: 후보 쪽 재무 낡음(별도 축 · 순위 띠의 후보를 색인 CIK로 넣을지는 그 띠의 수로 — 재지 않음) · 배치 행 생존 판정(⑥).

## 2부 드라이런 규칙 v2(대조 전 커밋 · 실키는 평일 13:20~15:45 밖 · 순차)
- **RF0 원장 목록(0콜 · 실물 픽스처)**: 2025 QTR4 · 2026 QTR2 `index.json` → 확인 상한 · 휴무일(10-13 · 11-11 · 12-24 · 12-26 · 성금요일 04-03은 있음)을 「공시 없음」으로 · 목록에 있는 날의 403 → 실패 · `list.json` 3개월 쪼개기.
- **RF1 대상 재연(실키 · 쓰기 없음 · 날짜 여럿)**: 2025-10-14(콜럼버스 뒤) · 10-31(미국 최대 10-30 뒤) · 11-12(재향군인 뒤) · 11-17(국내 피크 뒤 월) · 2026-04-06(성금요일 뒤) — 표지 = 그 전날로 두고 대상을 낸다 → **DB에서 그 구간에 `published_at`이 생긴 종목 ⊆ 대상** · 대상 − DB는 기계 규칙(6-K · 뒤 정정이 원본 날짜를 대체 · 창 밖)으로 분류(규칙을 대조 전에 커밋) · 미국 6-K·`isXBRL=0` 따로 셈.
- **RF2 사본 차분(a1 → b → a2)**: a = 0부+1부 jar · b = 2부 jar · 같은 덤프 · b에서 단계 1회 · a에서 같은 대상에 손 FR(+ 국내 FSR · 같은 연도 폭) → 행 단위로 같다(id·created_at 제외 · a1 = a2인 종목만) · **음성 대조**: 비대상 종목 바이트 동일 · **원래 결함 대조**: a(단계 없음)는 14:20 화해 시점에 어제 공시의 행이 없다.
- **RF3 달력 모의(사본 · 가짜 시계 · 가짜 리미터)**: 2026-11-12~20(마감 11-16 · 무거운 잡 위상 G 11-16 · FSR 11-17) → 이월이 오래된 것부터 닫힘 · 무거운 잡 미룸 규칙 · **「FR만 든 국내 종목 = 0」** · 15:30 전 끝남.
- **RF4 단위 + 주입 대조**: 소비 판정(보충 FAILED → 표지 그 제출일 − 1에 멈춤 · uncertain → 안 감 · 국내 013 → 안 감 · 다음 날 다시 대상 · 정정본 `rcept_no` ≥ → 소비) — **대조군 = 「FR 성공이면 전진」 규칙으로 돌리면 넘어간다(원래 결함 재현)** · 표지는 앞으로만 · 초기 90일 · 폼 집합 = `PERIODIC_FORMS` · 판정된 한계 출구 · 감시 다섯 · 짝의 FSR 실패 → 미소비.
- **RF5 가드**: 사본 아닌 이름 → 마이그레이션 전 실패(B v7 RB7).
- **RF6 비용 실측**: 좁힌 국내 짝의 콜·초(사본 · 10종목) · 미국 FR 1,000의 초(초기 판정 하루치).

## 1부 v4 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut9/` · 0콜 · 변이는 `git archive` 사본에서)
1. **[시험 미도달] 판 고정 시험이 해석 실패를 만드는 갈래에 안 닿는다** — 재료가 `instance-mini.xml` 하나라 같은 정밀도 충돌·수가 아닌 값·없는 컨텍스트·단위·날짜 오류·깨진 XML·문서 종류 없음·unitRef 있는 dei·`INF`·decimals 없음·`scenario`가 없고 신원은 폼 넷만 · 변이 P1(정밀도 충돌 안 던짐)·P2(수가 아닌 값 건너뜀)·P3(dei 뺌)·P4(20-F·40-F 신원 생략)·P5(`scenario` 안 봄)·P6(decimals 없음 처리) 전부 생존 · 저장 행 크기(rs1b OK 508): dei 든 행 420(주식수 377팩트) · `INF` 1,927팩트·321행 · 신원 밖 폼 10-K/A 244·20-F 27·40-F 9 · `fetch()` 분류(`:248-267`)도 해시 밖.
2. **[④] `settle`이 목록 날짜만 연다 — COVERED 뒤의 판은 companyfacts `filed`로 매겨진다** — companyfacts `filed` ≠ submissions `filingDate` 15 / 55,736(CIK 기준 · 전부 companyfacts가 1~3일 늦음 · 17:30 ET 뒤 접수로 설명되지만 같은 모양 427건은 그대로라 미리 모름) · 운영 `published_at`이 companyfacts 날짜(IBM 2019 Q3 · KLAC 2020) → 보충 판 D가 회수되고 D′는 이른 판으로 버려질 수 있음 · 지금 기대 약 0.17건.
3. **[①] 목록 실패 실행이 `settle`을 건너뛴다** — 대상은 `!covered`일 때만이라 `settle`은 목록이 필요 없다.
4. **[시험 미도달] `stale_used_at` 지우기 두 자리와 목록 실패 갈래** — S1(`markCovered`가 안 지움)·S2(`saveNoXbrl`이 안 지움)·S3(목록 실패가 지금 판까지 찍음) 생존.
- 선다(쟀다): 찍기 두 길 · 상태 전이 닫힘 · 대상 밖 FAILED·stale은 M에 남음(관문 판정과 맞음) · COVERED의 팩트 비우기 안전(EXE 301팩트 · 실림→빠짐 0/41,197) · `mergeInto`·`targets`·창은 판 밖이 맞음 · 해시 흔들림은 안전 방향(레코드 `toString`) · 후보 파일 이름 567개 모양 전부 덮음.

## v5 처방(계획 밖 렌즈를 받은 뒤 한 번에)
- **해석을 정적 메서드로 뗀다**(`SecFilingSupplement.interpret(bytes, cik, form, name)` — 읽기 · 신원 · 고르기 · 해석 실패 분류) · `fetch`는 받기만 · 판 고정 시험이 `interpret`를 부른다.
- **판 고정 재료**: `malformed(` 자리마다 인스턴스 하나(일곱) — 재료 수 = 소스의 그 자리 수를 단언(새 자리가 생기면 붉다) · 특성 재료(unitRef 있는 dei · `INF` · decimals 없음 · `scenario` · CIK 아닌 식별자 · 문서 종류 없음 · 깨진 XML) · 신원 = `SUPPLEMENT_FORMS` × (각 폼 · 정정 · null) · 그 뒤 P1~P6이 붉은지 다시 잰다.
- **`settle`이 그 접수번호에 companyfacts가 붙인 `filed` 날짜들도 연다**(`usGaapAccessions` → 접수번호 → 날짜 집합) · 통합 시험: companyfacts `filed` = `X_FILED` + 1 · 뒤 판 있음.
- **목록 실패에서도 `settle`**(대상 = 빈 집합).
- **시험**: stale 행을 companyfacts가 실으면 M = 0 · 옛 판 OK를 다시 받아 NO_XBRL이면 M = 0 · 목록 실패의 지금 판 저장분은 `staleReused` = 0 · M = 0.

## 1부 v4 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행 — 손 FR 목록·술어·시점은 선다」 · `refute9/` · 0콜)
1. **화해 대상이 안 정해짐** — FR 알림 문구의 「위 종목」은 무효화 15뿐 · 새 라벨만 받은 94종목(FUNDAMENTAL 상한 229,229칸)이 다음 정기 화해까지 낡음 → 같은 `--tickers=<109>`.
2. **(가)가 관측 평면에 남긴 명부** — 근사 순위로 10-02 DOX 편출·NJR 편입이 보충 없는 사본에만 재현 · 관문 복원은 RESTORED만 → 재고에 올림(main `55f21828`) · 정확한 몫은 쓰기 없는 `universe-restore --dates`로 잴 것.
3. **관문 재시도 명령에 `--stockscreener.gate.us=true` 없음**(코드로 확인: `SecEdgarFinancialAdapter` 생성자가 그 값으로 Q4 규칙을 가른다).
4. **손 FR 앞 덤프 없음** — 덤프 하나(10-05 18:09) · 가드는 하나라도 있으면 통과.
5. **batch-load 스킬이 `--universe=US_TOP1000`을 권함**(`:19` · `:259`).
6. ADR-0173의 108 → 94·14·1.
- 선다(쟀다): 109 = 운영 창 편입 술어 109/109 · RS1 모집단 밖 APGE·CRNX(no-cik · 보충 무관) · 과거 편입 18 창 안 · A 새 대표의 보충 대상 0 · 10-09 주식수 바뀌는 98 전부 109 안 · 3B 이상 나이 거름 후보는 BAM(보충 0)·DOX뿐 · 소비자 · 머지 충돌 없음 · 시계(손 FR NARROW) · 옛 jar가 V63·V64 DB에서 뜸(Flyway 기본값 · 실행으로는 안 잼).

## 1부 v5(2026-10-06 · 보류 브랜치 `e90bc7cd`·`7c390296` · 시험 1,814/0 · A5 = A)
- `SecFilingSupplement.interpret`(받은 바이트의 해석) · 판 고정 시험이 그것을 부름 · 재료 = 해석 실패 자리 일곱(소스의 `throw malformed(` 수를 단언 · 재료마다 그 자리 메시지에 닿음) + 특성 재료 여섯(단위 붙은 dei · `INF` · decimals 없음 · decimals 없음과 -6 중복 · 차원 멤버 있는/없는 `scenario` · CIK 아닌 식별자 · 문서 종류 없음) · 신원 = 보충 폼 × 문서 종류 행렬 → 반증의 P1~P6 전부 붉음(P5는 차원 멤버 없는 `scenario` 재료를 더한 뒤 붉음 — 멤버가 있으면 `xbrldi`가 축으로 잡아 가려졌다).
- 덮인 공시는 companyfacts `filed` 날짜도 연다 · 목록 실패에서도 정리 · 시험(통합 하루 늦은 filed · 목록 실패 정리 · stale 지우기 둘 · 목록 실패의 지금 판 0) · 변이 다섯 붉음(V15 · V16 · S1 · S2 · S3).
- 문서: 화해 = 같은 `--tickers` · 손 FR 직전 덤프 · 관문 재시도·재적재에 `--stockscreener.gate.us=true` · 94·14·1 · main: batch-load 스킬 · progress · 재고(관측 평면 몫).

## 2부 v2 반증 판정 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut-p2v2/` · DART 20콜 09:06~09:12 · SEC 2콜)
1. **`[첨부정정]`은 재무 API가 안 따라간다(0/3) — 소비 판정이 영영 안 닫힘** — 기재정정은 10/10 따라감(같은 날 두 번이면 늦은 번호 · 다른 날 정정도) · 첨부정정: 로젠 2025 3Q 목록 `…003099` API `…002578` · HS화성 FY2025 · **현 편입 로보스타 FY2025**(목록 `20260424000350` · API `20260312000793`) · 엘앤에프는 약 16주 미소비였을 것(추론) · 빈도 하한 2026-04 1쪽 100건 중 5(편입 2) · **함정: `[첨부추가]`는 원본 항목의 표시라 원본 번호·날짜를 지님** → 비교에서 `[첨부정정]`만 뺀다.
2. **(사업연도, 보고서코드) 대응 규칙이 없다** — 「(YYYY.MM)」은 기말 · 결산월 12월 349 · 3월 1(신영증권) · NULL 2(056080 · 399720) · 12월 근사면 신영증권에서 거짓 미소비와 거짓 소비 둘 다 → 「종류 + (MM − `stock.accounting_month`) mod 12」 · NULL이면 미소비.
3. **초기 90일과 B 흡수는 일부에만 선다** — 국내 지금 편입은 #645·#646·#658·#647·#648 실패 0으로 선다 · 미국은 RS0/RS1이 사본이라 운영 근거는 첫 손 FR(아직 안 돎) → 「손 FR 뒤 보충 FAILED 0 확인」이 V65 선행 조건 · 앞으로 표지 없이 드는 종목(후보 재무 08-30 · 90일 무공시 미국 5% · 국내 8%)은 조용히 낡음 · 미국 목록의 아래 끝(재편입 종목의 언 표지) → 표지 없음 = 전 창 FR(B 편입 갈래 유지) · 표지가 앞 분기보다 오래되면 그 분기 목록도 읽거나 전 창 FR.
4. **무거운 잡 미룸에 영영 안 도는 길** — 잔량 여유 약 456콜 · 영구 미소비 종목의 매일 짝 13~28개면 FSR이 영구히 밀림 · 「≤ 14:25」 고정이면 일일 갱신이 41분을 넘는 날부터 매일 미룸(13.6 → 23.9분 추세 · 외삽 12월 초 · 재지 않음) → 영구 미소비는 출구로 · 재시도 간격 · 실제 사용량 · 측정 소요 예산.
5. **드라이런이 원장 위 소비 판정에 안 닿음** → **RF7**: 실제 `list.json`(2026-03~05)과 API `rcept_no`로 소비 판정 재연 · 미소비를 원인별로 0까지 · 대조군(로보스타 · 엘앤에프 · 로젠꼴 · 신영증권 · FSR 전용 칸 빈 5종목 · `[첨부추가]` 원본) · **분기 첫날 `index.json`도 403**(`2027/QTR1`) → 현재 분기 목록의 403 = 빈 분기 · 일일 파일 403만 실패.
- 선다(쟀다): 기재정정 10/10 · FSR `rcept_no` = FR(2/2) · 미국 「us-gaap에 있음 = 소비」(매핑 0인 공시 1/6,795) · 색인에만 있던 6-K 6건 전부 `isXBRL=0` · 색인 첫 판 완결(늦은 접수 0/14,259 · 순서 역전 0) · 좁힌 국내 짝은 Writer 계약 안 깸(DART `complete=false` · Q4(Y) 창 안 · 이른 판 안 열림) · 단계는 시계 안 되돌림 · RF1 ⊆ 기제상 선다.

## 2부 v2 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v2/` · 0콜)
1. **RF2가 좁힘의 효과를 못 본다** — a에도 같은 연도 폭 · 값이 호출 단위에 매임(`FinancialWriter.managedColumns:401` · `fillMissing toClear:584`) · ADR-0145:33 「창을 좁히지 않는다」 사유를 재서 답할 것 · 지금 갈리는 칸 0(DB 대리) → a = 기본 창 FR + 분기 FSR · 창 밖 행 불변.
2. **판정 출구가 기존 등록부(`KnownSourceGaps.INFRA_FUND` · KSIC:642)와 갈리고 종목 단위** — 415640 KB발해인프라(KSIC:642 · 재무 0행 · 편입 4회)가 갈림 · 088980의 근거 자체가 비어 있음(`KnownSourceGaps:51` 「재무 엔드포인트에는 묻지 않았다」) → 공시 단위 판정 행(근거·일시 DB) · 등록부 통일 · 세 엔드포인트에 대조군과 물은 뒤 등록.
3. **B 흡수가 배포 뒤 편입에 안 선다**(안 3과 같음) → 표지 없음 = 전 창 FR · 주당 약 17종목.
4. **스위퍼에 단계 이름을 더하면 CLI 꼬리의 살아 있는 FSR 행을 닫는다** — 꼬리가 두 JVM에서 `full-statement-refill` GAP_FILL을 만든다 → 단계 실행에 고유 이름 · 600ad83f 본문의 Sweeper 문장은 SCHEDULER·ADMIN·화해 ①에만 선다.
5. **「시작 ≤ 14:25」가 15:30 가드와 안 묶임** — G 최악 15:32:07 · 잡별 소요(G 54:58 · FR 약 48분 · FSR 27~43분) → 「시작 + 소요 + 12:09 ≤ 15:28」 잡별 · 가드 시험을 그 함수로 · 소비자 더: ADR-0116 §결정 2(사용자 문장 위 결정 → 새 ADR로 대체 기록) · ADR-0166 · ADR-0113 · `application.yaml:21` · `PipelineSchedulerTest:178-217` · progress · **CLAUDE.md:94**(꺼진 jar가 뜨면 되돌리는 것에 14:15 단계 추가).
6. **감시 ③·④·연체 사유가 읽을 저장소 없음 · v1 ④가 이유 없이 빠짐** → 시장·일자별 단계 결과 행(이월 수 · 미룸 사유) · v1 ④ 되살림.
7. progress·reload-gate의 낡은 마감·B 문구(main `55f21828`로 고침).
8. (작음) 짝의 실행 행 형태 — 한 실행 안의 짝으로.
- 선다(쟀다): 풀 1 순서 · 평상시 사슬 끝 14:12 · 0부 시계 · 관리 화면 버튼 없음 · B FSR 변경 갈래가 빠지는 몫은 목록 공시 없이 바뀐 API 값(재지 않음 — 사정거리로 적을 것) · 좁힌 짝 아래 정기 FSR 여유 약 456콜.

# 2부 계획 v3 (2026-10-06 09시 40분 · v2 두 렌즈 반영)

## v3 실측(10-06 · 내가 잼 · DART 27콜 · 탐침 `DartListQueryProbeLiveTest` · `p2/`)
1. **인프라 펀드 둘(088980 · 415640)은 재무 축도 진짜 한계**: `fnlttSinglAcnt`(FY2025 · 2026 반기) · `fnlttSinglAcntAll`(FY2025 CFS·OFS) 전부 `013` · `list.json`(`pblntf_ty=A` · 2026-01~03 · 07~09)에도 정기보고서 0건 · 같은 실행 대조군 005930 30행 → `KnownSourceGaps.INFRA_FUND` javadoc에 판정 기록(main `7b0a0ff3`) · **2부 소비 판정에 안 묶인다**(목록 공시가 없다).
2. **별도 전용 공시사의 FSR 소비**: 케이뱅크 · 카카오뱅크 · 온코닉 · 알지노믹스 — FR은 OFS만(같은 `rcept_no`) · FSR CFS `013` → **FSR OFS가 같은 `rcept_no`로 100~170행** → FSR 소비 = 「CFS가, 또는 CFS 013이면 OFS가 그 `rcept_no`를 줌」 · 지배주주 자본이 빈 것은 별도재무제표에 지분 구분이 없어서(설계).
3. **영업활동현금흐름은 연간만**(`DartFullStatementAdapter:609-614` · 분기 현금흐름표가 누적이라) — 현 편입 최신 분기 행 348/349가 빈 것은 결함이 아니다.
4. 미래에셋벤처투자(100790)는 비지배지분을 **부채**로 분류(`비지배지분부채`) — 자본 안에 비지배가 없어 자본총계 = 지배주주 몫 · 지배주주 자본 칸이 비어도 PBR 분모는 폴백으로 같은 값(기록만).
5. `stock.accounting_month`가 있다 — 현 편입 국내 12월 349 · 3월 1.

## 결정(v2에서 바뀐 것만 · 나머지는 v2 그대로)
1. **국내 짝을 좁히지 않는다**(v2 결정 6 철회) — 좁히면 Writer 관할(`managedColumns:401`)이 호출 안의 보고서로 정해져, 옛 연도에만 값이 있는 컬럼이 관할 밖이 되어 정기 FR과 **다른 값 경로**가 생긴다(지금 갈리는 칸 0 · 기제는 있다 — 계획 밖 v2 반증 1). 짝 = 정기 FR과 같은 인자(8년) + 정기 FSR과 같은 인자(5년 · 분기). **국내 마감일 피크는 DART 한도로 이틀에 나뉜다**(짝 약 90콜 · 보통 잔량 약 16,300 → 하루 약 180종목 · 2025 실측 피크 11-13 57 + 11-14 256) — 이 지연은 「같은 값 경로」를 지키는 값이다(좁힘은 버린 대안으로 근거와 함께 적는다). 순서가 「가장 이른 미소비 먼저」라 이틀째에 닫힌다.
2. **국내 소비 판정**:
   - 목록 항목 중 **`[첨부정정]`만 비교에서 뺀다**(재무 API가 안 따라감 0/3 · 로보스타 · HS화성 · 로젠) — `[첨부추가]`(원본 항목의 표시 · 원본 번호·날짜)·`[기재정정]`(API가 따라감 10/10)·원본은 남긴다.
   - (사업연도, 보고서코드) = 보고서 종류 + 「(YYYY.MM)」과 `stock.accounting_month`로: 경과 월 = (MM − 결산월) mod 12 → 0 = 사업(11011) · 3 = 1분기(11013) · 6 = 반기(11012) · 9 = 3분기(11014) · 사업연도 = 회계연도 시작 연도(`DartFinancialAdapter:308-320` javadoc 실측을 옮김 — RF7에서 신영증권으로 다시 잰다) · 결산월 NULL이면 미소비(감시가 신고).
   - FR 소비 = API `rcept_no` ≥ 목록 `rcept_no` · FSR 소비 = CFS의, 또는 CFS 013이면 OFS의 `rcept_no` ≥ 목록 · 둘 다 소비여야 그 공시를 소비한 것.
3. **표지 없음 = 전 창 FR(미국) · 전 창 짝(국내)을 무조건**(B 편입 갈래 유지 · v2 결정 5의 「없음 → −90」 철회) · **배포 시점의 현 편입만 초기 표지 = 확인 상한 − 90**, 선행 조건: 국내는 #645·#646·#658(FR)·#647·#648(FSR) 실패 0(반증이 확인) · 미국은 **1부 첫 손 FR이 운영에서 끝나고 보충 FAILED 0 확인**(RS0/RS1은 사본이라 운영 근거가 아니다) · 미국 표지가 앞 분기 시작보다 오래되면(이탈 뒤 재편입) 전 창 FR.
4. **판정 출구는 공시 단위 · 등록부는 하나**: `KnownSourceGaps`에 (종목, 사업연도, 보고서코드) 단위 항목을 더한다(근거 javadoc · 구성원마다 원장에 물은 기록 · 시험이 구성원을 단언) · 업종 키(`INFRA_FUND`)는 목록 공시가 없어 2부에 안 걸린다 · 미소비가 5평일 넘으면 그 공시의 재시도는 주 1회로 줄이고 감시 ①이 판정을 요청한다(매일 짝을 부르지 않는다).
5. **단계 = 잡 하나 `financial-sync`**(한 실행 안에서 종목마다 미국 FR · 국내 FR → FSR 짝 · 표지는 같은 `REQUIRES_NEW` 트랜잭션) · 웹 스케줄러만 만든다 → `WEB_OWNED_GAP_FILL_JOBS`에 이 이름(FR·FSR 이름은 안 더함 — 꼬리가 CLI에서도 FSR GAP_FILL을 만든다).
6. **무거운 잡 미룸은 잡별 소요 모델**: 시작 시각 + 그 잡의 소요(최근 성공 3회 중 최대 · `batch_execution`에서) + 뒤 사슬(유도 + 화해 ①② · 실측 12:09) ≤ 15:28 ∧ DART 잔량 ≥ 그 잡의 필요량(기록: G 국내 8,050 · FSR 15,844 · FR 국내 약 14,350) → 아니면 미룸 · **미룸은 최대 2평일** — 셋째 날은 무거운 잡이 먼저(단계 국내는 잔량에서 그 잡의 필요량을 뺀 만큼만) · 가드 시험을 그 함수로(`PipelineSchedulerTest:178-217` 순서 목록에 새 크론).
7. **단계 결과 행**(V65 `financial_sync_run(run_date, market, targets, consumed, unconsumed, carried, dart_calls, heavy_job, heavy_deferred_reason)`) — 감시가 읽는다: ① 미소비 3평일(구성원·접수번호) ② 확인 상한 지연(미국 = `index.json` 날짜 기준 · 국내 = 목록 실패) ③ 이월 3평일 연속 ④ 무거운 잡 미룸 2평일 ⑤ 표지 없는 현 편입 ⑥ **v1 ④ 되살림**: 오늘 동기 FR이 돌았는데 최신 지표일 지표 0행 · 단계가 아예 안 돈 날은 `max(run_date)`로.
8. **403**: 현재 분기 `index.json`의 403 = 빈 분기(분기 첫날 · 실측 `2027/QTR1`) · 목록에 있는 날짜의 일일 파일 403만 실패.
9. **사정거리(적는다)**: 목록 공시 없이 바뀐 API 값(B v7 FSR 변경 갈래가 잡던 몫 · 재지 않음)은 월 정기 FR·FSR이 닫는다 · 후보 쪽 재무 낡음은 별도 축.
10. **새 ADR**: 단계 · 표지 · 무거운 잡 미룸(ADR-0116 §결정 2 「요일을 나눠 자동으로」(사용자 문장) 위의 결정을 보탠다 — 요일 배정은 그대로, 미룸만 더함 · 사용자 문장과 충돌 없음) · 소비자 목록에 ADR-0116:230-245 · ADR-0166:26·78 · ADR-0113:241 · `application.yaml:21` · progress · **CLAUDE.md 「재적재·백필」의 「꺼진 jar가 뜨면 정기 refill과 화해가 되돌린다」에 평일 14:15 단계를 더하는 문장은 구현 때 사용자에게 제안**(사용자 결정 문단이라 에이전트가 직접 안 고친다).

## 2부 드라이런 규칙 v3(대조 전 커밋 · 실키는 평일 13:20~15:45 밖 · 순차)
- RF0 · RF1 · RF5 · RF6은 v2 그대로 + RF0에 분기 첫날 `index.json` 403(빈 분기).
- **RF2**: a = 0부+1부 jar로 **정기와 같은 인자**의 손 FR·FSR · b = 단계 · 행 단위 같음(좁힘이 없어 기제상 같아야 한다 — 다르면 단계가 다른 값 경로를 만든 것) · 음성 대조 · 원래 결함 대조.
- **RF3**: 2026-11-12~20 달력 모의 + 미룸 최대 2평일 · 국내 피크 이틀 · 「FR만 든 국내 종목 = 0」.
- **RF4 단위 + 주입 대조**: 실물 사슬(로보스타 `[첨부정정]` · 엘앤에프 첨부정정 → 기재정정 · 휴온스 같은 날 기재정정 · 케이지에이 두 번 · 케이씨피드 `[첨부추가]` 원본) · 결산월(12 · 3 · NULL) · FSR OFS 폴백(케이뱅크) · 보충 FAILED·uncertain · 013 → 표지 멈춤 · 대조군 = 「FR 성공이면 전진」 규칙.
- **RF7 소비 재연(실키 · 쓰기 없음)**: 실제 `list.json`(2026-03-01~05-31 · 사업보고서·정정 시즌) ∩ 현 편입 × API `rcept_no`(FR · FSR CFS/OFS) → 소비 판정을 돌려 **미소비를 원인별로 0까지 분류**(`[첨부정정]` 제외 · 결산월 · OFS 폴백 · 진짜 한계 등록) · 대조군: 로보스타 · 엘앤에프 · 로젠꼴 · 신영증권 · 별도 전용 넷 · `[첨부추가]` 원본 · DART 콜 수를 먼저 세어 하루 한도 안으로 나눈다.

## 1부 v5 확인 반증 — 계획 안(「수정 후 진행」 · `rebut10/`) · 계획 밖(「수정 후 진행」 · `refute10/`)
- 안 1: 판 고정 시험이 규칙 열둘을 못 잡음(손으로 든 재료 · 캐시 인스턴스 530개에서 Q2 137 · Q1 112 · Q10 108 · Q8 35 · Q3 2개를 바꿈) → 분기 단위 재료.
- 안 2: 목록 실패 갈래에 같은 날 보호 없음(정상 갈래만 `removeAll`) · 안 3: 덮인 공시의 목록 날짜까지 엶(지금 0쌍).
- 밖 1: 첫 손 FR의 수용 기준·중단 조건·되돌리는 법 없음 · 2: 109 목록이 커밋 안 됨 · 3: 글자 명령 없음(선례마다 다른 플래그) · 4: 「빨리 감기로 합친다」 불가능(main이 base 뒤로 나감 · 1부는 reload-gate 충돌) · 5: 둘째 손 FR의 「A 새 대표」는 `--tickers`가 못 잡음 · 6: 스킬의 「SEC 종목당 1콜」이 1부 뒤 거짓 · 7: initial-load가 보충 칸을 안 실음.
- 덤(남의 결함 후보 · 소스에 안 물음): TXNM 2026 Q2 주식수 — dei(07-24) 103,046,111 · 같은 공시 us-gaap `CommonStockSharesOutstanding`(06-30) 111,045,010 · 순이익/EPS 함의 111.6M · dei 우선이라 어느 경로든 같은 값.

## 1부 v6(2026-10-06 · 보류 브랜치 `a134c8b8`·`47372ac4` · 시험 1,816/0 · A6 = A)
- 판 고정 재료를 분기 단위로(건너뛰는 조건마다 · 고르기 키 구성요소마다 · 공백 · 자기 컨텍스트 0 · 신원 행렬에 전환 보고서·다른 공시 · 폴더 조합) → **반증 변이 19개(Q1~Q13 · P1~P6) 전부 붉음**.
- 목록 실패에도 같은 날 보호 · 덮인 공시는 companyfacts 날짜만 엶 · initial-load note에 보충 칸 · 변이 셋(V20~V22) 붉음.
- 문서: ADR에 첫 손 FR 절차(같은 날 사본 재생을 사전 기대·사후 오라클로 · 글자 명령 · note 단언 · 되돌리는 표 집합 · 둘째 손 FR 목록 질의) · 109 목록과 질의(`p1-fr1/` · 내 질의로 다시 내 109 = 반증 목록) · RS1 109 몫 직접 셈(신규 128 · 정정 28 · 교정 3 · 이른판 0 · 회수 3 · 보충 없으면 정정 2) · main progress(merge 커밋 절차 · 낡은 B 문구).

## 2부 v3 반증 판정 — 계획 안 렌즈(2026-10-06 · **「다시 짠다」 — 표지 구조와 국내 짝의 비용·순서 모형** · `rebut-p2v3/` · DART 16콜)
1. **결산월 규칙이 3월 결산 사업보고서를 틀리게 잇는다** — 신영증권 「사업보고서 (2026.03)」을 API는 `bsns_year=2026`으로 준다(계획은 2025로 · `DartFinancialAdapter:308-311` javadoc을 반대로 옮김) · 분기는 맞음 · 결산월 NULL은 가짜 한계(가온칩스 `company.json` `acc_mt=12`) → **대응표를 버리고 소비 = 목록 `rcept_no`가 이번 실행이 받은 `rcept_no` 집합에 있는가** · 창 밖 기간의 정정은 판정에서 뺌(2026-04 1쪽 100건 중 7이 FY2018~2023 정정).
2. **날짜 표지 하나로는 「그 공시만 재시도」를 표현 못함** → 공시별 상태 표(1부 V64와 같은 꼴) · 출구 키 = `rcept_no`.
3. **짝의 원자성은 예외에만 선다** — FSR이 예외 없이 미소비(013·옛 번호)면 FR 행이 커밋돼 「FR만 든 종목」이 화해를 지남 · 셋째 날 강제 무거운 FR도 같은 상태 → 쓰기 전에 두 소비를 판정 · 무거운 FR은 국내 미소비 종목을 뺌.
4. **「피크는 DART 한도로 이틀」은 수도 원인도 틀림** — 짝 약 81콜(FR 36 · FSR 44.2~49.4) · 8.7~11.7초 · 25분이면 128~172짝(시각이 먼저 묶임) · 2026 피크 08-14 287 → D+3 · FSR 셋째 날과 겹치면 D+4 · **셋째 길**: `rcept_no` 키로 응답을 저장해 바뀐 것만 실콜(같은 번호 → 같은 응답 2/2 실측) → 채택·기각을 실측으로.
5. **「정기 FSR과 같은 인자(5년)」는 거짓** — `DEFAULT_YEARS_BACK = 8`(main javadoc 「5년」이 낡음).
6. **소요 모델(최근 성공 3회 중 최대)이 안 섬** — SCHEDULER 행 0 · FR 모델 16분 대 실제 약 47분 → 리미터 모형(`estimatedCalls` × 간격) + 이력은 SCHEDULER ∧ FULL만.
7. **미룸 「최대 2평일」이 `heavyJobDueToday`와 안 맞물림**(배정 요일 우선이라 영영 안 옴) · 「셋째 날 먼저」는 그물을 새 공시보다 앞에 둠 → 미룸 상한 = 신선도 예산(40일) · 단계는 늘 먼저.
8. 초기 −90 재검증(국내 349짝 + 미국 780 ≈ 3일)이 미국 파도 앞에 선다 → 선행 실행일을 기준점으로.
9. RF2에 원래 결함 대조군이 없다 → 사본에 유도 칸을 심어 좁힌 짝이 붉는지 먼저.
- 선다(쟀다): FSR OFS 폴백 = 어댑터 갈래 · CFS·OFS 같은 `rcept_no` · `[기재정정]` 다음 영업일 반영(3/3 · 2/2) · 3월 결산 분기 오프셋 · 짝 예외 롤백 · 단계 행 NARROW · 피크(2025-05-15 250 · 08-14 278 · 11-14 256 / 2026-05-15 256 · 08-14 287).

## 2부 v3 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v3/` · 0콜)
1. FSR 「5년」(안 5와 같음) → **같은 종목 단위 코드 경로를 부른다**(FR·FSR 루프에서 종목 하나를 처리하는 함수를 뽑아 단계와 `runHeavy`가 함께) · 풀린 인자가 같음을 시험으로.
2. **0부가 넘긴 수용 기준이 없다** — 「정기 실행의 실패 종목을 다음 평일에 집는 자리」(PLAN:230) · 정기 FR·FSR에서 실패한 종목은 새 공시가 없으면 한 달 → 대상에 「마지막 FULL 정기 실행의 미해결 실패 행이 있는 종목」.
3. 소요 모델의 입력이 비거나 틀림(안 6과 같음) · 「먼저」는 DART 우선권으로 정의.
4. **단계의 무효화가 무거운 잡 앞에 놓여 화면 공백이 매 평일** — FR 정정이 그 자리에서 지표를 지우고 복구는 화해(무거운 잡 뒤) · 2025 철 미국 정정 하루 최대 22 · p90 15 → 단계 끝에 사슬 안 화해(무효화 종목 ①+②).
5. 미국 미소비 정의가 1부와 두 벌 · UNREADABLE은 가짜 한계라 면제 금지 · FSR 「정정보류 0」 조건이 이유 없이 빠짐 → 1부 해소 술어 하나를 부름 · 출구는 국내 「원장이 재무 API에 안 줌」만 · 소비는 Writer 결과로.
6. **출구를 `KnownSourceGaps`에 더하면 그 등록부의 계약(SQL 조각 · `membershipGaps` 소비)이 깨짐** → `rcept_no` 키 별도 등록부 · 매 실행 「면제 낡음」 재기 · 7일 간격.
7. **주기 변경이 값의 경로를 바꾼다** — T+1에 companyfacts가 아직이면 보충 경로로 소비 · 주식수 동점은 경로마다 갈리고 덮어쓰기 안 됨 → 철 평일 단계 시각의 지연을 잼 · RF2에 두 시점 오라클 · 차이 나면 주식수 결정적 규칙을 2부 앞에.
8. 초기 표지의 선행 조건과 적용 대상이 다른 집합 → 교집합에만.
9. `DartCorpCodeCache` 갱신이 이유 없이 빠짐 → 못 찾으면 다시 받고 실패 기억 · 또는 `list.json`의 `corp_code`.
10. 순위: 피크의 원인은 25분 상한(약 166짝)이 먼저 · 편출 경계(금 공시 · 토 편출) → 「편입 구간이 (기준점, asOf]와 겹치는 종목」.
11. progress 146행 낡은 B 문구(main `a3568984`로 고침) · 매 평일 자동 FR이 되므로 FR 경로를 바꾸는 배포 직전 덤프를 규칙으로 · CLAUDE.md 제안문 보강.
- 선다(쟀다): FR 8년 · `financial-sync` 이름과 스위퍼 · 시계 · 관리 화면 버튼 없음 · 15:30 INDICATOR_DROP은 화해 뒤 · KSIC:642 두 종목 · 분기 `index.json` 403을 빈 분기로 읽어도 보수적 · 회수정지 = uncertain · 2부는 후보 쪽 낡음을 안 만듦.

# 2부 계획 v4 (2026-10-06 10시 · v3 두 렌즈 반영 · 표지 구조와 국내 짝 모형을 다시 지음)
**v3에서 버린 것**: 날짜 표지 하나(공시별 재시도·출구를 못 담음) · 결산월 대응표(3월 결산 사업보고서를 틀리게 이음) · 「피크는 DART 한도로 이틀」(원인이 25분 상한) · 「셋째 날 무거운 잡 먼저」(그물을 새 공시보다 앞에 둠) · 「최근 성공 3회 중 최대」(정기 이력 0) · `KnownSourceGaps`에 출구 더하기(그 등록부의 계약을 깸) · 「FSR 5년」(정기는 8년).

## v4 실측(10-06 · 내가 잼)
- 선행 실행 전부 SUCCESS · 실패 행 0: 국내 FR #645(200)·#646(150) 09-21 · #658(150) 09-22 · FSR #647(200)·#648(150) 09-21 · #663(200)·#664(129) 09-23 · 미국 FR #683(1,000) 09-28 01:14 KST.
- 반증이 잰 것(옮김): 짝 = FR 36콜(`estimatedCalls` · 9년 × 4) + FSR 44.2~49.4콜 ≈ 81콜 · 8.7~11.7초 · `[기재정정]` 다음 영업일 반영(FR 3/3 · FSR 2/2) · 같은 `rcept_no` → 같은 응답 2/2 · 피크 2025-05-15 250 · 08-14 278 · 11-14 256 / 2026-05-15 256 · 08-14 287.

## 결정
1. **공시별 상태 표**(V65 · 새 ADR · 1부 V64와 같은 꼴): `financial_filing_sync(market, stock_id, accession, report_name, filed, first_seen_at, last_tried_at, tries, consumed_at, exit_id)` — 원장 목록에서 처음 본 정기 공시가 행이 되고, 그 종목의 실행이 소비하면 `consumed_at`을 쓴다. **종목 기준점** `stock_financial_baseline(stock_id, baseline_date, source_execution_id)` — 기준점 이하 제출일의 공시는 지난 전 창 실행이 소비했다고 본다.
2. **소비 판정 = 접수번호가 이번 실행의 입력에 있는가**(결산월 대응표 없음):
   - 미국: 1부의 접수번호별 해소 술어 하나를 부른다 — companyfacts us-gaap에 있음 ∨ 보충이 실음(OK·NO_XBRL·COVERED · 현재 판) ∨ submissions `isXBRL=0` → 소비 · FAILED · 옛 판 사용(`stale_used_at`) · 목록 실패 → 미소비(1부 미해결과 같은 정의).
   - 국내: 목록 `rcept_no`(`[첨부정정]` 항목은 빼고 · `[첨부추가]`·`[기재정정]`·원본은 남김) ≤ 이번 실행의 FR이 받은 `rcept_no` 중 같은 보고서 이름의 최대 ∧ FSR도 같음 ∧ 그 종목의 FSR `deferredRevisions` = 0 → 소비 · 창 밖 기간의 공시(FR 창 2018 앞 · 2026-04 1쪽 7/100 꼴)는 판정에서 뺀다(`exit = OUT_OF_WINDOW`).
3. **대상**(시장마다 · asOf = 단계 시작의 시장 날짜 − 1 · 모집단 = 편입 구간이 (기준점, asOf]와 겹치는 종목 · 그 구간 안의 공시만):
   - 미소비 공시가 있는 종목(같은 공시의 재시도는 `tries` ≥ 5평일이면 주 1회 — 그 종목의 새 공시는 그날 바로)
   - 기준점이 없는 종목(배포 뒤 들어온 편입 · 재편입으로 기준점이 앞 분기 시작보다 오래됨) → 전 창
   - **마지막 FULL 정기 FR·FSR에서 미해결 실패 행이 있는 종목**(0부가 넘긴 수용 기준 · PLAN:230) → 전 창
4. **같은 종목 단위 코드 경로**: FR·FSR 잡의 종목 루프 몸통을 함수로 뽑고(`FinancialRefillJob.refillOne` · `FullStatementRefillJob.refillOne` — 풀린 연수·기간·쓰기 모드·이른 판 플래그를 그대로) 단계와 정기 실행이 같은 함수를 부른다 · 시험: 단계가 넘기는 풀린 인자 = 정기 인자.
5. **국내 짝의 원자성**: 한 `REQUIRES_NEW` 안에서 FR → FSR → **소비 판정을 쓰기 커밋 전에** — FSR이 FR이 받은 보고서를 소비하지 못하면(013 · 옛 번호 · 정정보류 > 0) 예외로 롤백(FR 행도 안 남음 · 「FR만 든 종목」 0) · 다음 평일 재시도 · **정기 FR(무거운 잡)도 미소비 공시가 있는 국내 종목은 뺀다**(그 종목은 단계가 짝으로).
6. **국내 짝은 정기와 같은 창** — 좁히면 Writer 관할(`managedColumns`)·`lastNoncontrollingIncome`이 호출 단위로 갈려 다른 값 경로가 생긴다 · **셋째 길(`rcept_no` 키 응답 저장 · 바뀐 것만 실콜)은 버린 대안**: 같은 번호 → 같은 응답이 2/2라 DART가 번호 없이 값을 고친 경우를 못 가르고, 그러면 저장 응답 경로가 정기 FR과 갈린다 — 채택하려면 시간 간격을 둔 표본으로 불변성을 먼저 재야 한다(재지 않음 · 후속 측정으로 남김).
7. **단계 시각**: 14:15 단계(늘 먼저) → 단계 끝에 **사슬 안 화해**(이 단계가 무효화한 종목의 ①+② · 14:00 사슬과 같은 꼴 · 화면 공백을 단계 안에서 닫음) → 14:16 무거운 잡 → 14:18 유도 → 14:20 화해 · 단계 상한 = 무거운 잡이 그날 돌 예정이면 「15:28 − 뒤 사슬 − 그 잡의 소요」까지 · 아니면 15:05까지(25분 상한을 걷음).
8. **무거운 잡의 소요·DART 필요량 = 리미터 모형**(그 잡의 `estimatedCalls` × 소스별 간격 · ADR-0116 모형 · 보충 몫 포함) · 이력은 SCHEDULER ∧ FULL 행이 3회 쌓인 뒤에만 큰 쪽 · 미룸 = 시각 또는 DART가 안 맞음 · **미룸 상한 = 신선도 예산(40일)** — 「마지막 성공 + 38일」이 되면 그날은 무거운 잡에 DART 우선권(단계 국내 몫 = 잔량 − 그 잡의 필요량)과 시각 우선권(단계 상한을 그 잡이 들어가게).
9. **판정 출구 = 별도 등록부**(`rcept_no` 키 · 국내 「원장이 재무 API에 그 보고서를 안 줌」만 · 근거·일시 · 2부만 읽음 · `KnownSourceGaps`·`membershipGaps`에 배선 안 함) · 매 실행 「API가 이제 그 번호 이상을 주는가」를 재 면제 낡음을 알림 · **미국 UNREADABLE은 출구 없음**(우리 파서의 실패 = 가짜 한계 · 결함 경보로 남김).
10. **초기 기준점**(V65): 배포 시점 편입 ∩ 선행 실행 대상(실패 행 없음)에만 — 국내 = FR·FSR 둘 다 덮은 실행일 중 늦은 쪽 − 1(09-22) · 미국 = #683 시작의 ET 날짜 − 1(09-26) · 1부 첫 손 FR 109는 그 실행일 − 1 · 나머지는 기준점 없음 → 전 창. 기준점 뒤 공시만 판정(재검증 쓸기 없음 · 미국 파도 앞에 안 쌓임).
11. **`DartCorpCodeCache`**: 종목코드를 못 찾으면 그날 한 번 다시 받고 실패를 기억 · 옛 맵 유지(B v7 결정 그대로).
12. **감시(15:30 · 단계 결과 행 + 공시별 표에서)**: ① 미소비 3평일(종목·접수번호 · 같은 공시 7일 간격) ② 확인 상한 지연 ③ 이월 3평일 ④ 무거운 잡 미룸(사유) ⑤ 기준점 없는 편입 ⑥ 단계가 돌았는데 최신 지표일 0행 ⑦ 면제 낡음.
13. **경로 차이 측정**: 단계 note에 「제출 ≤ 2일 공시를 보충으로 소비한 수」 · RF에 두 시점 오라클 — 차이가 나면 주식수 동점의 결정적 규칙(관문 재고)을 2부 앞으로 당긴다.
14. **배포 규칙**: FR 경로를 바꾸는 배포 직전 덤프(매 평일 자동 FR이 되므로) · CLAUDE.md 제안문 — 「꺼진 jar가 뜨면 정기 refill과 화해가 되돌린다」에 평일 14:15 단계를, 「설정 밖 어댑터 변경은 다음 14:15부터 공시 낸 종목에만 새어 중간 상태를 만든다」를 함께(사용자 결정 문단이라 구현 때 제안).
15. **배포 목표 ≤ 10-26** 유지 · 소비자 목록은 v3 13 + 0부 수용 기준 + `FinancialRefillJob` 알림 문구(GAP_FILL) + `FullStatementRefillJob:68` 낡은 javadoc(「5년」).

## 2부 드라이런 규칙 v4(대조 전 커밋 · 실키는 평일 13:20~15:45 밖 · 순차)
- **RF0**: 실물 `index.json`(2025 QTR4 휴무 · 2026 QTR2 성금요일 · 분기 첫날 403) · `list.json` 정정 사슬(로보스타 · 엘앤에프 · 휴온스 · 케이지에이 · 케이씨피드 `[첨부추가]` · 나노 창 밖 정정).
- **RF1**: 대상 재연 — 여러 날짜(콜럼버스 · 재향군인 · 미국 최대일 · 국내 피크 · 성금요일 뒤) · DB `published_at` ⊆ 대상.
- **RF2**: 사본 차분 a1 → b → a2 · a = 0부+1부 jar의 정기 인자 손 FR·FSR · b = 단계 · 행 단위 같음 · **원래 결함 대조**: 사본에 유도 칸(088350 매출 · 277810 순이익의 2025 Q4)을 심고 좁힌 짝이면 붉은지 먼저 본 뒤 단계가 초록인지 · 음성 대조(비대상 바이트 동일) · **두 시점 오라클**(T+1 단계 → T+20 FR 대 T+20 FR만 · 미국).
- **RF3**: 달력 모의 2026-11-12~20 + 첫 정기 FR(이력 0)인 날 + 「마지막 성공 + 38일」 날 · 국내 피크 287 · 「FR만 든 국내 종목 = 0」 · 15:30 전 끝 · 이월 최악을 수로.
- **RF4 단위 + 주입**: 소비 판정(미국 1부 술어 · 국내 `rcept_no` · `[첨부정정]` · 창 밖) · 짝 롤백(FSR 013 · 옛 번호 · 정정보류) · 공시별 재시도 간격 · 기준점 · 출구와 면제 낡음 · 대조군 = 「FR 성공이면 소비」 규칙.
- **RF5 가드** · **RF6 비용 실측**(짝 초·콜 · 미국 FR 초).
- **RF7 소비 재연**: 실제 `list.json`(2026-03-01~06-30 — 신영 사업보고서 06-11 포함) ∩ 편입 × API `rcept_no` → 미소비를 원인별로 0까지.

## 1부 v6 확인 반증 — 두 렌즈 「수정 후 진행」(2026-10-06 · `rebut11/` · `refute11/` · v7 처방 대기 · 세션 한도로 여기서 멈춤)
- 안 1: 판 고정이 아직 손 목록 — 새 변이 20 중 12 생존(실물 영향 0) → **판 범위 메서드의 소스 해시**(정규형)를 출력 해시 옆에 박아 「판 올림 / 행동 불변 재고정」을 같은 커밋에서 고르게 · FOLDERS에 「후보 둘 · 산출물 없음」.
- 안 2: `SecFilingClient.folder`가 `directory.item` 없음·빈 응답을 빈 목록으로 → 영구 `NO_XBRL`(캐시 567개 전부 항목 ≥ 1이라 빈 목록은 응답 이상) → MALFORMED로 던져 다음 실행이 다시 받게 + 시험.
- 안 3·4 / 밖 2: 첫 손 FR 2단계(사본 재생) 명령이 없고 하네스 기본값(`Q4V_REPLAY_FROM=2015` · `INSERT_EARLIER` 기본 참)이 운영과 다름 · 보충실패·회수정지·회수를 하네스가 안 냄 → `p1rs/rs1.sh` 꼴 글자 명령(FROM=2018 · INSERT_EARLIER=false · STORE=db · `stock_id<TAB>ticker` 대상) · 하네스에 세 합계.
- 안 5 / 밖 3: 사후 대조에 `share_basis_*`가 들고 운영만 유도가 돎 → 사본에도 `share-basis-derive` · 수집 뒤 공시 설명 조항 · 차이를 셋으로 가름.
- 안 6: 되돌리는 표에 `indicator_trade_date` 없음 · 안 7: 목록 실패 실행이 FAILED 행을 `touch` 안 함(작음).
- 밖 1: **표째 `pg_restore -t`는 identity·pkey·unique·index를 잃고 남의 쓰기까지 지움** → 종목 단위 되돌리기(덤프 → scratch DB → 한 트랜잭션에서 109 stock_id의 세 표 지우고 `OVERRIDING SYSTEM VALUE`로 다시 넣기 → `share-basis-derive` → `indicator-reconcile --tickers`) · 원시 SQL이라 별도 승인 · `ops/dump-db.sh`의 P9 좁힌 복원도 같은 형태(남의 결함 · 보고).
- 밖 4: progress의 합치기 단언(`git diff HEAD origin/hold -- src …` 비었음)이 지금 거짓(main 7b0a0ff3·600ad83f 몫) → 경로별 단언(P = merge-base..hold 경로는 hold와 같고 그 밖은 HEAD^1과 같음).
- 밖 5: reload-gate 충돌을 「양쪽 다」로 하면 행 중복·낡은 문구 부활 → 행마다 출처((가) p1 · (나)·관측 평면 main).
- 밖 6: 둘째 손 FR — ADR 두 문장과 progress:56이 갈림(A 새 대표 포함 여부) → 하나로.
- 밖 7: 운영 명령이 무엇을 컴파일하는지 단언 없음 → HEAD = 배포 커밋 · `git status --porcelain -- src` 빔 · V64 적용 확인.
- 밖 8: `SecFilingSupplement.settle` javadoc이 아직 「목록 날짜와 filed 둘 다」(코드는 filed만) → 같은 브랜치에서 고침.
- 밖 9: `p1-fr1/`은 이 탐색 브랜치에만 · `.sql` 재현에 사본 rs1a·rs1b(각 7.4GB) 필요 → 보존 조건을 적음.
- 대기: 2부 v4 두 렌즈(`rebut-p2v4/` · `refute-p2v4/`)는 세션 한도 때 아직 도는 중이었다 — 다음 세션이 그 산출물을 읽는다.

## 2부 v4 반증 판정 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행 — 3은 기각한 대안을 실측으로 다시 연다」 · `rebut-p2v4/` · DART 17콜 10:09~10:18)
1. **국내 「같은 보고서 이름」의 출처가 없다** — FR·FSR 응답에 `report_nm`이 없고 FSR은 `thstrm_dt`도 없음 → **목록 조인**: FR·FSR이 받은 `rcept_no`를 같은 실행의 목록에서 찾아 그 항목의 이름을 쓴다(원익홀딩스 `[기재정정]` `20260923000536` · 신영 사업보고서 (2026.03) = `bsns_year` 2026 · 이지스 오늘 정정 `20261006000023`을 FR·FSR이 같은 날 줌) · 이름 찾기용 목록은 오늘까지, 판정은 어제까지 · RF4 변이 대조(종류만으로 묶으면 1분기·3분기가 붙어 붉어야) · RF7에 거짓 소비 오라클.
2. **리미터 모형이 미국 FR·FSR에서 과소** — 미국 FR 실측 #683 16:00.8 · #643 16:52.7 · #653 15:04.3(종목당 0.90~1.01초) 대 모형 3:20(4.5~5배) · FSR #647+#648 42:31 대 26:24(+61%) · #663+#664는 +9% · 국내 FR +8~14% · 정기 FR 전체 15~23분 과소 → 시장·소스마다 max(호출 × 간격, 종목 × 실측 초/종목) · `FinancialRefillJob.estimatedCalls`(135행)는 미국에도 ×36이라 시장별로 · RF3에 실측 초/종목 주입.
3. **셋째 길의 기각 근거가 안 섬** — 005930 FY2025·Q3 2025 응답이 08-06 픽스처와 14/14행 같음(61일) + v3의 2/2 = 4/4 · 「번호 없이 값을 고침」은 v3 결정 9가 이미 받아들인 사정거리(월 정기가 닫음) · 저장 응답 = 「마지막으로 새로 받은 응답」 · 정기 실행이 갱신 → 단계의 Writer 입력은 전 창 그대로 · 버린 대가: 287 피크에서 D+2 약 84~97종목 · 정기 FSR은 단계 국내 ≤ 5짝인 날만 맞음 · 셋째 길이면 짝당 약 3~5콜(재지 않음) → **불변성 표본을 넓혀 다시 판정 · 서면 채택이 기본**.
4. **짝 롤백 + 정기 FR 제외 + 0부 FULL 시계 = 흡수 상태** — FSR이 끝내 소비 못 하는 종목은 매일 롤백 · 정기 FR에서 빠짐 · FR 시계는 초록 → FR 컬럼이 이후 공시까지 멈춤 · 롤백을 예외로 하면 실패 행·ERROR·Sentry가 종목마다 · 지금 가능성은 낮음(정정 같은 번호 10/10 · 정정보류 6회 0 · FSR 칸 다 빈 종목 1/348 카카오뱅크 OFS) → 롤백은 N평일·N회까지 · 그 뒤 FR 커밋 + FSR 미소비를 출구에 · 롤백은 실패 행이 아닌 상태 값 · 정기 FR이 뺀 수를 note에.
5. **국내 기준점 방향이 틀림(늦은 쪽 → 이른 쪽)** — FR #645 09-21 22:15 · #658 09-22 09:43은 09-22 업무시간 공시를 못 봄 → 구성원마다 min(FR 시작, FSR 시작) 날짜 · 지금 영향 0(09-18~09-23 현 편입 정기 공시는 원익 09-23뿐) · **「`rcept_dt` = `rcept_no` 앞 8자리 204/204」는 거짓 — 저장 571건 중 4건이 하루 늦음**(`20260921000435` → 09-22) · `filed`는 `rcept_dt`로 통일.
6. 「FR만 든 국내 종목 = 0」이 목록에 의존 — 목록 실패일엔 국내 정기 FR을 미룸 · 빠진 종목에 정기 FSR이 돌면 새 기간 행을 FSR이 먼저 넣음(`writeOne`은 행이 없으면 모드와 무관하게 넣음).
7. 드라이런 — RF2에 v3의 FSR 5년 인자 변이 대조 · 창 밖 = 「(YYYY.MM)의 YYYY < asOf 연도 − 8」(나노 정정은 창 안이었다) · 강제(+38)는 「그 이전 마지막 영업일」로.
- 선다(쟀다): 미국 기준점 09-26(현 편입 정기 공시 31 중 30이 DB 행 · BB는 10-02 편입) · 국내 기준점 영향 0 · 목록 조인은 결산월 없이 섬 · 접두 셋뿐 한 겹(546건 중 첨부추가 103 · 기재정정 101 · 첨부정정 6) · FR·FSR 정정 같은 번호 10/10 · 같은 날 반영 · 같은 번호 → 같은 응답 4/4 · 정기 FSR 정정보류 0 · 287 피크는 DART가 먼저 묶음 · **원익홀딩스(현 편입) 09-23 정정을 13일째 미소비**(DB 최신 rev 0 · 값 같음 · 첫 단계의 실물 대조군).

## 2부 v4 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v4/` · 0콜)
1. **재편입이 B v7보다 좁아짐** — 미국 주별 편입 대부분 재편입(09-18 13/15 · 09-25 12/14 · 10-02 11/13 · 빈 구간 7~77일) · 빈 구간에 공시가 있던 재편입 2025-11 13/31 · 2026-05 13/42 · 2026-02 12/45 → B v7의 「편입 시작 > 기준점 → 전 창」 되살림 · 모집단 「현 편입 ∪ 편출 경계」 · 기준점 전진 규칙.
2. **「미해결 실패 행」의 해결 표지가 FR·FSR 공유** — 둘 다 `CollectionPhase.FINANCIAL` · FSR 실패 2,002행 전부 미국인데 전부 `resolved=true`(FR·initial-load 성공이 찍음) → 술어 = 「그 실패 뒤 같은 잡 또는 짝이 그 종목에서 성공」 · G의 주인·마감.
3. **단계 상한과 무거운 잡 미룸이 순환** — 상한은 시작 때 「돌 예정이면」으로, DART 판정은 단계 뒤 → 단계 시작 전에 예상 DART 필요량(미소비 국내 × 81)으로 무거운 잡 적합을 먼저 정함 · RF3에 11-17.
4. **상한 식에 사슬 안 화해가 없음** — ②는 늘 창 전체 7:57 · G 날 끝 15:35:57 → 상한에서 ①+② 최악을 뺌 · 가드 시험에 그 항.
5. **짝 롤백 신호와 D+1 반영 시각** — `RuntimeException` 롤백은 `UNEXPECTED` 실패 행 · Sentry · `ck_failure_kind`에 미소비 없음 → 짝 앞에 1콜 탐침(`rcept_no` 비교) · 롤백은 결과 행에만 · 반영 시각을 실키로(평일 여러 시각).
6. **지속 미소비에서 「주기 변경」 전제가 깨짐**(안 4와 같은 뿌리) → N평일 뒤 FR 몫 커밋 + 그 라벨 FSR 칸 진짜 한계 미표시 · 제외 자리를 `runHeavy`로 · `scopeOf`가 FULL 유지 시험 · 손·관문 FR이 `consumed_at`을 쓰는지 · 정기 FSR도 미소비 종목 뺌.
7. **초기 기준점**(안 5와 같음) + 「실패 행 없음」이 보충 FAILED를 못 봄 → V65 술어에 · 대상 목록 재구성(#683 = 그 시각 편입 · 109 = 커밋 목록).
8. **단계가 안 돈 날을 아무도 신고 안 함** — GAP_FILL은 신선도 두 갈래 다에서 빠짐 → 감시 「`max(run_date)`」 되살림.
9. RF2가 `financial_statement`만 비교 → V64 · `financial_column_absence` · 실패 해결 · 무효화 집합도 · `refillOne` 리팩터는 정기 경로가 0부+1부 jar와 바이트 같은지 따로.
10. **이번 세션 회귀** — a) progress 합치기 단언 불성립(main 7b0a0ff3·600ad83f가 src를 바꿈) → 「합친 delta = 브랜치 delta」 + 합친 트리 전체 시험 · b) 7b0a0ff3의 `list.json` 판정은 1·3분기 창을 안 물었고 `list.json` 대조군 없음 → RF7 모집단에 두 종목 · c) api-sources 스킬 표(`:78` list 미착수 · `:81` companyfacts 종목당 1 · `:86` submissions) · 일일 색인·Archives 인스턴스 없음.
- 선다(쟀다): 선행 실행 SUCCESS·실패 0(다만 #657 FSR 150은 ABORTED) · 짝 롤백이 무효화·부재·보충 저장까지 함께 되돌림 · `04-refill.sh`는 미국 전용 · `00-precheck` RUNNING 0 검사 · 40일 = 신선도 예산 · 국내 현 편입 전부 보통주 · V63 ⊂ 1부 · V65 뒤 · 국내 재무 행 공시일 09-18~10-06 0.

# 2부 계획 v5 (2026-10-06 14시 · v4 두 렌즈 반영 · v4 결정 중 바뀐 것만)
1. **국내 소비 = 목록 조인**: FR·FSR이 받은 `rcept_no`를 같은 실행의 `list.json`에서 찾아 그 항목의 보고서 이름(정정 접두를 뗀 「분기보고서 (2025.09)」)을 얻는다 · 목록 공시 R(`[첨부정정]` 제외)은 FR과 FSR 각각이 「같은 이름 · `rcept_no` ≥ R」인 응답을 받았으면 소비 · **이름 찾기용 목록은 오늘까지 받고 판정 대상은 어제까지**(같은 날 정정을 API가 이미 줌 — 이지스 `20261006000023`) · 이름 없이 종류만으로 묶으면 1·3분기가 붙는다(RF4 변이 대조) · RF7에 거짓 소비 오라클(소비로 판정된 항목마다 FR의 `bsns_year`·`reprt_code`·`thstrm_dt`로 이름 검사).
2. **짝 앞 1콜 탐침**: 미소비 공시 R의 (사업연도, 보고서코드) 후보 = 「(YYYY.MM)의 YYYY」 · 종류 + 결산월 오프셋(3월 결산 분기 오프셋 실측 · 결산월 NULL은 `company.json` `acc_mt`로 채움 — 가온칩스 실측) → `fnlttSinglAcnt` 한 콜의 `rcept_no` < R이면 **오늘은 그 종목 짝을 안 돈다**(아직 반영 안 됨 · 81콜을 롤백으로 버리지 않음 · 결과 행에 「반영 대기」) · 탐침이 틀린 짝을 골라도 짝 뒤 목록 조인이 판정하므로 거짓 소비는 안 생긴다.
3. **셋째 길(응답 저장)은 실측으로 다시 판정 — 서면 채택**: 저장 = (corp_code, 사업연도, 보고서코드, fs_div, 엔드포인트)마다 「마지막으로 새로 받은 응답」(`rcept_no` 포함) · 단계는 탐침이 새 번호를 본 (연도, 코드)만 실콜 · 나머지는 저장 응답 → 어댑터 입력은 전 창 그대로(`managedColumns`·`lastNoncontrollingIncome` 같은 경로) · 정기 FR·FSR이 매번 저장을 갱신(번호 없는 값 고침은 v3 결정 9의 사정거리 = 월 정기가 닫음) · **채택 조건**: 「같은 `rcept_no` → 같은 응답」 불변성을 넓힌 표본(라벨당 1콜 · DB의 09-21 FR 값 대 오늘 API · 20종목)으로 0 차이 · 1이라도 다르면 기각하고 그 차이를 원장에 묻는다 · 기각 시 v4(전 창 짝 · 피크 D+2 약 84~97종목)로.
4. **롤백 상한과 흡수 상태 출구**: 롤백은 실패 행·예외가 아니라 상태 값(`financial_filing_sync.last_result`)으로 · 같은 공시의 롤백이 5평일(또는 5회)이면 FR 몫을 커밋하고 그 라벨의 FSR 전용 칸은 진짜 한계로 미표시 + 출구 등록(판정 네 요소 · 매 실행 면제 낡음 재기) · 출구는 롤백 판정과 정기 실행 제외 둘 다에 반영 · 정기 FR·FSR이 뺀 종목 수를 note에.
5. **정기 실행 제외 자리 = `PipelineScheduler.runHeavy`**(정기 FR·FSR 둘 다 · 국내 미소비 종목) · `scopeOf`가 FULL을 유지함을 시험으로(NARROW로 찍히면 매 평일 돈다) · 목록을 못 받은 날은 국내 정기 FR·FSR을 미룸 · 손·관문 FR도 같은 종목 단위 경로라 `consumed_at`을 쓴다.
6. **재편입 = 전 창**(B v7 「편입 시작 > 기준점」 되살림) · 모집단 = 현 편입 ∪ 편출 경계(편입 구간이 (기준점, asOf]와 겹침 · 편출 뒤 공시는 판정 안 함) · 기준점은 전 창 실행이 성공할 때 그 실행일 − 1로 전진.
7. **미해결 실패 술어 = 「그 실패 뒤 같은 잡(또는 짝)이 그 종목에서 성공」**(FR·FSR이 `CollectionPhase.FINANCIAL`을 공유해 해결 표지가 섞임 — FSR 미국 실패 2,002행이 FR 성공으로 전부 해결됨) · **G(거버넌스)의 실패 재시도는 2부 밖** — 주인 = 별도 항목(재고) · 마감 = 다음 정기 G(11-16) 전.
8. **시각·DART 순서**: 단계 시작 전에 「예상 단계 DART = 반영 대기를 뺀 국내 대상 × (탐침 1 + 짝 콜)」로 그날 무거운 잡 적합을 먼저 정하고(DART 잔량 · 소요) 그 뒤 단계 상한을 계산 · 상한에서 사슬 안 화해(① + ② 최악 7:57)를 뺀다 · 가드 시험(`PipelineSchedulerTest`)에 그 항.
9. **무거운 잡 소요 = 시장·소스마다 max(호출 × 간격, 종목 × 실측 초/종목)**(미국 FR 0.90~1.01초/종목 · FSR 실측 42:31 — 리미터 모형은 미국 FR을 4.5~5배 과소) · `FinancialRefillJob.estimatedCalls`는 시장별로 나눠 셈 · RF3에 실측 초/종목 주입.
10. **기준점 = 구성원마다 min(FR 시작, FSR 시작)의 날짜 − 1** · 공시 날짜 = `rcept_dt`(저장 571건 중 4건이 `rcept_no` 앞 8자리보다 하루 늦음 — v1 「204/204」는 표본이 좁았다) · V65 술어에 「그 선행 실행의 대상 ∧ 실패 행 없음 ∧ 보충 FAILED 없음」 · 대상 재구성: #683 = 그 시각 편입 · 109 = 커밋 목록 · #657(FSR 150) ABORTED는 선행 실행에서 뺀다.
11. **감시 ⑧ 단계가 안 돈 날**(`max(run_date)` · GAP_FILL은 신선도 두 갈래에서 빠지므로).
12. **창 밖 = 「(YYYY.MM)의 YYYY < asOf 연도 − 8」** · 강제(+38)는 「그 이전 마지막 영업일」.
13. **소비자 더**: api-sources 스킬 표(`list` 미착수 · companyfacts 종목당 1 · submissions 용도 · 일일 색인·Archives 인스턴스 없음) · 7b0a0ff3의 `list.json` 판정은 1·3분기 창을 안 물었다 → RF7 모집단에 088980·415640과 `list.json` 대조군.
- **드라이런 v5 더함**: RF2 — `financial_statement` 밖 표(V64 · `financial_column_absence` · 실패 해결 · 무효화 집합)도 비교 · `refillOne` 리팩터 뒤 정기 경로가 0부+1부 jar와 바이트까지 같은지 따로 · v3 결함(FSR 5년) 변이 대조 · RF3 — 11-17(FSR 연체 · 국내 피크 D+1) · 첫 정기 FR 날 · 실측 초/종목 · RF4 — 목록 조인 · 탐침 · 롤백 상한 · 출구 · 실패 술어 · RF7 — 088980·415640 · 이름 오라클 · **반영 시각 실측**(평일 여러 시각 · 어제 공시 표본 · 실키 · RF7은 이미 반영된 과거라 못 잰다).

## 2부 v5 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v5/` · 0콜)
1. **시각 모형이 벽시계 절대값과 크론 분 간격에 기댄다** — 오늘 크론이 기계 수면만큼 밀렸다(아래) · (a) 15:05·15:28 절대 상한이면 1시간 넘게 밀린 날 단계 0 · 무거운 잡 미룸 · 감시가 원인을 잘못 귀속 (b) 「늘 먼저」가 14:15→14:16의 1분 간격에만 기댐 — Spring 7.0.8은 작업마다 본체가 끝난 시점에 「목표 − 벽시계」를 단조 지연으로 고정(`q07`) → 단계 끝과 무거운 잡 사이에 1분 넘게 잠들면 다음 날 순서가 뒤집혀 「FR만 든 국내 종목」 (c) 주말 장수면이면 같은 날 두 번 발화(결과 행 키 충돌) → 상한 = 실제 시작 + 예산(상대) · 늦게 시작한 날의 행동 명시 · 무거운 잡은 「오늘 단계 결과 행에 국내 목록 OK」가 없으면 미룸(또는 단계 → 무거운 잡 한 사슬) · 결과 행 하루 여러 행 · RF3에 +2h · 역전 · 이중 발화.
2. **손 쓰기 창·배포 관문이 벽시계 기준이라 밀린 날 「유일한 방어」가 빈다** — `batch-load/SKILL.md:582` · `data-pipeline.md:182` · `00-precheck.sh:9-13` · `deploy.sh:28-30` · `stop.sh:61` · 실키 창도 같은 규칙 → 공통 술어를 사건 기준으로(「오늘 SCHEDULER `indicator-derived-rebuild` 행이 끝남」 · 2부 뒤엔 단계·무거운 잡 행도) · **오늘은 화해 행이 끝난 뒤 배포**.
3. **롤백 상한 뒤 「FSR 칸 진짜 한계 미표시」가 전제를 깬다** — (a) 원인을 묻지 않고 자동 라벨(판정 네 요소 없음) (b) 미표시가 계산기에 안 닿음 — 지배주주 자본 NULL이면 `PbrCalculator:81` → `FinancialMath:111-113`이 총자본으로 물러나 ADR-0159가 금지한 짝 · 노출 349 중 86(×0.9 미만) · 14(×0.5 미만) · `PbrMedian5y`·`Roe`·`RoeMin3y` → 출구는 「FR 커밋 + 판정 요청」 · 라벨은 원장에 물은 뒤 · 미표시는 출구 표지를 읽는 계산기 규칙을 무해한 선행 배포로(표지 0행이면 값 불변을 바이트로 단언).
4. **탐침 「반영 대기」가 롤백 상한을 우회** — 대기는 롤백으로 안 세는데 정기 FR·FSR에선 빠짐 → 흡수 상태 → 상한을 first_seen부터의 평일로(대기·롤백 공통) · 정기 제외는 「갈릴 수 있는 짝」으로 좁히거나 상한 뒤 해제.
5. **셋째 길** — 「번호 없는 값 고침은 월 정기가 닫음」을 정기에서 빠진 미소비 종목에 이식(④) · 불변성 표본이 `fnlttSinglAcnt`만(FSR이 저장 응답 대부분이자 지배주주 자본의 출처) · DB에 국내 `rcept_no` 0 · 손·관문 FR이 저장을 읽는지 미정 → 표본에 FSR(CFS·OFS)·같은 날 정정 종목 · 원시 응답끼리 비교 · 저장은 단계만 읽고 정기·손·관문은 늘 실콜 후 갱신(시험으로).
6. 순위 — 0부 이월(G 실패 재시도) 행이 reload-gate·progress에 0 · `reload-gate.md:70` PARTIAL 행은 아직 「0부와 독립 · ?」(FR·FSR 몫은 v5 결정 7이 닫음 → 행을 가름) · (나)의 채택 실측 둘 미측정.
7. **이번 세션 회귀** — `progress.md:56`이 v3에서 버린 결정을 현재 상태로 적음(main `3720df76`로 고침 — 설계는 PLAN만 가리킴) · 544f82e0 합치기 단언: P ∩ main 변경 = `reload-gate.md` → P 단언에서 빼고 행 단위로 · 공시일 정의가 둘(결정 10 `rcept_dt` 대 DB `published_at` = `rcept_no` 앞 8자리 · KNOWN_ON이 읽는 쪽) → 571 중 4건이 하루 갈림.
8. (작음) `usedToday`는 JVM 메모리라 세션 실키를 못 봄 → 무거운 잡 날 아침 실키 금지 · 결산월은 기존 `DartCompanyProfileAdapter:145` 경로(현 편입 350/350 채워짐).
- 선다(쟀다): **밀림 원인 = 10-05 18:55~20:51 덮개 닫은 배터리 수면 합 6,912초(pmset) ≈ JVM 단조 차이 6,890초** · AC의 Idle Sleep은 DarkWake라 안 쌓임 · `caffeinate -s`는 AC에서만 막도록 의도(`start.sh:66-68`) · 오늘 크론은 전부 10-05 15:43:37 기동 한 시각에 예약 → 고르게 밀려 순서 보존 · 30일간 13:30·14:00 발화 ±3초 · 밀림은 2부보다 작은 축(37평일 중 오늘과 08-19 +7:39 · 밀림으로 틀린 값 관측 0) · `uq_fs_latest`·`uq_fs_revision`이 두 JVM 재무 쓰기 경합을 실패로 드러냄.

## 1부 v7(2026-10-06 · 보류 브랜치 `a9d1f03c`·`8789b6b2`·`f9a1d4c7` · 시험 1,818/0)
- v6 두 렌즈 반영: 판 범위 소스 해시(`SOURCE_PINNED`) · 빈 폴더 색인 → MALFORMED · 목록 실패 실행도 보호 행을 `touch` · 하네스 두 값 필수 + 세 합계 · `settle` javadoc · ADR-0173 첫 손 FR 절차(종목 단위 되돌리기 · 사본 재생과 사본 `share-basis-derive`를 글자 그대로).

## 1부 v7 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute12/` · 0콜 · DB는 SELECT만 · 병합 모의는 `--shared` bare 클론)
1. **손 쓰기 창(평일 13:30~15:40 밖)이 벽시계 기준이라 밀린 날 안 선다 — 교차 JVM 배타는 이것 하나뿐**(④·②) · 운영 FR·화해가 앱의 밀린 화해와 겹치면 교착 선례(#305) · 덤프와 FR 사이 앱 쓰기가 사후 대조를 (다)로 오판시키고 `_base` 되돌림이 그 쓰기까지 지움 → 절차 전체를 **앱을 내린 채**(관문과 같은 꼴) + 내리기 전에 「오늘 크론이 다 돌았다」를 시계가 아니라 기록으로(오늘 SCHEDULER 화해 행 끝남 · RUNNING 0) · 각 단계 직전 지연 L(벽시계 경과 − `jcmd VM.uptime`) · 뿌리는 재고 행(main `2cc94eb3`) · `PipelineScheduler` javadoc 「지나간다」는 거짓(보고).
   - 오늘(예측 · 재지 않음): 13:30 → 15:24:49 · 14:00 → 15:54:49 · 14:15 → 16:09:49(무거운 잡 없음 — 셋 다 10-03 성공 · 3일) · 14:18 → 16:12:49 · 14:20 → 16:14:49 · 15:30 점검 → 17:24:49 · 15:41~16:30 배포는 밀린 잡을 ABORTED로 만들고 새 JVM은 다음을 10-07로 잡아 그날 남은 발화가 통째로 빠진다.
2. **합치기 경로별 단언이 같은 절차의 충돌 규칙과 모순**(①) — 모의 M1(main 544f82e0 + p0)은 깨끗 · M2(M1 + p1)는 `reload-gate.md` 하나 충돌 · 행 규칙 결과가 HEAD^2와 4행 다른데 그 파일이 P 안 → Q = 양쪽이 바꾼 경로(지금 `reload-gate.md` · `IndicatorBackfillParallelTest`) · P∖Q만 HEAD^2와 같음 · Q는 경로마다 규칙으로 단언(reload-gate 세 행 · 충돌 표지 0) · 「합칠 때」 고칠 것은 단언 뒤 별도 커밋.
3. **사후 대조 가름에 원인 둘 빠짐**(①) — 운영 FR의 종목 실패(note에 `failure_count` 없음) · 덤프 뒤 남의 쓰기 → (다)로 빠져 109 전부 원시 SQL 되돌림 · (나)의 티커를 note로 못 앎 · (나) 재실행 뒤 화해 없음 → 3단계 뒤 새 MANUAL 행 하나 · SUCCESS · `failure_count = 0` 단언 · 그 행 `started_at`(DB 시계)을 되돌리기 `fetched_at` 기준으로 · (나) 대상 = `FAILED ∧ fetched_at ≥ started_at`(CIK → 티커) ∪ 실패 행 · 재실행 뒤 같은 `--tickers` 화해.
4. **사전 기대가 v7로 잰 수가 아님**(③) — `rs1-b1.tsv`(05:42) 뒤 여는 길·보호·목록 실패·빈 색인 커밋 6개 · 설명 안 되는 차이와 「이른판 > 0」 뒤 행동 없음 → v7로 다시 냄(`p1rs/rs1c.sh` · 0콜 · `sc_a` 사본) · 설명 안 되면 「멈추고 구성원마다 판정」.
5. **0단계 빠진 전제**(②) — (a) `sec_filing_supplement` 0행 단언(되돌리기가 거기 기댄다) (b) 배포 커밋을 어디에도 안 남김 → 배포 직후 해시를 적고 `git diff --quiet <배포> HEAD -- src build.gradle settings.gradle` (c) 같은 셸에서 사본 → 운영 `bootRun` — 칠 때마다 의도한 DB에만 새 실행 행이 생겼는지 단언(`apply-window`의 `new_exec` 꼴) · Gradle 데몬 환경 이월은 재보지 않음.
6. (작음) 「SEC 종목당 1콜」 문장 셋(batch-load `SKILL.md:213` · `api-spec/sec-edgar-financial.md:455` · 합칠 때 고칠 줄) · 「창을 넓혀도 콜 안 늚」도 보충 뒤 거짓 → 셋을 함께(RS0 1,209종목 4,102콜).
- 선다(쟀다): 하네스 필수 변수 소비자(ADR 절차 · `rs1.sh`) 둘 다 줌 · 0부 병합은 양쪽 같이 바꾼 경로 0 · (가) 행 md5 같아 행 규칙은 지금 섬 · 사전 기대 수를 b1의 109 몫에서 하네스 공식으로 다시 세어 맞음 · 109 목록 중복 0(TAP.A 포함) · 되돌리는 두 표로 들어오는 FK 0 · identity ALWAYS라 `OVERRIDING SYSTEM VALUE` 맞음 · 운영 손 FR 뒤 `share-basis-derive` 자동(`REDERIVE_SHARE_BASIS_AFTER`) · 2-4 명령은 `boot()` 사본 갈래와 같은 꼴 · 서명 질의는 id·created_at·stock_id를 뺌 · 하네스와 운영 수집이 같은 Writer 인자(차이는 absence 쓰기뿐 · 서명 밖) · 소스 해시가 판 범위 메서드와 리더 전체를 덮음 · 디스크 278GB.
- **4의 실측(내가 잼 · 14:12 · 0콜)**: `createdb -T stock_screener_sc_a stock_screener_q4v_rs1c` + V64 → v7(`a9d1f03c`) 하네스를 rs1과 같은 인자로(`p1rs/rs1c.sh` · cache-1006 · FROM=2018 · INSERT_EARLIER=false · STORE=db) — 종목 TSV가 b1과 **1,207행 전부 같다**(차이 0) · `financial_statement` 229,192행 서명 md5 같음(id·created_at 뺀 전 칸) · `financial_column_absence` 18,566행 같음 · 보충 표 NO_XBRL 37 · OK 508 같음 · 109 몫 신규 128 · 정정 28 · 교정 3 · 이른판 0 · 회수 3 그대로. 대조군: 같은 사본 출발점의 보충 끔 재생(rs1a)은 신규 24라 하네스가 보충 여부를 가른다. → 사전 기대는 v7에도 선다 · 남는 처방은 「설명 안 되는 차이 → 멈추고 구성원마다 판정」 문장.

## 1부 v7 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut12/` · 0콜 · 변이는 `r12copy`(git archive f9a1d4c7))
1. **「행동 불변 재고정」 갈래의 증거가 v6에서 눈먼 것으로 밝혀진 출력 해시다**(④) — v6 생존 변이 D1(fy trim 제거)을 v7에 넣으면 소스 해시 붉음 · 출력 해시 초록 · 보충 시험 75 중 다른 실패 0 → 표대로면 「`SOURCE_PINNED`만 고친다」로 통과 · `PINNED` 중복 금지도 같은 전제 → 재고정 갈래를 걷고 소스가 바뀌면 언제나 판을 올림 · 중복 금지 단언 걷음 · 비용(사본 545행 ≈ 1,053콜)은 크기로만.
2. **판 범위 목록이 종료 상태를 정하는 자리 셋을 빠뜨림**(②·①) — S1 `folder`가 `.xsd` 버림 · S2 `fetch`가 `pickInstance` 입력을 거름 · S7 `instance` 빈 본문 가드 제거 → 두 해시·보충 시험 75 전부 초록 · S1·S2는 NO_XBRL(조용한 종료 · 미해결 계수 밖) · 실물 0(캐시 색인 567에서 판정 변화 0) → 두 파일 전체 + 저장 코덱을 범위로 · 빈 인스턴스 시험 · 이름 없는 색인 항목 MALFORMED.
3. StAX 구현을 `newFactory()` 서비스 조회가 고름(잠복 · 지금 대체 구현 0) → `newDefaultFactory()` + 구현 이름을 출력에.
4. 정규화기 구멍 넷(텍스트 블록 안 `\"""` · 문자열 안 `//` · 리터럴 안 공백 접힘 · 같은 머리 오버로드) — 지금 영향 0.
5. ADR 절차의 「글자 그대로」가 안 섬 — (a) `B`가 디스크 경로가 아님(브랜치는 scratch 워크트리뿐) (b) 되돌리기 SQL이 자리표시자 (c) (나) 「그 티커만」을 따를 길 없음(note는 보충실패를 수로만 · `stock`에 cik 없음).
- 선다(쟀다): 빈 색인 MALFORMED는 캐시 567 전부 항목 ≥ 5 · 소스 해시 경계(주석만 초록 · 한 토큰 붉음) · 목록 실패 touch와 tally 술어 · 하네스 필수 두 값 · 사전 기대 수 · `applyDotEnv`는 빈 값으로 준 변수를 안 덮음 · `financial_statement.id` GENERATED ALWAYS · FK 0 · 보충은 부재 표를 안 바꿈(RS0 18,329 = 18,329) · 시험 1,818/0.

## 1부 v8(2026-10-06 · 두 렌즈 반영)
- **판 고정**: 판 범위 = 저장되는 것을 정하는 파일 전체(`FilingSupplementer` · `SecFilingClient` · `SecFilingSupplement` · `SecFilingSupplementStore` · `SupplementLedger` · `XbrlInstanceReader`) · 정규형 = javac가 파싱한 트리를 다시 찍고 문서 주석 줄을 걷음(손으로 짠 주석 걷기를 버림 — 반증 4의 넷이 원리적으로 사라짐) · 범위 파일이 코드로 부르는 같은 패키지 형은 범위 안이거나 사유 적힌 예외(`SecEdgarResponse`·`SecEdgarHeaders`·`SecTickerCikCache` — 다시 받는 실패만 냄)라는 성질 시험 · 재고정 갈래·중복 금지 걷음 · 출력에 StAX 구현과 JDK 판 · `READER_VERSION` 2.
- **받기**: 이름 없는 색인 항목 → MALFORMED · StAX `newDefaultFactory()`.
- **대조(내가 잼)**: 주석만 바꾼 대조군 C0 → 다섯 시험 초록 · S1·S2·S7·D1·S3(코덱이 start 버림) → 소스 해시 붉음(출력 해시는 다섯 다 초록 — 「출력 같음」이 증거가 못 됨을 다시 확인) · 범위 밖 형을 새로 부르는 변이 → 성질 시험 붉음 · 정규화기 자기 시험(리터럴 안 공백 · 텍스트 블록 · 문자열 안 `//` 뒤 코드 · 오버로드 · 텍스트 블록 뒤 코드)은 공백 접기·순진한 `//` 걷기 변이에 붉음 · 이름 없는 항목 검사 제거 → 새 시험 붉음 · 빈 본문 가드에서 `length == 0`만 빼면 초록(RestClient가 빈 200을 null로 냄 — 그 갈래는 이 길로 안 닿음) · 가드 통째로 빼면 NPE로 붉음.
- **ADR-0173 절차**: 앱을 내린 채(내리기 전 「오늘 크론 다 돎」을 기록으로) · 0단계에 배포 커밋 `D` diff · 보충 표 0행 · `E0` · 목록 파일을 `git show`로 · `bootRun`마다 「bootstrap이 데이터베이스 X에 붙었다」와 러너 끝 줄을 grep으로 단언(`share-basis-derive`는 실행 행을 안 만든다) · 운영 FR 새 행 하나·MANUAL·`started_at` = `S` · (나) = 실패·보충실패·회수정지 > 0 → 같은 109로 3·4를 다시(두 번까지) · (다) = 멈추고 구성원마다 판정 → 결함 종목만 되돌림 · 되돌리기 SQL을 글자로(임시 표 `like` · `\copy` · 두 delete · `overriding system value` · 보충 `S` 뒤 행 delete · 행 수 단언 DO 블록) · 둘째 손 FR은 「보충 표 0행」이 안 섬을 명시.
- **main**: progress 합치기 단언 Q = P ∩ M · 배포 시각을 기록으로 · 합칠 때 고칠 것은 별도 커밋(스킬 두 줄 · api-spec:455는 `companyconcept` 절이라 그대로 — 반증 6의 셋 중 하나는 서지 않음) · A56 옛 세 항목 접음(main `bca5a4c6`).
- **보류 브랜치**: `066c325a`(코드 · 시험 1,822/0) · `f014d2c8`(ADR 절차) · `db3d571f`(되돌리기 단언 고침).
- **v8 사전 기대 재측정(내가 잼 · 0콜)**: `createdb -T stock_screener_sc_a stock_screener_q4v_rs1d` + V64 → v8 작업 트리로 rs1과 같은 인자(`p1rs/rs1d.sh`) — 종목 표 b1과 1,207행 차이 0 · 재무 229,192행 서명 md5 같음 · 보충 NO_XBRL 37 · OK 508(판 2).
- **되돌리기 연습(내가 잼 · 사본)**: `_base` = `sc_a` · 운영 자리 = `rs1c`(v7 재생을 마친 복제) · ADR 글 그대로 → 109종목 두 표 서명이 sc_a와 md5까지 같음(재무 8,117 · 부재 1,528) · 나머지 그대로 · 보충 545행 지움. **대조군(한 줄 뺀 복사 파일)이 첫 꼴의 단언을 통과해 8,116행을 커밋했다** — 「넣은 수 = 임시 표 수」는 동어반복 → 사본에서 잰 수를 박는 꼴로 고침(`db3d571f`) · 같은 대조군이 예외로 트랜잭션째 되돌려지고 정상 경로는 통과 · `rs1c`는 되돌린 상태(sc_a와 같은 109)로 남아 있다.
- 대기: v8 확인 반증 두 렌즈(`rebut13/` · `refute13/`).

## 1부 v8 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut13/` · 대상 `db3d571f` · 0콜 · 사본 `stock_screener_q4v_r13a`·`r13b` · 코드 사본 `r13copy`)
1. **받다 만 본문과 200 오류 본문이 `UNREADABLE`(종료)로 굳는다 — 가짜 한계** · MockWebServer 탐침 다섯 꼴(대조군 503 → TRANSIENT · 다음 실행 OK): 200 HTML · 200 XHTML · 본문 도중 끊김 · Content-Length보다 짧은 본문 → 전부 `FAILED/UNREADABLE` · 다음 실행 인스턴스 0콜 · 뿌리 = `SimpleClientHttpRequestFactory`가 잘린 고정 길이 본문을 예외 없이 넘기고 `instance()`는 상태·빈 본문만 봄(버린 대안 6과 같은 모양) · 전송을 `JdkClientHttpRequestFactory`로 바꾼 변이에선 끊김 둘이 TRANSIENT · **원장은 길이를 준다**(cache-1006 인스턴스 530/530이 `index.json` `size`와 바이트 일치 · 불일치 0 · 빈칸 0) · 절차의 `stop.sh --app`이 앱의 `caffeinate -s -w`를 끝내고 이 기계는 `pmset sleep=1`(AC·배터리) → 처방: `folder()`가 이름과 `size`, `instance()`가 길이 ≠ size면 MALFORMED · 판 3 · 잘린 본문·HTML 시험 · `bootRun`·`liveTest`를 `caffeinate -is`로.
2. **(나) 재실행이 `S`를 덮어 되돌리기가 첫 실행의 보충 행을 남긴다** — r13b에서 109를 두 번 재생 뒤 글자대로 되돌리기: 재무·부재는 sc_a와 md5 같음 · 보충 delete 0행 · 161행 남음 · 「실패한 공시만 다시 받는다」는 UNREADABLE에 안 섬 · (나) 판정 칸 `보충실패`(`fetched_at >= since ∧ FAILED`)는 재실행에서 첫 실행의 UNREADABLE을 0으로 셈(코드로 읽음) → `S`는 첫 FR 직전 한 번 · 3의 단언 = FR 행 수 = 친 횟수 · 첫 손 FR의 보충 되돌리기는 표 전체(0단계가 0행 단언) · (나) 판정에 미해결 > 0 · UNREADABLE이면 (다)로.
3. 성질 시험이 static import · 완전한 이름 참조를 못 봄(P1·P2 초록 · 소스 해시만 붉음 · 범위 밖 도우미로 `.xsd`를 버리는 P1b는 판 3 고정 뒤 전부 초록) · 지금 실물 0(잠복) → javac 속성 분석으로 참조 심볼의 소유 형 · 대상 `com.stockscreener.*` 전체(1번이 보이듯 다른 패키지 형도 종료 상태를 정한다).
4. 정규형이 `record` 키워드를 잃음 — 명시 정규 생성자 레코드 대 같은 생성자 내부 클래스 · 성분 0 레코드 대 내부 클래스가 같은 정규형(행동 다름) · 실물 0 · 나머지 13쌍은 섬(코드 줄을 먹은 경우 0) → `ClassTree.getKind()`를 정규형에 · 자기 시험에 레코드 쌍.
5. 뜻을 바꾼 v8의 문서 소비자 — ADR-0173 §규칙 40행(옛 「해석 규칙 다섯」 · v6·v7에서 거짓으로 밝혀진 「반증 변이 19개 전부 붉다」) · V64 주석 6~11행 옛 범위 · `rs1b`는 옛 V64라 v8로 재생 불가 · 판 1→2 이행은 r13a에서 잼(판 1로 낮춘 161행을 v8이 전부 다시 받아 판 2 · fs md5 같음 · stale 0) — 섬.
6. 두 MUST NOT(박힌 해시를 고쳐 판을 안 올림 · 재료 변경 동반)이 산문뿐(구조로 확인 · 변이로는 안 잼) → 재료 입력 해시 · 기존 항목 수정 금지 훅.
7. (작음) 예외 셋: `SecTickerCikCache` 섬 · `SecEdgarResponse`는 비2xx 갈래만 서고 `requireJson`의 200 갈래(트리 → 이름 목록 → NO_XBRL 입력)를 안 덮음 · `SecEdgarHeaders`의 「틀리면 403」은 좁음(재보지 않음) · heredoc을 들여쓰기째 복사하면 종결자가 안 섬(실패 → 트랜잭션째 되돌림 · 안전).
- 선다(쟀다): 새·바뀐 시험이 자기 코드에 닿음(base 79/0 · C0 초록 · 항목 검사 제거 붉음 · 문서 주석 걷기 끄기/탐욕 붉음 · 범위 밖 단순 이름 붉음 · `newFactory()`는 소스 해시만 붉음 · 배포 jar 113개에 대체 StAX 0) · JAXP 한도(120MB · 엔티티 다량 무사 · 캐시 최대 23.9MB) · v8 109 재생 = ADR 2-5 수 · 같은 109 재실행은 쓰기 0(Writer 수준 멱등) · 되돌리기 SQL 글자대로 md5 같음 · 절차 글자(1단계 109행 · `8e8f8484` 닿음 · 빈 `--tickers=` 거절 · 로그 두 줄 코드에 있음 · `^$(date +%F)` 맞음 · DB 시간대 Asia/Seoul · note 꼴 · `liveTest`에 `.env`) · 평일 저녁 크론 없음 · 14:53 오늘 실행 행 0(밀림 실재).

## 1부 v9(2026-10-06 · 작업 트리 `wt-p1v9` · 계획 밖 v8 판정 대기 · 시험 1,828/0)
- **받기**: `folder()`가 항목마다 `size`(빈 칸은 null · 수가 아니면 MALFORMED) · `instance()`가 받은 길이 ≠ 색인 크기면 MALFORMED · 크기 없는 인스턴스는 받지 않고 MALFORMED · 판 3 · 범위에 `SecEdgarResponse`(색인 JSON 200 갈래).
- **판 고정**: 정규형에 형 종류(`ClassTree.getKind()`) · 범위 밖 프로젝트 형을 javac 속성 분석으로(`com.stockscreener.*` · 일곱 형 사유 — `DataSourceException`(Kind에 UNREADABLE 없음을 시험으로) · `ConditionalOnApiKey` · `DataSourceProperties` · `ExternalApiClientFactory` · `RateLimiter` · `SecEdgarHeaders` · `SecTickerCikCache`) · 두 목록 덧붙이기만(git 이력 · `066c325a`부터 · 재료만 바꿔도 판을 올림 · 중복 허용).
- **ADR·V64**: §규칙의 판 범위·「받았다 = 길이가 색인 크기와 같다」 · V64 주석(체크섬 바뀜 → 시험 DB `stock_screener_p1v9_test`) · 절차: `caffeinate -is` · `S` = 이 절차의 첫 FR 시작 · 3의 단언 = 행 수 = 친 횟수 · (나)에 `미해결보충` · UNREADABLE이면 (다) · heredoc 들여쓰기.
- **대조(내가 잼)**: 길이 대조를 빼면 새 시험 셋(잘린 본문 · 200 HTML · **MockWebServer 본문 도중 끊김** — 지금 전송이 끊긴 본문을 넘기는 원래 결함을 재현)이 붉음 · 박힌 값 하나를 고치면 덧붙이기 검사 붉음 · 범위 밖 형을 완전한 이름·static import로 부르면 성질 시험 붉음(식별자만 보던 v8 시험은 둘 다 초록이었다) · 성분 0 레코드와 내부 클래스는 트리 출력이 같고(`class R { }`) 형 종류가 가름.
- **v9 재생(0콜 · `p1rs/rs1c-v9.sh`)**: 109를 sc_a 상태로 되돌린 `rs1c` 위 — 109 몫 신규 128 · 정정 28 · 교정 3 · 이른판 0 · 회수 3 · 나머지 변화 0 · **보충실패 0(1,207종목 전부 — 길이 대조의 오탐 0)** · 109 재무 서명이 v8 재생(`rs1d`)과 md5 같음 · 보충 NO_XBRL 37 · OK 508.

## 2부 v5 반증 판정 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행 — 1(출구)·2(저장 응답)는 처방을 다시 지어 다시 받는다」 · `rebut-p2v5/` · DART 6콜 15:46~15:47 · SEC 0)
1. **출구가 「진짜 한계 → 미표시」를 만들지 않는다**(④·①·②) — 출구에서 FR을 커밋하면 개정본 행의 FSR 칸이 NULL(`insert`는 이어받지 않음 · `FILL_WITHOUT_REVISION`은 이른 FSR 응답을 건너뜀 `FinancialWriter:425-479`) → 계산기 11개가 연결 기저로 폴백해 값을 냄(PBR · ROE · ROE_MIN_3Y · PBR_MEDIAN_5Y(창에 NULL 한 행이면 창 전체) · PER · PER_MEDIAN_5Y · EPS_TTM · EPS_CAGR_5Y · EPS_GROWTH_TTM_YOY · DIVIDEND_PAYOUT · EXEC_PAY + `GovernanceFactDeriver:222`) · 실제 미표시는 OCF·FCF·발생액뿐 · 노출 349 중 86(지배 < 총 × 0.9) · **원인 다섯(FSR 지연 · FR 지연 · 013 · 이름 조인 실패 · 결산월 변경)을 타이머 하나가 처방 하나로 묶음** — 결산월 변경 실측(비츠로셀 00154718: 「사업보고서 (2017.06)」·「(2017.12)」가 (2017, 11011) 한 칸 · `fnlttSinglAcnt`는 늦은 쪽만) → 롤백 N회면 전 창 짝 실콜 한 번 더 · 엔드포인트별 (번호 · 013)을 결과 행에 · (a) FSR만 낡음 → FR 커밋 + 「FSR 미반영」 표지 · 11 폴백이 표지면 null(폴백은 원장이 개념을 안 주는 종목만) (b) FR 낡음 → 커밋 안 함 (c) 같은 (Y,C)에 이름 다른 보고서 → 그 공시만 출구 · 라벨 칸 불변 (d) 나머지 구성원마다 · 드라이런: 지배 < 총 × 0.9 종목 출구 라벨로 PBR·ROE·PBR_MEDIAN_5Y null 단언.
2. **셋째 길 재생 규칙 다섯 빠짐** — (a) 「새 번호」 기준 미정(저장본 대비면 FSR 늦은 다음 날 FR 저장본이 탐침 번호와 같아 FSR을 안 부르고 낡은 저장본 재생 → 가짜 출구) → 미소비 R의 (Y,C)는 두 엔드포인트 모두 매 시도 실콜 (b) 탐침 (Y,C) 매핑이 하중 — 틀리면 맞는 라벨이 저장본 재생 → 조인 실패 → 출구 · 규칙은 표본 89/89(결산월 NULL·변경이 갈래) → 출구 계수 전 전 창 실콜 (c) 전 창 대상(재편입 · 기준점 없음 · 미해결 실패)에 저장본 금지 — 국내 재편입 298회(146종목) · 빈 구간 중앙 14일 · 최대 287일 · 빈 구간 공시 88회(30%) → 「전 창 = 전부 실콜」 (d) 갱신 주체 누락(손 CLI · 관문 · B 꼬리 GAP_FILL #706 · initial-load) → 저장은 어댑터 라이브 호출 층 · `000`·`013`만 · 「받은 적 없음」과 013 구분 (e) 채택 조건이 좁음(FR만 · DB 값에 FSR 덮은 값 섞임 · 15일 < 저장 수명 · n·기준 미정 — 20라벨 0이면 95% 상한 15%) · 실측 FSR FY2025 CFS 3종목 BS 행 64/22/49 같음(15일) → 오늘 두 엔드포인트 원시 응답 저장 · 40일 이상 뒤 같은 번호로 비교 · 정정 이력·OFS 전용·금융·3월 결산 · n과 「허용 0」 먼저 커밋.
3. **반영 대기에 상한 없음** — 대기 종목은 `runHeavy`에서 빠져 FR·FSR 전 컬럼이 멈춤 · 출구는 롤백만 셈 · 탐침 013 처리 미정 → 대기에도 N평일 상한 → 넘으면 실콜 짝 → 1의 갈래 · 결과 행에 「013」과 「번호 < R」 구분 · 감시 ①이 대기 일수.
4. 손·관문 FR이 국내 `consumed_at`을 쓰면 FSR 없이 소비(④ 미국 단일 엔드포인트 규칙 이식) → 국내는 엔드포인트별 소비 칸 둘 · 둘 다일 때만 consumed_at.
5. **G 도는 날 단계 창 음수** — G 최종 시작 15:28 − 12:09 − 54:58 = 14:20:53 · 단계 상한 14:12:56 < 14:15 → G 적합일은 대상 0일 뿐(37/261 = 14% · 하한 · 6-K 포함이라 더 적음) → G는 강제(+38)일만 · 그날 단계 0분 · 강제일 10-28(G) · 10-30(FSR) · 11-10(FR · 10-Q 마감 다음 날) · FSR(42:31)이면 창 약 10분 · FR이면 약 6분 · 「반영 대기를 뺀 대상」은 탐침을 돌려야 알아 순환 → 탐침을 단계 첫 순서로 · 화해는 실제 무효화 있을 때만 뺌 · 강제 날 단계 몫을 수로 · RF3에 강제일.
6. **FR·FSR이 공유 컬럼을 서로 덮는다**(실측 반쪽) — `toCorrect`는 모드 무관 덮고 periodStart부터 무효화(`FinancialWriter:595-674`) · FSR이 쓰는 revenue·영업이익·순이익·자산·자본·부채·유동·이익잉여금은 FR과 공유 · #647 교정 4는 FR #645 직후 FSR이 덮은 칸(그 칸에 FR이 값을 내는지 안 잼) · ADR-0056 「덮지 않는다」와 코드 어긋남 → 두 매퍼의 공유 컬럼 불일치를 0콜로 전수 · 0 아니면 소유자 하나 · RF2 a를 두 순서로.
7. 미해결 실패 술어의 「그 종목에서 성공」을 담을 자리 없음(해결 표지는 (종목, 단계) · NARROW는 대상 목록 안 남김 · `resolveFailures`를 잡 단위로 바꾸면 `BatchExecutionReader:146` · `DailyUpdateJob:228,479` · `BatchExecutionContext:159,659` 뜻이 바뀜) → 단계 결과 행/공시 표에 종목별 성공 · 공유 해결 표지 불변.
8. 드라이런이 새 갈래에 안 닿음 — RF2 빈 저장소는 재생 갈래를 안 탐 → a1로 채운 저장소에서 b · 낡은 저장 응답을 심어 b가 붉은지 먼저 · 대기 갈래 반영 시각은 13:19·15:46 둘이 같을 때만 「14:15에도」 · 출구 지표 파급 RF 없음 · RF0/RF4에 비츠로셀 2017 · 가온칩스.
9. (작음) 단계 asOf(「시장 날짜 − 1」)와 정기 `targetDateFor`(주말 건너뜀)가 1월 1일이 주말인 해 첫 월요일(2028-01-03)에 연도가 갈림 → 같은 함수 · 시험 날짜.
- 덤(남의 결함 · 보고): `published_at` = `rcept_no` 앞 8자리 — 571 중 4건(창대정밀 · 지앤이헬스케어 · 케이솔루션 · 프레스티지바이오파마)은 `rcept_dt`가 하루 늦음(어느 쪽이 공개일인지 원장에 안 물음 · 재무가 하루 앞 봉에 보일 후보) · 현 편입 미소비 실물 셋(원익홀딩스 030530 · 보로노이 310210 · 현대바이오 048410 — 뒤 둘은 10-02 `[기재정정]반기보고서 (2026.06)` · API는 이미 그 번호 · DB Q2 2026 최신 행 08-14·08-13).
- 선다(쟀다): 탐침 (Y,C) 규칙 89/89(12월 83 · 3월 신영증권 6 · `probe_map_check.tsv`) · 현 편입 결산월 NULL 0 · 같은 이름 원본 둘 0/571 · 접두 한 겹 · `[첨부추가]`는 원본 번호 표시(87묶음) · FSR 불변성 3/3(방향만) · 기준점 「실행일 − 1」 · filed = `rcept_dt`는 보수적 · 0부가 FSR 미국 NOT_FOUND 1,000행을 닫아 새 실패 술어가 미국 전원을 매일 전 창으로 만들 길 없음 · 목록 조인은 결산월 대응표 없이 섬.

## 1부 v8 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute13/` · 0콜 · 시험 DB `stock_screener_r13b_test`)
1. **오늘 A+A2 배포 문장이 아직 벽시계**(② 소비자) — `progress:56` 「15:40 뒤」 둘 · `:93` 「손 쓰기 잡은 평일 13:30~15:40 밖」 · bca5a4c6은 `:305-306`만 바꿨다 · 글자대로면 봉 확인 직후(약 15:48) 배포 → 오늘 남은 크론 다섯이 빠진다.
2. **둘째 손 FR이 첫 FR의 2단계 산출물을 이름으로 부른다** — 「0·3·4·5·6단계만 같다」라 1·2(사본 · 재생 · 중단 조건)가 없고 5·되돌리기가 `fr1`·`_fr1_base`·109를 부른다 → 운영 쓰기 앞의 문이 없고, 되돌리기를 글자대로 치면 첫 FR 직전 상태로 간다.
3. **「오늘 크론이 다 돌았다」 술어 반례 셋** — (a) 전날 발화가 자정을 넘기면 다음 날 아침 참(0콜 재현) (b) 화해 행은 필요·충분조건 아님(14:00 사슬이 SCHEDULER 화해를 부르면 14:15에 참 · ①이 FAILED면 ② 행 없음 · `IndicatorReconciler.reconcile:96-100` · SCHEDULER로 이 행이 생긴 날은 10-05 하루) (c) 토요일 술어 없음(꼬리 끝 = `MembershipGapFiller:175` 로그 · 앱 크론일 때만 stdout.log).
4. **D(1부를 배포한 커밋)가 절차를 막는다** — main src 커밋 9-20~10-06 17일 중 11일 · ops에 배포 커밋 기록 자리 없음 → 「지금 도는 jar의 커밋」 · 그 커밋 worktree에서 bootRun(재보지 않음).
5. **되돌리기 delete가 되돌리지 않은 종목의 원장 행까지 지운다** — 자기 MUST NOT(:47)과 충돌 · 「다음 FR이 다시 받는다」는 그 사이 companyfacts가 실은 갈래를 빠뜨림(기대 약 0.04건) · 과거 편입 18은 다음 FR이 관문뿐 → CIK로 좁힘(같은 날 캐시 `companyfacts/<티커>.json` · 109 전부 매핑) · CIK 공유 쌍은 함께.
6. 합칠 때 목록 밖에서 거짓이 되는 문장 — `SecEdgarFinancialAdapter:28-31` 「종목당 1콜」 · api-spec DOX 절(「US_TOP1000에서 DOX 하나」 · 「financial-refill이 집어 간다」) · ADR 「dump-db 좁힌 복원도 같은 형태」(09120ba3 뒤 낡음).
7. READER_VERSION 하류 — `:141` `==` 대 `:348` `<`(높은 판 행을 옛 판 사용으로 안 셈) · 「언제나 올린다」로 main이 배포 jar보다 자주 앞섬 · 2부 v4 해소 술어 「현재 판」의 빈칸 · 판을 설정의 함수로 할 날 시험 구조(지금 0).
8. (작음) 재고 행 「37평일」은 발화한 날 수 · 08-10 뒤 발화 없는 평일은 08-11 더해 넷.
- 선다(쟀다): 병합 Q 규칙(M1 충돌 0 · M2 reload-gate 하나 · Q = {reload-gate, IndicatorBackfillParallelTest} · 같은 행 양쪽 변경 0 · P∖Q = HEAD^2 · P 밖 = HEAD^1) · v8 대조 재현(79/0 · 다섯 변이 소스 해시 붉음/출력 초록 · C0 초록) · 크론 밀림 예측 1초 안 · 10-09 공휴일에도 크론이 돈다 · 기동 때 따라잡기 없음 · 보충 표 0행은 첫 FR까지 섬 · `fetched_at` = DB `now()` · 되돌리기가 FR 경로의 표를 다 덮음.

## 1부 v9 마무리(2026-10-06 · 보류 브랜치 `4c58053d`·`02b1d21f` · 시험 1,829/0)
- 계획 밖 v8 반영: main — `ops/crons-done.sh`(a74c6204 · 술어 하나로 · 반례 셋 반영 · 지금 상태에서 「아직」 판정 확인) · progress·재고 행을 그 판정으로 · 재고 수 고침 · `ops/stage.sh`가 `app.jar.commit`(6f6794a3) · `PipelineScheduler` javadoc(4509543c) · 보류 브랜치 — 판 비교 `!=`(높은 판 시험 · `<` 변이 붉음) · 어댑터 javadoc · DOX 절 · ADR 절차를 `N`의 함수로(둘째 FR 대상 = 그 리밸런싱 편입 전원 · 10-02에 대어 13 = 13 · 첫 편입만 세면 2(IOVA·KOD) — 창 밖 재편입은 첫 FR 범위가 아니었다) · 배포 커밋 worktree · 보충 delete를 CIK로 · `crons-done.sh` 호출.
- 연습(내가 잼): worktree bootRun — 정상 경로(`stock_screener_q4v_rs1e` = sc_a 복제 · Flyway가 V63·V64 적용 · 두 단언 줄 각 1) · 실패 경로(`rs1c` — Flyway 밖 V64 → 「이미 있다」로 죽고 종료코드 0 · 두 줄 0건이 잡음).

# 2부 계획 v6 (2026-10-06 16시 · v5 두 렌즈 반영 · v5 결정 중 바뀐 것만)
## v6 실측(내가 잼)
- 국내 공시가 몰리는 날(현 편입 ∪ 과거 · `published_at` 종목 수): 2026-08-14 380 · 2026-05-15 341 · 2025-11-14 341 · 2026-03-18 138 · 그 밖 50 이상은 5일(1년 9일). 리미터 `dart.daily-quota` 18,000 · `permits-per-minute` 600. 종목당 전 창 짝 81콜(v4 실측)이면 몰린 날 다음 평일 단계는 약 28,000콜 — 한도 안에 약 210종목(18,000 − 기업 행위 700 · 무거운 잡 없음 가정).
- 크론 밀림: 10-06 13:30 크론이 15:24:51에 돌았다(전날 배터리 수면 6,912초) — 시각 모형의 절대값은 서지 않는다.
- 불변성 표본 층의 크기(현 편입 350): 3월 결산 1(신영증권) · 정정 이력(revision_no > 0) 29 · 금융 업종(KSIC 64~66) 있음 · OFS 전용은 DB 컬럼으로 못 가른다(FSR CFS 013을 원장에 물어야 한다).

## 결정(v5에서 바뀐 것만)
1. **셋째 길(저장 응답)은 채택하지 않는다 — 단계는 전 창 실콜이다**(v5 결정 3 철회). 채택 조건(같은 접수번호 → 같은 응답)은 40일 이상 관찰이 있어야 서고(v5 계획 안 2e — 15일 20라벨 0이면 95% 상한 15%) 2부 마감(≤ 10-26) 전에 못 선다. 재생 규칙 다섯(2a~d)도 함께 사라진다. **관찰은 지금 시작한다** — 아래 「불변성 관찰 규칙」(대조 전 커밋) · 판정은 11월 피크 뒤.
2. **몰린 날은 이틀에 걸쳐 닫힌다 — 수로 정한다**: 단계는 그날 DART 잔량(18,000 − 기업 행위 − 그날 무거운 잡 몫)을 종목당 81콜로 나눈 수만큼 **공시일이 이른 종목부터** 돌고, 남은 종목은 미소비로 다음 평일에 남는다(값이 틀리는 것이 아니라 하루 늦게 보인다 · 결과 행에 「한도로 미룸 N」). 11-14(금) 341이면 월 약 210 · 화 나머지(약 130).
3. **롤백·대기 상한 → 진단 실행 → 원인 갈래**(v5 결정 4 바꿈 · 계획 안 1·3 · 계획 밖 3·4): 같은 공시의 롤백 또는 반영 대기가 N평일(첫 값 5)이면 그 종목의 전 창 짝을 실콜로 한 번 더 돌고 엔드포인트별 (접수번호 · 013)을 결과 행에 적는다:
   - (a) FSR만 낡음(FR 번호 ≥ R · FSR 번호 < R 또는 013) → FR 커밋 + 그 라벨 행에 **「FSR 미반영」 표지** → 지배주주 폴백을 쓰는 계산기(PBR · ROE · ROE_MIN_3Y · PBR_MEDIAN_5Y · PER · PER_MEDIAN_5Y · EPS_TTM · EPS_CAGR_5Y · EPS_GROWTH_TTM_YOY · DIVIDEND_PAYOUT · EXEC_PAY)와 `GovernanceFactDeriver:222`는 **표지가 있으면 null**(미표시). 폴백은 원장이 그 개념을 안 주는 종목에만 남는다(카카오뱅크 OFS 꼴). **표지를 읽는 계산기 규칙이 무해한 선행 배포다**(표지 0행이면 값 불변을 바이트로 단언) — 표지 저장 구조는 ADR로.
   - (b) FR 낡음 → 커밋 안 함 · 판정 요청(결과 행 · 감시).
   - (c) 같은 (Y,C)에 이름이 다른 보고서(결산월 변경 — 비츠로셀 2017) → 그 공시만 「조인 불가」로 등록 · 라벨 칸 불변.
   - (d) 그 밖 → 구성원마다 원장에 물은 뒤 등록(판정 네 요소 · 자동 라벨 없음).
   - 반영 대기도 이 상한을 탄다(탐침 013과 「번호 < R」을 결과 행에 갈라 적음 · 감시 ①이 대기 일수).
4. **국내 소비 칸은 엔드포인트별 둘**(계획 안 4): FR 칸 · FSR 칸 · 둘 다 찼을 때만 `consumed_at`. 손 FR은 FR 칸만 쓴다.
5. **시각은 상대값·사건 기준**(v5 결정 8 바꿈 · 계획 안 5 · 계획 밖 1): 단계는 탐침을 첫 순서로 돌린 뒤 그날 대상을 알고 무거운 잡 적합을 정한다 · 상한 = 실제 시작 + 예산(벽시계 절대값 아님) · 무거운 잡은 「오늘 단계 결과 행이 끝났다」를 보고 돈다(1분 간격에 기대지 않음 · 늦게 시작한 날도 순서 보존) · 사슬 안 화해 몫은 실제 무효화가 있을 때만 뺀다 · **G 강제일(+38)**: 단계 몫 = 2의 수(한도 몫)로 고정하고 G가 그 뒤 · G 적합일은 단계 대상 0인 날뿐(37/261)이라는 실측을 RF3에 · 강제일 10-28(G) · 10-30(FSR) · 11-10(FR).
6. **공유 컬럼 소유자**(계획 안 6): FR·FSR 매퍼가 함께 쓰는 칸(revenue · 영업이익 · 순이익 · 자산 · 자본 · 부채 · 유동 · 이익잉여금)의 불일치를 표본으로 잰다(아래 관찰 규칙의 같은 응답으로 0콜 비교) · 0이 아니면 칸마다 소유자 하나(뿌리) · RF2 a를 두 순서로.
7. **실패 해결은 단계 결과 행에 종목별로**(계획 안 7): 공유 해결 표지(`BatchExecutionRecorder`)는 안 건드린다 · 술어 = 「그 종목의 단계 성공 행 ∨ 정기 FULL 실행의 대상 재구성 ∧ 실패 부재」.
8. **asOf는 정기와 같은 함수**(계획 안 9 · `targetDateFor`) · 시험 날짜 2028-01-03.
9. **공시일**: 결정 10의 filed = `rcept_dt` 유지(기준점에 보수적) · DB `published_at` = `rcept_no` 앞 8자리가 `rcept_dt`보다 하루 이른 4/571건은 지표가 하루 앞 봉에 재무를 보는 후보 — 어느 쪽이 공개일인지 원장에 물어 재고에 올린다(2부 밖 · 보고).

## 불변성 관찰 규칙(대조 전 커밋 · 셋째 길 재판정용)
- 물음: 같은 접수번호를 낸 응답이 40일 이상 뒤에도 같은 행 집합인가(두 엔드포인트 `fnlttSinglAcnt` · `fnlttSinglAcntAll` CFS·OFS).
- 표본: 현 편입에서 층마다 고정 시드(`order by md5(ticker || '20261006')`)로 — 정정 이력 4 · 금융 업종 4 · 3월 결산 1(전수) · OFS 전용 4(FSR CFS 013을 원장에 물어 고른 것) · 일반 12월 결산 7 = 20종목 × 라벨 셋(FY2025 `11011` · 2026 반기 `11012` · 2026 1분기 `11013`) = 60라벨 · 라벨당 3콜(FR 1 · FSR CFS·OFS 2) = 180콜.
- 판정: 행 집합(계정 · 금액 · 기간 · `rcept_no`)이 **하나라도 다르면 채택하지 않는다**(허용 0) · 접수번호가 바뀐 라벨은 「정정됨」으로 따로 세고 비교에서 뺀다 · 60라벨 0이면 95% 상한 약 5%.
- 일정: 1차 수집 10-06 밤(실키 창 밖) · 2차 11-16 이후 같은 탐침 · 원시 JSON을 그대로 저장(어댑터를 거치지 않는다).

## 1부 v9 확인 반증 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute14/` · 0콜)
1. **`ops/crons-done.sh`가 크론이 남았는데 0을 낸다** — Spring `ReschedulingRunnable`은 크론마다 자기 본체가 끝난 순간 다음 지연을 단조로 고정 → 화해 끝(14:28) ~ 15:30 점검 전에 잠들면 다음 날 13:30~14:20만 밀리고 15:30은 정시 · 실제 Spring 스케줄러를 가상 시계로 돌린 재현(`cronsim/` · 10-05~06 실측 조건이 15:25:12·15:55:12 — 실측과 21초 = pmset 합과 JVM 지연의 차) · 가짜 홈·shim으로 스크립트가 0 → **고침(main `373dc2b1`)**: 일곱 `@Scheduled`가 finally에 끝 줄 · 판정은 크론마다 명목 뒤 끝 줄 · L 관문 걷음 · 회전 `.gz`까지 · 표 시험 `CronsDoneTableTest` · 연습 여섯 갈래(고른 0 · **차등 1(남은 5)** · 15시 기동 1/0 · 회전만 0 · 남의 PID 1).
2. **판정이 진입점에 안 걸렸다** · 낡은 크론 목록 둘(progress `13:30·14:00·14:30·15:30`) · ADR-0173 실키 창 → **고침**: `ops/stop.sh --app`·`ops/deploy.sh`가 부르고 멈춤(`--force`는 사람 · main `8f72ddc8` · 가짜 홈으로 kill·빌드 앞 rc 1) · progress 목록(`aee7cc45`) · 실키 창은 출처가 저장소에 없는 앞 세션 제약이라 그대로.
3. **「덧붙이기만」 시험이 merge 커밋으로 영구 붉다** — `git log` 날짜 순이 두 가지를 섞음 · `--first-parent`면 초록(sim) · `git archive` 사본에선 늘 붉음 → v10(다음).
4. `app.jar.commit`이 「스테이징 순간 HEAD」 → **고침**: deploy가 빌드 전후 (HEAD · 빌드 입력 미커밋 목록) · 다르면 unknown(`8f72ddc8` · 네 갈래 잼).
5. (작음) a74c6204 정정이 stage.sh 커밋에만 → `373dc2b1`·`aee7cc45` 본문에도 · progress:56 어순 · javadoc 산술(6,912 → 실제 밀림은 6,891).
- 선다(쟀다): 모의 병합(main 01c885b7 + p0 + p1 — M1 Q 0 · M2 Q = reload-gate · IndicatorBackfillParallelTest · 행 규칙이 새 행까지 덮음 · 합친 트리 1,821/1 SpaFallback) · v9 대조 재현 · 오늘 다섯 발화가 고르게 약 6,891초 · 싣는 src = A · A2-a · 9e7b029d + 주석만 셋 · 재고 수 맞음 · 출력 해시의 JDK는 feature만 · `!=` 섬.

## 2부 v6 반증 판정 — 계획 밖 렌즈(2026-10-06 · 「수정 후 진행」 · `refute-p2v6/` · 0콜)
1. **강제일 달력이 0부 jar의 위상과 어긋남** — 0부 ADR-0172 · progress:55 = G 10-19 · FSR 10-20 · FR 10-30(재구성 같음) · v6의 10-28·10-30은 09-20·09-22 + 38 → 2부 시대 실제: G·FSR 11-16/17 재연체(몰린 날) · 강제 G 11-26 · FSR 11-27 · FR 11-10 · 2025 같은 철 평일 32 중 단계 대상 0인 날 2(하한) → 첫 정기 FR이 11-10 · 10-30 전제 소비자 여섯(progress:55·218·239 · reload-gate:65 · ADR-0172 · ADR-0173:102) · G 재시도 마감 셋이 셋 · 연속 강제일 하루 결측 규칙 없음.
2. **워치리스트가 단계보다 앞(13:30)에서 판정** — `DailyUpdateJob:404-409` · 알림 걸린 항목 1(국내 · 조건 전부 재무 유래 · `q16`) · D 공시가 D+1 14:15에 써지는데 워치리스트는 D+1 13:30에 판정 → 결정 2 「하루 늦게 보인다」는 워치리스트에 거짓(낡은 값 알림 · 회수 안 됨) → 단계를 13:30 앞으로(SEC 일일 색인 D+1 11:04 · DART 반영 12:00~13:00 표본 먼저) · 안 되면 14:20 뒤 재판정(spec §83 해석 — 사용자 확인 대상).
3. **무거운 잡의 문 「오늘 단계 결과 행이 끝났다」는 crons-done이 버린 꼴**(자정 넘김) → 단계 → 무거운 잡을 한 `@Scheduled`로 묶어 술어를 없앰.
4. 결정 2의 수·요일·순서 — 341·380은 국내 485 전부(③) · 그날 편입 253·258·285 · 둘째 날 몫 약 40~72(130 아님) · 2026-11-14는 토요일(마감 11-16 · 단계 11-17·18) · 동점은 `rcept_no` 순.
5. 결정 3a: 기존 같은 꼴 0행(관문 묶음 아님 · `q03`·`q08`) · 사정거리가 A48과 반대(비지배 「판정 불가」에 A48은 폴백 유지) · 표지 해제 규칙 없음(창 계산기가 null을 키움) · 「폴백은 원장이 개념을 안 주는 종목에만」 거짓(A48③ 꼴 307행 · 덕산하이메탈 2024Q3 지배 자본 빔 — 보고).
6. 결정 5 예산이 수가 없음(213짝 × 8.7~11.7초 = 31~42분) · 「G 적합일 37/261」은 버린 절대 모형의 수.
7. 결정 6 소유자 변경은 값을 바꾸는 변경 — 설정 뒤 · 관문 규칙에 대한 입장 없음.
8. (작음) progress:56 낡음(고침 aee7cc45) · 관찰 2차 「11-16 이후」는 몰린 날과 겹침(`usedToday`가 세션 실키를 못 봄) → 11-19 이후.
- 선다(쟀다): 셋째 길 철회의 순위 · 화면·백분위 자가 치유(13:30 원본층 · 14:20 ①②) · 미국·국내 순위 안 섞임 · 폴백 소비자 수 · FR 강제 11-10 · crons-done과 양립 · progress는 PLAN만 가리킴.

## 2부 v6 반증 판정 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut-p2v6/` · DART 21콜 16:19:45~16:27:24)
1. **(a)·(b)·(d)에 끝 상태가 없다** — 표지를 지우는 주체·시점 없음 · (a) 뒤 FSR 칸 빈 채 미소비 → 정기에서 빠지고 매 평일 81콜 · FSR이 와도 표지가 남으면 11(+4) 지표 영구 null(우리가 만든 가짜 한계) · 영구 013 실물 0(카카오뱅크 10행뿐 · `q03`) → 표지 수명주기(FSR이 `rcept_no ≥ R`로 쓰는 같은 트랜잭션에서 지움 · 시험) · (a) 상태는 FSR 탐침 1~2콜만 · 정기 제외는 「진단 전 미소비」만.
2. **표지 소비자 넷 누락** — `FinancialMath.normalizedTaxRate:943`이 NULL 해를 건너뛰고 중앙값 → `RoicAfterTax*` 셋 · `EffectiveTaxRate` · 0콜 모의 273 중 262가 값을 내고 252가 다름(최대 46.6%p) → 소비자를 성질로(「표지 행의 FSR 전용 칸을 읽는 모든 계산기」) · 성질 시험.
3. 「폴백은 원장이 안 주는 종목만」 거짓 — 지배순이익 NULL · 비지배 실재 15행 4종목(솔브레인홀딩스 · NHN · 펌텍코리아 · SK바이오팜) · 2/2 원장이 값을 줌(펌텍 「-표준계정코드 미사용-」 · 솔브레인 계속+중단 지배) · 매퍼가 ID 둘만 봄(`DartFullAccountMapper:106-108`) → 계획 문장을 「(a) 라벨에서」로 · 매퍼 결함 재고(보고).
4. 불변성 관찰 — (a) 신영 2026/11012가 지금 013 → 라벨은 「이미 낸 최근 보고서 셋」 (b) 비교 키가 `ord`·`thstrm_add_amount`·`account_detail`·`currency`·`sj_div`·`fs_div`를 빠뜨림 → 원시 행 전 칸 `ord` 순 · 차이면 어댑터 0콜 영향 (c) OFS 전용은 라벨의 성질(에코프로머티) · 0콜 대리 = 지배자본이 한 번도 없는 현 편입 20종목(리노공업 CFS 013 1/1) (d) 95% 상한은 독립 가정 — 회사 단위 3/20 = 15%.
5. 결정 6 표본(60라벨)은 알려진 크기의 불일치를 못 봄(12,600 중 1라벨 볼 확률 0.5% · 18라벨이어도 8%) · 5라벨 공유 9칸 0/45 → 짝마다 공유 칸 교정 셀을 결과 행에(`FinancialWriter:668`) · 또는 RF2 전 국내.
6. 결정 2 — 81은 평균(73~108 · OFS 전용 108) · 고정 나눗셈이 넘치면 가드가 짝 도중 RATE_LIMITED · G 강제일엔 G 몫을 먹어 연쇄 → 짝마다 `usedToday + need ≤ 18,000 − 예약` · 「700」 대신 단계 시작 `usedToday` · 수 정정(현 편입 최대 287 · 209/일이면 이틀 · G 강제일 114/일 사흘 · 2026-11-14 토).
7. 결정 7 — 재구성이 제외 종목을 대상으로 셈 → 실패가 거짓 해결 → 제외 종목 ID를 결과 행에 · 「대상 ∖ 제외」.
8. 결정 4 — 손 FR엔 목록이 없음(오늘 정기공시 4건이 전부 같은 날 [기재정정]) → 받은 번호가 공시 표에 없으면 FR 칸 안 씀(또는 손 FR도 목록).
9. (작음) 탐침이 FR 1콜만 봄(같은 날 반영 4/4 · 피크일은 못 잼 → FSR CFS 탐침 1콜 더) · N평일은 「시도한 날」로 · 1월 첫 평일 FR 10년·FSR 9년(85콜 · 해 없음).
- 덤(남의 결함 · 보고): 매퍼 결함(비표준 코드 · 계속+중단 지배 순이익) · 연결 전환 종목의 창 혼합(에코프로머티 OFS→CFS · 지배자본이 라벨마다 있다 없다 하는 현 편입 19).
- 선다(쟀다): 폴백 소비자 11 + `GovernanceFactDeriver:222` · DART 020 = RATE_LIMITED(013 오인 없음) · 같은 날 반영 4/4 · 공유 칸 0/45 · 신영 3월 결산 탐침 규칙 · 응답에 바뀌는 칸 없음 · `targetDateFor` = 화해 `dataAsOf` · 하루 늦게 보인다(①이 매일 편입 전원) · 결산월 12월 349 · 3월 1 · 정정 이력 29 · FSR 미반영 라벨 카카오뱅크 10행뿐.

# 2부 계획 v7 (2026-10-06 17시 · v6 두 렌즈 반영 · v6 결정 중 바뀐 것만)
## v7 실측(내가 잼 · 반증 실측은 그 절)
- 오늘 크론 사슬(main 기록): 13:30 → 15:24:51 · 14:00 → 15:54:51(기업 행위 16:10:50 끝) · 14:15 → 16:10:50(무거운 잡 없음 — 풀 1이라 앞 사슬 뒤) · 14:18 → 16:12:51 · 14:20 → 16:14:51(① 16:17:27 · ② 16:20:35).
- 판정 스크립트는 크론마다 끝 줄로 묻는다(main `373dc2b1`) · 진입점이 부른다(`8f72ddc8`).

## 결정(v6에서 바뀐 것만)
1. **단계는 13:30 일일 갱신 앞에서 돈다 — 12:30 · 무거운 잡과 한 `@Scheduled`로 묶는다**(계획 밖 2·3): 워치리스트가 13:30에 판정하므로(`DailyUpdateJob:404-409`) 단계가 그 뒤면 공시 다음 날 판정이 그 공시 없이 나간다(알림은 회수 안 됨). 한 메서드 안에서 단계 → 무거운 잡 순서라 「오늘 단계가 끝났다」 술어가 없다(자정 넘김 · 크론마다 다른 밀림에 안 기댄다). 대가: 무거운 잡 날은 13:30 갱신이 그 뒤로 밀린다(풀 1 · 최악 12:30 + 42분 + 55분 ≈ 14:07 · 값은 안 틀리고 늦을 뿐) — **채택 조건은 DART 반영 시각 실측**(아래 RF7 · 12:00~13:00에 전날 공시가 두 엔드포인트에 다 있는가). 반영이 안 되면 그 공시는 「반영 대기」로 남고(결정 3) 워치리스트는 아래 6으로 보류한다.
2. **몰린 날의 수와 순서**(계획 밖 4 · 계획 안 6): 모집단은 「그날 편입」(현 편입 최대 287 · 2026-08-14) · 짝마다 `usedToday + need(종목) ≤ 18,000 − 예약(그날 무거운 잡 몫)`을 묻고 넘으면 멈춘다(`need` = 그 종목의 직전 FSR 콜 수 + FR 36 · 모르면 108) · 「기업 행위 700」 상수 대신 단계 시작 시 `usedToday` · 순서 = 공시일 → `rcept_no`(원장 접수 순 · 같은 종목이 분기마다 밀리지 않게) · 2026 3분기 마감은 11-16(월 · 11-14는 토) → 단계 11-17 · 11-18 · G 강제일과 겹치면 사흘(114/일).
3. **표지 수명주기와 끝 상태**(계획 안 1 · 계획 밖 5): (a) FR 커밋 때 「FSR 미반영」 표지를 단다 → **FSR이 그 라벨을 `rcept_no ≥ R`로 쓰는 같은 트랜잭션에서 지운다**(그 뒤 화해가 값 복귀) · (a) 상태에서는 전 창 짝 대신 FSR 탐침(CFS 1 · 013이면 OFS 1)만 매 평일 · 정기 FR·FSR 제외는 「진단 전 미소비」만 — (b) 종목은 정기에 넣는다(FR 번호 < R이면 R 행을 못 만들어 해가 없다) · (b)·(d)는 결과 행과 감시에 남아 판정을 기다린다 · **표지 소비자는 성질로**: 「표지 행의 FSR 전용 칸(지배주주 자본·순이익 · 세율 재료 · OCF · capex …)을 읽는 모든 계산기」 → 성질 시험(표지 행을 넣은 컨텍스트에서 출력이 바뀌는 계산기는 null) — 지배 폴백 11 + `GovernanceFactDeriver:222` + 세율 중앙값 넷(`RoicAfterTax*` 셋 · `EffectiveTaxRate`) · **사정거리 = (a) 라벨만**: A48(비지배 「판정 불가」에 폴백 유지)과 다른 처리인 근거는 「(a)는 원장이 그 칸을 곧 준다는 것을 아는 일시 상태 · A48은 원장이 그 라벨에 안 주는 것」 — 「폴백은 원장이 개념을 안 주는 종목에만 남는다」는 쓰지 않는다(지배순이익 NULL · 비지배 실재 15행 4종목은 매퍼 결함 후보 — 재고).
4. **국내 소비 칸**(계획 안 8): 손 FR이 받은 번호가 공시 표에 없으면 FR 칸을 안 쓴다(목록을 안 받으므로 · 안전한 쪽) · 같은 날 정정은 다음 단계가 목록으로 집는다.
5. **실패 해결 술어**(계획 안 7): 정기 실행이 뺀 종목 ID를 결과 행에 남기고 술어 = 「(대상 ∖ 제외)에서 실패 부재」.
6. **워치리스트 보류**(계획 밖 2 · 새 결정): 판정일 asOf에 「공시일 ≤ asOf인 미소비 공시가 있는 종목」은 그날 워치리스트 판정에서 보류한다(알림을 안 보내고 다음 판정으로 넘긴다 · 보류 수를 실행 기록에) — 낡은 값으로 참이 되는 알림을 원리적으로 없앤다. 몰린 날 이튿날 몫(40~72)과 반영 대기가 그 대상이다.
7. **공유 컬럼은 재지 말고 기록한다**(계획 안 5 · 계획 밖 7): 짝마다 공유 칸 교정 셀(칸 이름 · 전/후 · 출처 잡)을 결과 행에 남긴다(`FinancialWriter:668`이 이미 칸 이름을 찍는다) → 첫 시즌이 콜 0으로 모집단을 잰다 · 소유자 변경은 값을 바꾸므로 **설정 뒤 · 국내 관문 묶음**이고 그때까지 2부는 「마지막에 쓴 잡이 이긴다」 상태를 기록하며 돈다.
8. **강제일 달력**(계획 밖 1): 2부 배포 전 현 jar가 G 10-19 · FSR 10-20 · FR 10-30에 돈다 → 2부 시대: FR 강제 11-10 · G·FSR 재연체 11-16/17(몰린 날) · 강제 G 11-26 · FSR 11-27 — 결정 1로 단계와 무거운 잡이 한 메서드라 「적합일」은 그날 DART 잔량으로만 정해지고, **연속 강제일**은 하루 하나 규칙 그대로 + 밀린 쪽의 예산 초과 알림을 미룸 사유와 함께 적는다 · 10-30 전제 소비자 여섯(progress:55·218·239 · reload-gate:65 · ADR-0172 · ADR-0173:102)을 2부 배포 때 고친다 · G 재시도 마감 = 2부 시대 첫 정기 G(11-16 전후) 전.
9. **탐침에 FSR CFS 1콜**(계획 안 9) · N평일 = 시도한 날 수(한도로 미룬 날 제외).

## 불변성 관찰 규칙 v2(대조 전 커밋 · v6 규칙을 바꾼다)
- 라벨 = 종목마다 「이미 낸 최근 보고서 셋」(1차에 013인 라벨은 바꾼다 · 3월 결산 포함) · OFS 층 = 「FSR이 채운 행은 있는데 지배자본이 한 번도 없는 현 편입」 20종목 중 시드로 4(라벨마다 CFS 013을 1차에 확인).
- 비교 = 원시 행 전 칸을 `ord` 순으로(계정 · 금액 · `thstrm_add_amount` · `account_detail` · `currency` · `sj_div` · `fs_div` · `rcept_no`) · 차이가 나면 두 응답을 어댑터에 0콜로 먹여 영향을 적는다.
- 판정: 하나라도 다르면 채택하지 않는다 · 상한은 회사 단위로 적는다(20사 0이면 약 15%) · 2차는 11-19 이후(몰린 날 단계와 한도를 안 겹치게).

## 드라이런 규칙 v7 더함(대조 전 커밋 · 실키는 평일 13:20~15:45 밖 · 순차)
- RF7 **반영 시각**: 10-07 12:00·12:30·13:00(실키 창 밖)에 10-06 공시(목록)의 두 엔드포인트 번호를 물어 「12:30에 다 있다」 비율을 적는다 · 13:19와 15:46 값이 같으면 「단계 시각에 반영」으로 읽는다 · 결정 1의 채택 조건.
- RF3 시각 모형: 결정 1의 한 메서드 · 몰린 날(그날 편입 287 · 예약) · 강제일 달력(결정 8).
- RF4 표지 수명주기: (a) → D+k FSR 도착 → 표지 0 → 화해 뒤 값 복귀 · 성질 시험이 세율 넷을 잡는가.
- RF9 워치리스트 보류: 미소비 공시가 있는 종목이 그날 판정에서 빠지는가 · 다음 날 판정되는가.

## 1부 v9 확인 반증 — 계획 안 렌즈(2026-10-06 · 「수정 후 진행」 · `rebut14/` · 0콜 · 사본 `stock_screener_r14a_test` · 코드 사본 `r14copy`)
1. **crons-done이 이른 발화를 「아직」으로**(두 판 모두) — 운영 13:30·14:00 첫 잡 62건 중 21건이 명목 전(최대 −2.189초) · 바로 끝나는 크론은 끝 줄도 명목 전 → 그날 내내 1 → stop·deploy가 멈춰 `--force` 습관 → **고침(main `68f9675a`)**: 명목 − 60초 · 연습 S6(−2초) 0 · S7(−90초) 1.
2. **길이가 다르면 같은 실행에서 다시 안 받음** — 503은 3콜 뒤 TRANSIENT · 끊긴 고정 길이 본문은 1콜로 MALFORMED → 다음 FR까지 빔(가짜 한계) → **v10**: 덜 온 2xx를 `withRetry` 안에서 다시 받음.
3. a74c6204의 거짓 0은 373dc2b1이 고침(확인 · 실제 ThreadPoolTaskScheduler 역전 재현) · 재고 행 「기동 때 고정」 문장(고침 `68f9675a`).
4. (a) `captured=stage`를 받으면 안 됨 (b) 앱의 `.env`(배포 폴더 복사본)와 worktree가 링크한 main `.env`가 다를 수 있음 (c) jar 바이트 대조(재보지 않음) → v10 ADR: `captured=build` · `cmp -s`.
5. (잠복) 범위 밖 `@Primary SupplementLedger`가 판 고정을 지남 → v10 성질 시험 · 출력 해시는 JDK feature만(Jackson·Spring 판 · 업데이트 판 빠짐 — 재료 밖) · 리베이스·체리픽 병합이면 덧붙이기 검사 영구 붉음 → javadoc에 merge만.
6. (작음) V64:6·35 · `SupplementLedger:32`가 「낮다」 → v10 고침(V64 체크섬 바뀜).
7. (작음) `folder()` 크기 검사가 모든 항목 · 19자리 넘으면 `NumberFormatException` → v10: 고른 인스턴스만 · 파싱 실패 MALFORMED.
8. (작음) N=2 되돌리기가 근사(덮어쓴 앞 행이 안 돌아옴) → v10: 보충 행도 사본 상태로(CIK 행 delete+insert).
- 선다(쟀다): 색인 크기(567 · 33,303항목 · 수 31,602 · 빈칸 1,701 = html 1,134 + .txt 567 · 이름 중복 0 · 후보 둘 이상 0 · 인스턴스 530/530 바이트 같음 · 수집 도구가 운영과 같은 전송) · rs1c 보충 545행 전부 판 3 · 사본 전체 시험 1,824(실패 둘은 환경) · PATH의 jcmd(17)가 JDK 21 JVM을 읽음 · N=2 대상 질의 13 · 서명 질의 3.2초 · dump-db TRUNCATE가 FK를 다 품음 · 빌드 입력에 무시·미추적 0.

## 1부 v10(2026-10-06 · 보류 브랜치 `8b706933`·`9eb16f28` · 시험 1,833/0 · 판 4)
- 위 2·4·5·6·7·8 + v9 계획 밖 3(`--first-parent` · git 없는 사본은 건너뜀 표시).
- 대조(내가 잼): 같은 실행 재시도를 뺀 변이 → 「받다 만 본문은 같은 실행 안에서」 시험 붉음 · 새 시험 셋(재시도 · 남의 항목 크기 · 20자리 크기) 초록.
- 되돌리기 연습(지금 꼴 · `p1v8/drill10.sh`): `$BASE` = `rs1e`(V64 Flyway · 보충 0) · 운영 자리 = `rs1c`(v9 재생) → 109 두 표 서명 = `$BASE` · 107 CIK 보충 161 → 0 · 다른 CIK 384 그대로.

## 1부 v10 확인 반증 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `rebut15/` · 0콜 · 첫 에이전트는 세션 한도로 끊김 — 다시 띄운 에이전트가 앞 결과를 전부 다시 잼(M2 붉음은 javadoc 글자에 걸린 거짓 붉음))
1. **「덜 온 2xx」가 받기 길 셋 중 하나에만** — `index.json` 끊김 → 1콜 FAILED/MALFORMED(세 번 끊겨도 1콜 · 200 HTML도) · submissions 끊김 → 1콜에 그 종목 보충 0 · 회수정지 · 대조 503 → 둘 다 2콜 OK · 뿌리 `json()`이 `incomplete = r -> null`(`SecFilingClient:256-258`) → 깨진 JSON은 재시도 밖 MALFORMED → 「2xx인데 JSON으로 안 읽힘」을 덜 온 응답으로 · 판 5 · 덤: companyfacts 본 호출(`SecEdgarFinancialAdapter:131-133`)도 한 번 받고 MALFORMED(코드로만 읽음).
2. **범위 밖 구현 성질 시험이 `implements X` 글자만 봄**(잠복 · 실물 0) — 하위 인터페이스 · `extends SecFilingSupplementStore`+`@Primary` · `@Bean` 익명 하위 클래스 · `extends SecFilingSupplement`/`SecFilingClient` 다섯 변이가 판 고정 7 ok / 1 skip · 다섯 다 주입 · X1~X3은 TRANSIENT를 UNREADABLE로 저장 → javac 속성 분석으로 「src/main 모든 형 중 범위 형의 하위형은 범위 파일 안」 · Spring 컨텍스트에서 `AopUtils.getTargetClass`로 세 빈이 범위 클래스인지 · 정규식은 주석에 반응(거짓 붉음).
3. **`crons-done.sh` RUNNING 검사가 MANUAL 유령 행까지 셈** — 스위퍼가 닫은 MANUAL 유령 12건(08-06~09-08 · 평균 33.7시간 · 최대 76.2시간 RUNNING) · 가짜 홈 G1에서 stop rc 1 · `--force`는 끝 줄 판정까지 통째로 건너뜀 → 앱 소유 행(SCHEDULER·ADMIN)만 · MANUAL은 살아 있는 bootstrap JVM이 있을 때만.
4. **`deploy.sh`가 판정(stop) 앞에서 스테이징** — 거절된 배포가 `app.jar`·`.env`·`app.jar.commit`을 새것으로 바꿔 둠 → 다음 기동이 말없이 그 jar를 띄움 · ADR 0-2 「지금 도는 jar」 거짓 → 판정을 스테이징 앞으로(또는 임시 이름 → stop 성공 뒤 교체) · `start.sh`가 실제로 띄운 jar의 커밋을 기록.
5. (작음) ADR 0단계 순서 — 읽기 전용 검사(0-2 · 0-3 · 0-4)를 stop 앞으로 · `E0`은 stop 뒤 · 멈출 때 `start.sh --app` 명시.
6. (작음) `crons-done.sh` psql에 연결 시간 제한 없음(말 없는 DB에 16초 넘게) · rc 2를 stop이 「크론이 남았다」로 찍음 · 인자 없는 stop의 안내 `ops/stop.sh  --force`는 사용법 오류.
7. (작음) 되돌리기 CIK 줄이 조용히 줄어듦(파일 없음 → rc 0 · `cik` 없음 → `null`) → CIK 수 = 행 수 · 전부 수 단언 · 2-5에 「2-3 종료코드 0 · 실패=0」.
8. (잠복) main에 합친 뒤 `--first-parent`는 merge 커밋 M 하나만 냄(M^1에 파일 없음 → M이 기준선) → M을 M^2와 비교.
- 선다(쟀다): `instance` 같은 실행 재시도(끊김→정상 2콜 OK · chunked 예외 → TRANSIENT → OK · 404 2콜에서 멈춤) · 실패 종류 섞임은 마지막 시도가 이김(소비자는 UNREADABLE 비교 하나라 행동에 안 닿음) · 재생 하네스가 실제 `SecFilingClient`를 거쳐 길이 대조를 탐 · `run-app.sh`는 늘 스케줄러 켬 · 앱이 죽고 PID 파일만 남으면 0 · 토요일 20:40 끝 줄 없음 1 · 21:30 있음 0 · 14:16 기동 14:17 판정 0 · 앱 기동 2.7~3.0초.

## 2부 v7 반증 판정 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행 — 결정 6은 문장을 다시 지어 그 절만 다시 받는다」 · `rebut-p2v7/` · 0콜 · 첫 에이전트는 세션 한도로 끊김)
1. **결정 6의 술어는 단계가 이미 목록에서 본 공시만 본다 — 「원리적으로 없앤다」 거짓** — 공시 행은 단계가 목록에서 처음 볼 때 생김 → 단계가 asOf까지 목록을 확인하기 전에 13:30이 판정하면 보류 0 · 갈래 넷: (i) 수면 역전(실제 Spring 7.0.8 가상 시계 — 13:20부터 70분 자면 다음 날 13:30이 13:30:00 · 12:30 단계는 13:43:36 · 50분이면 순서 보존 · 빈도 안 잼) (ii) 12:30~13:30 사이 냉기동(12:30은 다음 날로) (iii) 단계 예외(크론은 `BatchShutdownException`만 잡음) (iv) 목록 실패·늦은 색인(EST 색인 12:03~12:21 KST · 12:30까지 최소 8.3분) → 보류를 **시장별 단계 결과 행**에 묶음(「그 시장 확인 상한 ≥ asOf ∧ 오늘 단계 끝남」이 아니면 그 시장의 재무 유래 조건 항목 전부 보류) · 시험이 넷을 주입 · 함정: 같은 asOf 재판정은 처방이 못 됨(알림 키 `watchlist:%d:%s` · `recordEvaluation`이 `last_satisfied=true`를 적어 둘째 판정의 새 충족이 영영 안 나감).
2. **보류는 보류된 종목만 지키고 다른 종목의 백분위는 낡은 값으로 움직임** — `PercentileCalculator` RANKED = `PARTITION BY universe_code`의 `PERCENT_RANK` · 알림 걸린 항목 005930(「저평가 가치주」)의 단면 조건 셋 · 모의(08-14 Q2 283): 보류 40이면 보류 안 된 종목 중 `PBR_PCTL≤0.30`이 평균 0.8 · 최대 4 뒤집힘(76이면 1.4 · 4) → `indicator.population`(UNIVERSE · SECTOR)을 참조하는 항목은 그 모집단 구성원 하나라도 미소비이면 보류 · 그러면 (b)·(d)가 그 유니버스 단면 알림을 영구히 막으므로 (b)·(d)의 끝 상태(상한 뒤 그 종목 재무 유래 지표 null · 보류 해제) + 항목 단위 보류 상태를 화면에 · 2일 이상이면 운영자 알림.
3. **결정 1 × 2: 12:30 `usedToday`에 14:00 사슬 몫이 없어 사슬이 굶음** — 사슬 국내 몫 = `irdsSttus` 2콜 × 350 = 700 · 한도가 바닥나면 `DartCorporateActionAdapter:484`가 종목마다 RATE_LIMITED(치명 아님) · 굶기 시작하는 단계 사용량: FSR날 1,456(약 18짝) · FR날 4,700(58) · G날 9,250(114) · 없음 17,300(213) · 11-17은 FSR 재연체 × 몰린 날 → 예약에 사슬 몫 · RF3에 「사슬 RATE_LIMITED 0」.
4. 결정 9: 탐침이 FSR CFS 013을 받을 때의 뜻이 없음 — OFS 전용 후보 20 · 에코프로머티 · HK이노엔 · 376180이 CFS 013 · OFS 000(같은 rcept) · 리노공업 CFS 013 → 탐침도 「CFS 013이면 OFS 1콜」(결정 3과 같은 규칙).
5. 결정 1: 한 메서드면 단계 예외가 그날 무거운 잡을 지움 · finally로 돌리면 목록 실패일 미룸·진단 전 미소비 제외가 깨짐 → 「단계 결과(성공 · 목록 실패 · 예외 · 종료)마다 G · 국내 FR/FSR · 미국 FR 중 무엇이 도는가」 표와 시험 · 예외 전파 + `addSuppressed`.
6. **RF7은 실패할 수 없는 표본** — 10-06 목록 4건 중 3건을 16:19~16:27에 이미 물어 반영 확인 → 10-07 12:00에 재면 당연히 반영 · 결정 1이 기대는 경우(몰린 날 다음 날 12:30)는 11-17 전 원장에 없음 → 「채택 조건」 틀을 걷고 결정 1을 미반영에도 안전하게(지적 1) · 11-17은 관찰(DART 번호 + 색인 게시 시각).
7. 결정 3 문구 — 성질 시험 「출력이 바뀌는 계산기는 null」은 공유 칸만 읽는 PSR·매출 성장도 null로 만듦 → 「표지 행의 FSR 전용 칸만 다른 두 컨텍스트」 · FSR 전용 칸 집합은 두 매퍼에서 유도 · 해제 조건 → 「그 라벨에 rcept ≥ R인 FSR 응답을 처리했다」(FSR 전용 칸이 원래 빈 라벨 — 카카오뱅크).
8. (작음) 실키 창 「13:20~15:45 밖」은 앱의 DART 구간이 12:30~14:20으로 옮겨가면 함께 옮겨야 함.
- 선다(쟀다): `CronsDoneTableTest`가 12:30 크론 누락을 잡음 · 풀 1 순서는 수면 60분 초과일 때만 역전 · 최악에도 15:30 점검 전 끝(약 15:07) · 미국 색인 12:30에 60/60일 떠 있음(여유 얇음) · 둘째 날 몫은 13:30 원본층이 자가 치유 · 미국 영구 미소비 후보 0(rs1c) · `DailyUpdateJob:404-409` · 287 · 11-14 토 · `FinancialWriter:668` 맞음.

## 2부 v7 반증 판정 — 계획 밖 렌즈(2026-10-06 밤 · 「수정 후 진행 — 결정 1은 형태를 바꿔 다시 받고 결정 6은 구현 전에 사용자 결정」 · `refute-p2v7/` · 0콜 · 첫 에이전트는 세션 한도로 끊김)
1. **결정 1의 목적(단계 → 워치리스트)이 크론 시계에 기댄다 — 역전 재현** — 실제 Spring 7.0.8 `ConcurrentTaskScheduler`·`CronTrigger`를 가상 단조 시계에(`cronsim/CronSleepSimV7` · `s01_sim_rerun.out`): 월 13:40 덮개 수면 2시간 → 화 13:30(워치리스트) 13:30:00 · 12:30 메서드 14:30:00(14:18·14:20도 단계보다 먼저) · 대조 18:55 수면은 순서 유지 · 창 = 12:30 메서드 끝 ~ 13:30 잡 끝 사이 60분 넘는 수면(가벼운 날 12:35~13:44) · 앱이 12:30엔 없고 13:30엔 있는 날도 같음 → **단계와 워치리스트를 한 `@Scheduled` 안의 호출 순서로**(13:30 메서드 머리: 단계 → 주식수 기저 유도 → 일일 갱신 → 무거운 잡) · 이러면 5(벽시계 소비자)·6(SEC 여유)도 풀림 · 「일일 갱신에 초기 적재를 섞지 않는다」 사유가 서는지는 안 잼(`DailyUpdateJob` 안이 아니라 스케줄러 메서드에서 부름).
2. **피크 날 14:00 기업 행위 사슬이 DART 한도를 못 받음**(④ — 「700 대신 `usedToday`」는 단계가 사슬 뒤일 때의 처방) — 2026-11-17 단계가 18,000까지 쓰거나 G날 단계 9,950 + G 8,050 → 기업 행위 국내 전 종목 RATE_LIMITED(치명 아님) · `PipelineSchedulerTest`의 순서 시험 둘이 결정 1로 붉어짐(대신할 불변식이 없으면 지우는 쪽으로 고쳐질 위험) · 기업 행위 국내 실제 콜 수 안 잼(700은 javadoc · 대상 1,350 → 1,683) → 예약에 기업 행위 몫(실측 최대 · 실행 기록에 콜 수) · 시험 「단계 + 무거운 잡 + 기업 행위 ≤ 18,000」.
3. **13:30 계산이 새 판을 기저 유도 전에 읽음** — 주식수 기저 NULL = OK(`FinancialFigures:100-109`) · 유도는 14:18 · v7은 쓰는 쪽만 13:30 앞으로 → 13:30 원본층 재계산·워치리스트가 NULL 판을 읽음 · 지금 표지 종목 둘(AKTX · AMRN)은 현 편입 아님(영향 0 · 기제만) → 같은 메서드 안에서 단계·무거운 잡 뒤, 13:30 계산 앞에 미국 주식수 기저 유도(이름 붙은 예외로 격리).
4. **결정 6은 spec 해석 — 사용자 결정 대상 · 「원리적으로」 거짓** — spec §7(83행) 「일일 갱신 배치는 조건 충족 여부도 함께 확인 … 통지」 · 보류는 그날 확인을 안 하는 것 · (a)·(b)·(d)는 끝이 없어 무기한 · 화면 표시 없음 · v6 계획 밖 2가 「사용자 확인 대상」으로 적었는데 v7이 「새 결정」으로 채택 · 원리적이지 않은 셋: 백분위(005930의 조건 8 중 셋이 PCTL · 동료 40~72종목의 낡은 값) · 목록 없는 날(행이 없음 ≠ 공시 없음) · 종목 단위 과보류(가격만 건 항목 — 지금 0) → 사용자 선택지(보류 단위 · 상한과 화면 표시 · 「14:20 뒤 재판정」) · 목록 못 받은 시장은 그 시장 재무 유래 항목 보류.
5. **시각을 옮기며 벽시계 소비자를 안 셈**(②) — 「13:30~15:40 밖」 main 9 + hold/p1 1 · 실키 창 · 크론 목록 · 14:15 서술(ADR-0116 · ADR-0166 · ADR-0113 · javadoc · data-pipeline 표) · `g01_wallclock_consumers.txt` → 1의 처방이면 시작 시각은 그대로(끝 시각만 다시 잼).
6. RF7이 판정이 필요한 모집단을 안 잼 — 10-07 표본은 10-06 정기공시 4건(전부 [기재정정] · 피크 아님) · SEC는 RF7에 없음 · 2025 QTR4 색인: EDT 22일 최소 여유 69분 · EST 38일 최소 8분 17초(11-25) · v6 반증의 「D+1 11:04」는 EDT 수(④) → 1의 처방이면 여유 68분 이상 · 아니면 RF7에 SEC(EST)와 첫 피크(11-17)의 반영 대기 수.
7. (작음) 「최악 ≈ 14:07」에 사슬 안 화해(최악 약 12분)·1부 보충 몫 빠짐 · 무거운 FR이 앞으로 오면 그 무효화가 14:20까지 과거 날짜 백분위에서 비는 창이 길어짐(최신일은 13:30이 다시 씀) · `reload-gate.md:56`의 「평일 14:15 단계」가 낡은 요약.
- 선다(쟀다): spec에 갱신 시각 문구 없음 · 미국 시세 경로 무관(Alpaca 일일 한도 0) · `CronsDoneTableTest`가 표 어긋남을 잡음 · 고른 수면은 순서 유지 · 15:30 점검 최악 약 14:48(계산) · progress:146은 PLAN만 · v7이 버린 결정을 안 되살림 · 배포 순서 0부 → 1부 → 2부(2부가 1부 술어를 부름) · 10-26은 모형상 목표(넘어도 값은 안 틀림) · 강제일 G 10-19 · FSR 10-20(현 jar) · FR 10-30(2부 jar).

## 1부 v10 확인 반증 — 계획 밖 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `refute15/me/` · 0콜 · 운영 JVM에 jcmd도 안 붙임 · 첫 에이전트는 세션 한도로 끊김)
1. **[회귀 · 8f72ddc8] 배포가 스테이징 뒤에 멈추면 배포 폴더만 새것** — 가짜 빌드가 가짜 시계를 13:29 → 13:31로 밈: 빌드 앞 판정 0 → `stage.sh`가 `app.jar`·`app.jar.commit`(새 커밋 · captured=build)을 덮음 → `stop.sh` 판정 1 → rc 1 · 옛 JVM 생존 · 안내 `ops/stop.sh --app --force`를 따르면 앱이 꺼지고 아무것도 안 올림 · 그 뒤 `crons-done`은 「앱이 안 떠 있다」로 0 · 0부라면 다음 기동 때 V63이 통제 밖 시각에 적용 → deploy 순서를 build → 판정 → stop → stage → start로 · deploy에서 불린 stop의 안내는 `ops/deploy.sh --force` · 시계 미는 가짜 빌드를 회귀 연습으로.
2. **`crons-done = 0`은 「지금 내려도 빠질 발화 없음」이지 「그날 크론이 끝났다」가 아님** — 새 JVM 기동 시각으로 수 09:00 0 · 토 19:00(리밸런싱 전) 0 · 같게 쓴 소비자 셋(progress:56 0부 「그날 크론 뒤」 · progress:306은 373dc2b1이 걷은 옛 술어로 설명 · ADR-0173 둘째 손 FR) → N=2가 리밸런싱 전에 돌면 1단계 `max(effective_from)`이 10-02 명부를 조용히 고름 → N=2에 기록 관문(SCHEDULER `universe-rebalance` `target_date=2026-10-09` SUCCESS · `max(effective_from)='2026-10-09'` · 20:00 뒤 `크론=runUniverseRebalance` 끝 줄) · 「그날 크론 뒤」는 별도 술어 · progress:306 고침.
3. **[회귀 · 373dc2b1] 「끝 줄을 넣기 전 코드다」가 새 JVM에서도 뜸** — 조건이 「로그에 끝 줄 없음」이라 기동 뒤 첫 발화 전에도 뜸(운영 15406 끝 줄 지금 0) → 오늘 `--force`를 정당화한 문구가 내일 0부 창에서 `--force` 습관을 가르침 → 갈래를 걷거나 「기동 뒤 첫 발화가 아직 안 끝났다」로.
4. 같은 실행 재시도가 인스턴스에만(계획 안 1과 같음) — 같은 날 캐시 보충 응답 2,885 중 인스턴스 530(18%) · index 567 · submissions·페이지 1,788은 `json()` → 표(index 잘림·끊김 1콜 MALFORMED · submissions 잘림 uncertain · 대조 인스턴스 2콜 OK).
5. 문서·안내 어긋남 — README:122 · `deploy.sh` 머리말 「사전 체크 필요 없다」 거짓 · README 「끄기」의 무인자 `ops/stop.sh`에 안내 `ops/stop.sh  --force`는 용법 오류 · README:212 · `browser-driving` 스킬 · `dump-db.sh:82` · TODO.md:75가 멈춤·`--force`를 모름.
6. (작음) psql 실패(rc 2)를 stop·deploy가 「크론이 남았다」로 · `--force`는 RUNNING 검사도 건너뜀.
7. (작음) 재기동 창 4.2초가 명목 시각에 걸치면 그 발화가 빠짐(확률 안 잼) → 「다음 명목까지 여유」를 판정에.
8. (작음) p1이 reload-gate 표 밖 줄 셋을 바꿈 → 「표 밖 줄 = 바꾼 쪽의 판」 단언.
- 선다(쟀다): 오늘 `--force` 근거(옛 14154가 여섯 크론 시작 · 741~746 SUCCESS · RUNNING 0 · 풀 1) · 새 JVM 판정(18:5x 0 · 다음 명목 10-07 13:30 · 연습 여섯 갈래) · 68f9675a의 수 재현(−2.189초 · 오늘 벽시계 27.98초 역행도 60초 안) · 회전·크론 표 · 모의 병합(M1 Q 0 · M2 Q 둘 · 충돌 1 · 합친 트리 1,830/1 SpaFallback) · `pinnedListsOnlyGrow` merge 이력에서 초록 · v10 대조 재현 · 재시도 비용(정상이면 0).

## 2부 불변성 관찰 1차 수집(2026-10-06 19:00 · 규칙 v2 · 탐침 `DartRawSnapshotLiveTest` · `p2-invariance/`)
- 표본: `p2-invariance/plan.sql` → 20종목 × 최근 보고서 셋 = 60라벨(정정 이력 4 · 금융 4(KSIC 64~66 — 지주회사 포함) · 3월 결산 1 · OFS 대리 4 · 일반 7) · 라벨당 FR 1 · FSR CFS·OFS 2 = 180콜(19:00:45~19:01:45 · HTTP 200 전부).
- 결과: FR 60/60 `000`(바꿀 라벨 0) · FSR CFS `000` 48 · `013` 12 · OFS `000` 60 · OFS 대리 층 12라벨 중 CFS `013` 9 · `000` 3(OFS 전용은 라벨의 성질 — 반증 확인).
- 원시 응답: `p2-invariance/snap1-20261006/`(180 · 10MB · 키 없음) · 2차는 11-19 이후 같은 계획으로 → 원시 행 전 칸을 `ord` 순으로 비교.

## 2부 결정 6(워치리스트) — 사용자 결정과 내 판정(2026-10-06 19시)
- **사용자 문장**: 선택지 셋(재무 조건 항목 보류 · 화해 뒤 한 번 더 판정 · 지금처럼 판정 + 표시만)에 대해 「claude.md 의 판단기준에 따라 선택해.」
- **내 판정(CLAUDE.md 기준 · 사용자가 고른 것이 아니다)**: **재무 조건 항목 보류**.
  - 「판정 + 표시만」은 결손을 표시로 갈음하는 것이라 「싼 것으로 갈음」 금지에 걸린다 — 낡은 값 알림이 그대로 나간다.
  - 「화해 뒤 재판정」은 13:30의 낡은 값 알림이 남고(회수 안 됨) 반영 대기·목록 실패일에는 둘째 판정도 낡다 — 정합성을 못 지킨다.
  - 「보류」는 알림이 늦을 수는 있어도 낡은 값으로는 안 나간다 — 「언제 실행해도 정합성」에 맞는 유일한 안이다. 보류가 영구가 되지 않게 끝 상태(상한 뒤 그 종목 재무 유래 지표 미표시 · 보류 해제)를 함께 둔다.
- 구현 때 ADR에 이 두 줄을 갈라 남긴다(spec §7 해석).

## 1부 v11(2026-10-06 밤 · 보류 브랜치 `58572199`·`56a97d3d` · 시험 1,837/0 · 판 5) + main 운영 고침(`ab4356ca` · `d023cd9f`)
- **보류 브랜치**: 색인·공시 목록 JSON도 덜 오면 같은 실행 재시도(`SecEdgarResponse.unreadableJson` 한 판정) · companyfacts 본 호출에 같은 재시도(404·한도 제외 · 지금까지 재시도 0) · 범위 밖 상속·구현을 파싱 트리로(상속·구현 · 익명 하위 · 범위 인터페이스 반환 · 람다 — 주석 안 걸림) · 덧붙이기 검사의 merge 기준선(첫 부모에 파일 없으면 둘째 부모) · ADR: 0단계 읽기 전용 넷 → 앱 종료(그 뒤 멈추면 `start.sh`) · 둘째 FR 리밸런싱 기록 관문 · 2-5 종료코드·실패 0 · CIK 수 단언.
- **main 운영**: 배포 = 빌드 → 판정 → 종료 → 스테이징 → 기동(멈춘 배포가 배포 폴더를 안 바꿈) · 판정의 RUNNING은 앱 소유(SCHEDULER · ADMIN · GAP_FILL)만 · rc 2 「판정하지 못했다」 · `--force` 아무 자리 · psql 연결 5초 · 다음 명목 5분 안이면 「아직」 · 「끝 줄을 넣기 전 코드」 문구를 두 가능성으로 · README·스킬·덤프 안내·TODO · progress의 문 설명과 0부 「그날 크론 뒤」(= A+A2 첫 정기 실행 확인).
- **대조(내가 잼)**: 보류 브랜치 — `json()` 재시도 빼면 색인·목록 시험 붉음 · companyfacts 재시도 빼면 셋 붉음 · 범위 밖 `extends SecFilingSupplementStore` 붉음 · 주석에만 쓰면 초록. main — 가짜 gradlew가 RUNNING 1을 만들면 배포가 종료·스테이징 전에 rc 1(배포 폴더 그대로) · psql 실패는 「판정하지 못했다」 · 인자 없는 stop 안내 `ops/stop.sh --force` · 앞 연습 여덟 갈래 그대로.

# 2부 계획 v8 (2026-10-06 밤 · v7 두 렌즈 반영 · 결정 6은 위 「사용자 결정과 내 판정」 · v7 결정 중 바뀐 것만)
## 결정(v7에서 바뀐 것만)
1. **13:30 `@Scheduled` 하나가 호출 순서로 돈다 — 단계 → 미국 주식수 기저 유도 → 일일 갱신(지표 · 워치리스트) → 무거운 잡**(v7 결정 1 철회 · 계획 밖 1·3 · 계획 안 5): 단계와 워치리스트가 한 메서드 안의 순서라 수면·기동 시각이 순서를 못 뒤집는다(v7은 12:30 크론이라 실제 Spring 스케줄러에서 역전 재현) · 기저 유도를 갱신 앞에 둬 13:30 계산이 새 판의 기저 NULL을 OK로 읽지 않는다(이름 붙은 예외로 격리 — 실패해도 갱신은 돈다) · 14:15 크론은 없어진다(무거운 잡이 이 메서드 끝) · 시작 시각은 13:30 그대로라 벽시계 창 소비자의 시작 쪽 전제는 안 바뀌고 끝 시각만 다시 잰다(최악 13:30 + 단계 42분 + 갱신 약 22분 + 무거운 잡 55분 ≈ 15:30 — 14:00 사슬·14:18·14:20·15:30은 풀 1 순서대로 그 뒤) · 「일일 갱신에 초기 적재를 섞지 않는다」 규칙은 `DailyUpdateJob` 안을 말한다 — 이것은 스케줄러 메서드의 순서다(잡은 따로).
2. **단계 결과마다 무엇이 도는가 — 표와 시험**(계획 안 5): 단계 결과 = 성공 · 목록 실패(시장별) · 예외 · 종료 × 그 뒤 일일 갱신(늘 돈다 — 워치리스트는 6의 보류) · 기저 유도(늘) · 무거운 잡(G는 늘 · 국내 FR·FSR은 그 시장 단계가 성공일 때만 · 미국 FR은 미국 단계가 성공일 때만 — 진단 전 미소비 제외와 목록 실패일 미룸이 단계 결과에 기대므로) · 예외는 전파하되 뒤 단계를 돈 뒤(`addSuppressed`) · 시험이 넷을 주입.
3. **DART 예산 = 단계 + 무거운 잡 + 기업 행위 ≤ 18,000**(계획 안 3 · 계획 밖 2): 단계는 짝마다 `usedToday + need ≤ 18,000 − 예약`이고 예약 = 그날 무거운 잡 몫 + 기업 행위 사슬 몫(그 잡이 실행 기록에 남기는 실제 콜 수의 최근 최대 · 그 기록이 생기기 전에는 javadoc의 700이 아니라 국내 대상 × `irdsSttus` covering 크기로 계산) · 시험 「단계 + 무거운 잡 + 기업 행위 ≤ 18,000」 · RF3에 「사슬 RATE_LIMITED 0」.
4. **탐침이 FSR CFS 013을 받으면 OFS 1콜**(계획 안 4) — 결정 3(진단)과 같은 규칙 · OFS가 R 이상이면 반영됨.
5. **표지 문구**(계획 안 7): 성질 시험 = 「표지 행의 FSR 전용 칸만 다른 두 컨텍스트에서 출력이 바뀌는 계산기는 표지 컨텍스트에서 null」 · FSR 전용 칸 집합은 두 매퍼(FR · FSR)가 쓰는 칸의 차로 유도(손 목록 아님) · 표지 해제 = 「그 라벨에 `rcept_no ≥ R`인 FSR 응답을 처리했다」(FSR 전용 칸이 원래 빈 라벨도 풀린다).
6. **워치리스트 보류(사용자 결정 + 내 판정 — 위 절)**: 판정일 asOf에 **그 시장**의 「단계가 asOf까지 확인했다(목록 OK · 예외·종료 없음) ∧ 그 시장 판정 모집단에 공시일 ≤ asOf인 미소비 공시가 없다」가 아니면, 그 시장에서 재무 유래 지표(재무를 읽는 계산기가 낸 값과 그 백분위 · 업종 상대 — 계산기의 입력 표로 유도)를 조건에 쓴 항목은 그날 판정하지 않는다(알림 없음 · 평가 기록 없음 → 다음 판정이 정상으로 돈다 · 같은 asOf 재판정은 하지 않는다 — 알림 키 `watchlist:%d:%s`와 `recordEvaluation` 때문에 처방이 못 됨) · 가격만 쓴 항목은 판정 · 항목마다 보류 상태와 사유를 화면에 · 2평일 넘으면 운영자 알림 · **끝 상태**: 진단 (b)·(d)로 상한(결정 3)을 넘긴 공시는 그 종목의 재무 유래 지표를 미표시로 두고 미소비 집합에서 뺀다 → 보류가 풀린다(보류가 영구가 되지 않는다).
7. **RF7은 채택 조건이 아니다**(계획 안 6 · 계획 밖 6): 1이 미반영에도 안전하므로(반영 대기 → 6의 보류) 반영 시각은 채택을 가르지 않는다 · 11-17(첫 피크)을 관찰로: DART 번호의 반영 시각 · SEC 색인 게시 시각(EST 12:03~12:21 KST) · 그날 반영 대기 수.
8. **벽시계 소비자**(계획 밖 5): 시작 시각이 그대로라 「13:30~15:40 밖」 문구는 바뀌지 않는다 · 「14:15」 서술(ADR-0116:23·230·241 · ADR-0166:26 · ADR-0113:241 · `PipelineScheduler` javadoc · data-pipeline 표 · `PipelineSchedulerTest`의 순서 시험 둘)은 2부 배포 때 「13:30 메서드 끝」으로 고친다 — 순서 시험은 지우지 않고 「단계 → 갱신 → 무거운 잡 → 사슬」 불변식으로 바꾼다 · 실키 창 「13:20~15:45」는 앱의 DART 구간(13:30~최악 15:30)을 그대로 덮는다.

## 2부 v8 반증 판정 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행 — 결정 6의 내 판정 문장 둘과 결정 8의 벽시계 문장 둘이 거짓」 · `rebut-p2v8/` · DART 0 · SEC 0 · 운영 SELECT만)
- **선다(쟀다)**: 단계 → 워치리스트 순서는 실제 Spring 7.0.8 `CronTrigger` 가상 시계 6 시나리오 전부에서 보존 · FSR 전용 칸 9(생성자 인자에서 유도 · FR 전용 0) · 업종 층 키의 `KSIC:`/`SIC:` 접두가 시장 단위 보류와 맞음 · 사슬의 국내 DART는 `irdsSttus` 하나 · 결정 4 규칙이 1차 수집 60/60과 모순 없음.
- **1 결정 6(내 판정)**: (a) 끝 상태가 국내 (b)·(d)에만 — 미국 UNREADABLE(v4 결정 9 「출구 없음」)·보충 FAILED·옛 판은 공시 하나가 미국 시장 전체를 무기한 보류 · 국내 (a) 표지 상한 없음 → **원인 무관 「공시 하나가 N평일 미소비면 그 종목만 미표시 + 시장 보류에서 뺌」**(결함 경보는 남김). (b) **미표시·표지 null이 알림 엣지를 뒤집는다** — 값 없음 → 불충족 → `recordEvaluation(false)` → 값 복귀 → 「새로 충족」 재알림(내가 `WatchlistAlertRunner:121-125·213`·`WatchlistStore.recordEvaluation`에서 확인) → 미표시로 비는 항목은 「보류(기록 없음)」 · 미표시 범위는 「봉 ≥ 그 공시일」. (c) 공시일 정의 둘(지표 `published_at` = rcept_no 앞 8자리 · 단계 filed = `rcept_dt` · 저장 list 475 중 4건이 하루 늦음 · 현 종목 0 — 기제만) → 술어·판정 경계를 `published_at`(또는 이른 쪽)으로. (d) 입력 표 유도는 미국 주식수 넷(`MARKET_CAP`·`SHARES_GROWTH_3Y`·`TURNOVER_RATIO`·`TURNOVER_RATIO_60` — priceBars만 선언하고 `FinancialMath.sharesOf`로 재무를 읽음 · 내가 grep으로 확인)과 미국 배당 사실(FY 기간에서 유도)을 놓친다 → 성질 시험(재무 행을 바꾸면 출력이 바뀌는 계산기). (e) 13:30 앞 유도가 주식수 기저뿐 — 미국 배당 사실은 14:18 `deriveInputs`에서만(내가 확인: `shareBasisDeriver.derive` → `governanceDeriveJob.run`) → 갱신 앞에서 거버넌스 유도도. 「늦을 수는 있어도」도 거짓(D 참 → D+1 거짓이면 알림이 사라진다).
- **2 결정 8**: 15:29는 13:30 메서드의 끝일 뿐 — 그 뒤 사슬(실측 최대 15.9분) · 14:18 · 14:20 화해(① 2.5~5.1 + ② 3.1~3.8분)가 풀 1로 붙어 화해 끝 ≈15:51~16:00 · 사슬의 국내 `irdsSttus`가 15:45를 넘김(10-06 로그 16:10:44까지) · DART 한도 묶은 현실 최악도 ≈15:35~15:44 · 합에서 빠진 것 셋(갱신 실측 최대 23.9분 · 단계 끝 사슬 안 화해 · 1부 보충 몫 — 뒤 둘은 v7 계획 밖 7의 지적이 v8에 안 들어감 — **내 누락**) → 「마지막 `indicator_value` 쓰기의 끝」과 「마지막 DART 사용자의 끝」을 따로 재고 창을 사건 기준으로(소비자: `FinancialRefillJob:327` · `InitialLoadJob:177` · `AdjustmentRebuildJob:211` · `CorporateActionJudgeJob:153` · `FinancialRefillAlertTest:101` · data-pipeline.md:182 · batch-load SKILL:582).
- **3 결정 1**: 「14:00·14:18·14:20·15:30은 풀 1 순서대로 그 뒤」는 수면이 끼면 거짓 — `cronsim/CronSleepSimV8`(내가 sim.out을 열어 확인: 목 15:34 +2h 수면 → 금 사슬 14:00 · 유도 14:18 · 화해 14:20이 먼저 · 13:30 메서드가 15:30) · 문턱 50분 · 18:55 수면 대조군은 보존 · 지금 main(문턱 5분)에도 있는 결함을 v8이 「공시 있는 평일」로 넓힌다 → **같은 메서드의 호출 순서로 묶는다(단계 → 기저 → 갱신 → 사슬 → 무거운 잡 → 유도 → 화해)** · 기저 유도는 13:30 앞에 「더한다」(사슬 APPLIED·무거운 잡 새 판 뒤에도 있어야 하므로 유도 자리에 남는다).
- **4 결정 2**: 시장별 무거운 잡이 잡 이름 단위 신선도(`JobFreshnessPolicy.daysSinceLastSuccess`)와 p0 `scopeOf`에 안 맞음(한 시장만 돈 FR = NARROW → 시계 안 감 → 이튿날 연체 · 국내 DART 12,600 중복 / FULL로 세면 못 돈 시장의 월 그물 한 달 빠짐) → 두 시장 단계가 다 성공한 날만 돌고 아니면 통째로 미룸 · 또는 (잡, 시장) 신선도.
- **5 결정 3**: 예약은 단계만 지킴 — DART 어댑터 22곳이 전역 `hasDailyQuota()`만 봄 · 무거운 잡 국내 실제 콜 기록 없음 · 사슬 국내 대상 475(현 350 + 과거 125) × covering 2 = **950**(700 아님) · 실행 기록 note 비어 있음 → 사슬을 무거운 잡 앞에(3과 함께) · 실행 기록에 DART 실제 콜 수.
- **6 (작음)** 종료 게이트는 `ContextClosedEvent` 때 열린 실행에만 붙음 → 종료는 즉시 전파하고 뒤 단계 안 돈다. **7 (작음)** 결정 4: FR 응답에 CFS 행 있으면 FSR CFS 000 48/48 · OFS뿐이면 CFS 013 12/12 → FR 탐침의 fs_div로 0콜 판별(연결사 CFS 지연 시 OFS 거짓 소비 차단 · 지연 실재는 안 잼). **8 (작음)** 「초기 적재 규칙은 `DailyUpdateJob` 안」은 사실과 다름 — MUST NOT이 `PipelineScheduler:127` 그 메서드 위 → ADR에 사유(재시작마다 850종목)가 단계에 안 선다는 근거로. **9 (작음)** 순서 시험은 3과 같은 뿌리 · `heavyJobAndFreshnessComeAfterTheChain` 70분 근거 바뀜.

## 1부 v11 확인 반증 — 계획 밖 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `refute16/` · 0콜 · 운영 SELECT만 · 운영 JVM에 jcmd·신호 없음)
- **선다(쟀다)**: 10-07 14:15에 날짜가 된 무거운 잡 없음(FSR 14.6일 · G 16.9 · FR 3.7 · 기준 > 25) · 새 JVM 판정 가짜 홈 13갈래 설계대로(실제 19:29 rc 0 · 다음 10-07 13:30 · 실제 15406 로그 줄 꼴 = grep 패턴) · 합치기 모의 M1 Q 0 · M2 `reload-gate.md` 충돌 1을 progress 「행마다 바꾼 쪽」 규칙으로 풀어 72행 = 「main + (가)만 p1판」 · 합친 트리 `./gradlew build` rc 0 · 1,838/0/5 · `pinnedListsOnlyGrow` merge 둘째 부모 기준선으로 초록 · p1 단독 1,837/0/5 · 판 5 · companyfacts 재시도는 정상이면 추가 0콜 · 404·403·429 재요청 없음 · 이중 파싱 1,218파일 7.0초 · `ab4356ca` 본문이 GAP_FILL 빼고 코드와 맞음.
- **1 [회귀 · `ab4356ca`] GAP_FILL을 앱 소유로 묶었다(묶어서 판정)**: 리밸런싱 꼬리 GAP_FILL은 두 JVM 모두에서 돈다(내가 확인: 10-03 #703 MANUAL 리밸런싱 → #704~708 GAP_FILL · `BatchExecutionSweeper` 「어느 스윕도 닫지 않는다」) → 꼬리 도중 강제 종료 유령이 `crons-done`을 영구 1로 · 손 FR 「RUNNING = 0」도 막음 · 사본 재현 rc 1 → **술어 = `triggered_by <> 'MANUAL' and started_at >= 이 JVM 기동 시각`**(스위퍼와 같은 규칙 · 사본에서 유령만 rc 0 · 기동 뒤 GAP_FILL RUNNING rc 1).
- **2 [ADR-0173 0단계 4] 둘째 FR 관문 모집단이 실제 토요일을 빠뜨린다**: 최근 리밸런싱 6회 중 09-05 #278 SCHEDULER PARTIAL_SUCCESS · 10-03 #703 MANUAL(내가 확인) → 「SCHEDULER ∧ SUCCESS」 문은 막히는 쪽 실패 → 트리거 무관 SUCCESS/PARTIAL_SUCCESS ∧ `target_date` 확인 · 꼬리 끝은 SCHEDULER면 앱 끝 줄, MANUAL이면 bootRun 「부트스트랩 러너가 끝났다」 + 그 행 뒤 RUNNING 0 · 미국 명부 변화 0인 주는 「FR2 불요」.
- **3 [소비자를 안 셈] progress:56 둘째 FR 괄호가 아직 「꼬리가 끝난 뒤(`ops/crons-done.sh`)」**(v10 계획 밖 2가 이름으로 짚은 소비자 · `d023cd9f`가 같은 줄 0부 구절만 고침 — **내 누락**).
- **4 [소비자를 안 셈 · `ab4356ca`] 살아 있는 MANUAL이 문에서 사라졌다**: progress:93은 「손 쓰기 잡은 `crons-done` = 0일 때 시작」 — 옛 술어가 MANUAL도 세서 손 잡끼리의 겹침을 우연히 막았다 → 손 잡 시작 문 = `crons-done` 0 ∧ 살아 있는 bootRun 0.
- **5 [회귀 · `ab4356ca`] 스테이징이 종료 뒤라 스테이징 실패가 사이트를 내린 채 둔다**(가짜 홈 · `.env` 없음 재현: 종료 → `[stage] .env가 없다` → rc 1 · 재기동·안내 없음) → 종료 전 사전 검사 + 종료 뒤 실패 경로에 「앱이 내려간 채 — `ops/start.sh --app`」.
- **6 [순서 · 시험 DB]** 시험 프로파일엔 V900이 있어 V64가 future로 무시되지 않는다 — V64 적용 시험 DB에서 main + p0 빌드 758 실패(대조 V62 사본 1,770/0/5) → 0부 배포 빌드가 초록이 될 때까지 `stock_screener_test`에 V64를 안 들인다 · 1부를 코드째 되돌리는 배포는 시험 DB 처리를 되돌리기 메모에.
- **7 [순서] 첫 손 FR이 토 20:00을 넘기면 N=1 전제(0-2 `count(*) = 0`)가 영영 거짓**: 1부 배포 뒤 리밸런싱 꼬리(`InitialLoadJob`)가 보충을 탄다 → 1부 배포와 첫 FR을 같은 저녁에 묶거나 0-2를 109 대상 CIK로 좁힌다.
- **8 (작음)** `stop.sh --app --tunnel`이 rc 0으로 아무것도 안 내림(`ab4356ca` 앞은 용법 오류 2) · v11 `call()` javadoc이 `fetchFacts` 위에 떨어져 있음.

## 2부 v8 반증 판정 — 계획 밖 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `refute-p2v8/` · 0콜 · 운영 SELECT만)
- **선다(쟀다)**: 풀 1 큐가 수면 없을 때 사슬·14:18·14:20·15:30을 13:30 메서드 뒤 기한 순으로 · 단계 → 워치리스트는 수면에 안 뒤집힘 · 실키 창 15:45가 앱 DART 구간을 덮음(최악 15:29 — 다만 v8 근거는 사슬을 빼고 맞은 것) · `CronsDoneTableTest`가 14:15 줄 미삭제를 잡음 · 일일 갱신은 DART 0 · 화면 최신일 갱신은 최악 14:38로 늦을 뿐 · daily-update SCHEDULER 08-10 뒤 38회 FAILED·ABORTED 0 · ADR-0116 사용자 문장은 14:15를 안 정했다 · progress:146은 PLAN만 가리킴 · v5~v7이 버린 결정을 PLAN 본문은 안 되살림.
- **1 결정 6: 따라잡기 없는 보류는 알림을 늦추지 않고 잃는다**: 보류 항목은 평가 기록이 없어 나중의 같은 asOf 판정은 재판정이 아니다 · 알림 키 억제는 메모리 10분(`suppression-window: 10m` — 내가 확인) → 「같은 asOf 재판정 불가」 근거가 안 선다 · 엣지 알림(`WatchlistAlertRunner:302-304`) · 「저평가 가치주」 KOSPI 255거래일 재판정: 참 구간 53 중 하루짜리 19(창 첫날 잘림 제외) · 그중 8은 앞뒤 7일 안 다른 참 없음 → 그날 보류면 통째로 사라짐 · 005930 자신은 창 안 참 0 → **처방: 따라잡기 판정**(그 시장 미소비가 D까지 0이 되고 D의 파생층이 소비 뒤 다시 쓰이면 보류한 asOf를 날짜 순으로 판정 · 일일 갱신은 최신일 파생층만 쓰므로 조건을 글자로) · 끝 상태를 미소비 종류 전부로((a)는 즉시 보류 집합에서 뺌) · 사용자에게 다시 물을 해석은 없다고 봄(spec §7 「충족되면 통지」에 글자로 더 맞음) · 「늦을 수는 있어도」 문장 정정.
- **2 결정 2의 시장별 FR이 0부 시계와 안 맞물림**(`FinancialRefillJob:343-360` 한 잡이 두 시장 · 한 시장만 돌리면 p0 `scopeOf` NARROW → 이튿날 또 돎 / FULL이면 안 돈 시장 시계까지 감김 · 0부 수용 시험 `everyScheduledCallIsFullScope`와 충돌 · 첫 노출 10-30) → ① 두 시장 단계가 다 성공한 날만 ② 잡 이름을 시장별로 ③ (잡, 시장) 시계 — 어느 쪽이든 0부 FULL 단언을 표 전 칸으로.
- **3 「13:30~15:40 밖 문구 불변」 거짓**: 메서드 끝 뒤 사슬·유도·화해 약 36분 빠짐 · 단계 42분(17,253콜)과 G(8,050콜)는 18,000 안에 함께 못 듦 · 예산에 묶어 재면 G 날 메서드 끝 15:14 · 화해 끝 15:50(main 지금 15:31) → 알림 본문 4곳 · `FinancialRefillAlertTest:101` · data-pipeline.md:182 · SKILL:582 · ADR-0166:39가 G 날 거짓 → 문구를 `ops/crons-done.sh` = 0 술어로.
- **4 결정 8 소비자 목록은 하한**: `ops/crons-done.sh:38` · `CronsDoneTableTest`(`PipelineScheduler.class`만 훑음 → src/main 모든 `@Scheduled`로 성질) · `PipelineSchedulerTest:223` · `PipelineSchedulerChainTest`의 `runHeavyJobOfTheDay()` 직접 호출(main 3 · p0 수용 시험 :443-450) · `application.yaml:21` · progress:218·820·1533·1818 · reload-gate:56 · ADR-0116:26 · ADR-0166:20·79 · `PipelineScheduler` javadoc 207·385·448-455 · `:33·127` 초기 적재 규칙(글자가 이 메서드에 있다 — 사유로 답해 문장을 고친다) · `AdminOverviewView.vue:318` · PLAN v4 결정 14의 CLAUDE.md 제안문.
- **5 이번 세션 회귀: `reload-gate.md:56·65`가 버린 설계를 지금 처방으로 적는다**(`55f21828` · 「표지 = 소비한 공시 · 표지 없음 = 전 창 FR · 평일 14:15 단계」 · 「14:17 표지 술어」 — 내가 확인) → PLAN 포인터만.
- **6 첫 피크 순서**: 결정 3(예약 = 무거운 잡 우선)과 v7 결정 8 달력(단계 우선 + 38일째 강제)이 다른 규칙 전제 · 현 선택 규칙 모의 11-16 G · 11-17 FSR · 예약 우선이면 11-17 단계 17짝뿐 → 287짝이 11-17~19 사흘 · 국내 보류 사흘 · 불변성 2차 수집과 겹침 → 하나를 글자로 고르고 RF3에.
- **7 (작음)** 한 메서드에서 시각을 두 번 읽음(26일째 갈림 · 모의 평균 1.07일/년) → today·now를 메서드 머리에서 한 번. **8 (작음)** 수면 역전은 단계 → 워치리스트만 막힘(G 날 사슬 역전 35분 · 화해 55분 이상 수면 · main은 10분 이상) → 순서 시험은 「명목 순서」 시험으로 이름. **9 (작음)** 결정 2 표에 「갱신 예외 → 무거운 잡?」 빈칸 · 종료면 멈춤. **10 (작음)** 결정 6 머리의 「사용자 결정 + 내 판정」 — 사용자 결정은 위임 한 줄뿐, 나머지 전부 내 판정으로 갈라 적는다.

## 1부 v11 확인 반증 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `rebut16/` · 0콜 · 시험 DB `stock_screener_r16a_test`(유령 표지 행 95313·95314) · 코드 사본 `scratchpad/r16/{v10,v11,pm,mrl}`)
- **선다(쟀다)**: 재시도 갈래(v10·v11 같은 탐침) — 404·403·429·400은 셋 다 1콜 멈춤 · 503×3은 3콜 뒤 TRANSIENT · 503→200·반쪽 JSON·200 HTML·빈 본문·연결 리셋·고정 길이 중간 끊김은 2콜 뒤 OK(v10은 1콜 실패) · 섞이면 마지막 시도가 이김 · 재시도 중 한도 바닥 = RATE_LIMITED · 실패는 `DataSourceException` → `StockTaskExecutor` 종목 단위(코드로 읽음) · 성공 경로 바이트 동일(실물 companyfacts 61개 · 보충 없는 어댑터 · SHA-256·콜 수 61/61) · 판 5의 출력 해시 불변이 맞음(받기 길을 안 탐 · 깨진 JSON은 둘 다 MALFORMED) · 앱 JVM 트리거는 SCHEDULER·ADMIN·GAP_FILL뿐 · deploy 실패 갈래(빌드 중 커밋 → unknown · 종료 45초 초과 → rc 1 · 폴더 그대로 · 옛 JVM 생존) · 0-2는 운영·`sc_a` 모두 V64·표 없음이라 지금 멈추는 것이 맞음.
- **1 ADR-0173 0단계 4가 글자로 안 박혀 지난 명부를 통과시킨다**: 「그 시점 마지막 SCHEDULER 리밸런싱 행」으로 읽으면 지난 토요일 13:00 되감기 6번 중 3번(09-19·09-26·10-03) 지난 명부로 통과 · `target_date = 토요일 − 1`로 박으면 6/6 막음(`rebut16/adr0/gate4-asof.*`) · v10 처방(`target_date='2026-10-09'`)이 v11 글자에서 빠짐(**내 누락**) → SQL을 글자로 + `max(effective_from)` 같은 날 + 끝 줄은 그 행 `started_at` 뒤 · 손으로 돌린 주의 증거(MANUAL 행 + 꼬리 GAP_FILL 전부 SUCCESS + bootstrap 끝 줄). (계획 밖 2와 합친다: 트리거 무관 · PARTIAL_SUCCESS의 뜻을 재고 받을지 정한다.)
- **2 main `ab4356ca` GAP_FILL 과포함**(계획 밖 1과 같은 결함 · `MembershipGapFiller.loadMissingRawData:278`이 물려받은 트리거가 MANUAL이어도 GAP_FILL) → **main `676a58e6`로 최소 처방(이 JVM 기동 뒤 행만) 반영** · 뿌리(소유 JVM 칸)는 progress 「JVM을 건너는 쓰기 배타」 줄에 남은 틈으로 적음(기동 뒤 bootRun 꼬리 유령은 막히는 쪽).
- **3 같은 실행 재시도에 간격이 없고 `fetchFacts`가 `SecFilingClient.withRetry`를 복제해 이미 갈렸다**: 운영 리미터로 503 서버 상대 세 시도 간격 204·209ms(전체 414ms) · 운영 companyfacts 전송 실패 4건 = Premature EOF 2(즉시 재시도로 닫힘) · Connect timed out 1 · Network is down 1(09-15 02:10 · 끊김 길이는 안 잼) · 인터럽트에서 갈림(v11 `fetchFacts`는 acquire의 InterruptedException을 TRANSIENT로 받아 다시 acquire · `SecFilingClient`는 acquire가 try 밖 · 실제 TokenBucket에선 둘 다 0콜) → **재시도 도우미 하나로(acquire는 재시도 밖) · TRANSIENT에 지수 간격 또는 실행 끝 재시도**.
- **4 판 고정이 놓치는 변이(잠복 · 실물 0)**: 면제를 형 단위로 줘 면제 형의 새 멤버를 못 봄 — `isLinkbase` 본문을 면제된 `SecTickerCikCache.linkbaseStem`으로 옮긴 뒤(그 순간은 붉음 → 판 6) 도우미만 바꾸면 고정 시험 전부 초록인데 `pickInstance([abc-20180331.xml, abc-20180331_LAB.xml])`가 UnreadableFiling → OK · 범위 형을 범위 밖에서 구현하는 네 꼴(캐스트 람다 · 조건식 람다 · `Proxy.newProxyInstance` · 생성자 인자 람다)이 `scopeTypesAreSubtypedOnlyInScope` 통과 → 면제 형에서 범위 소스가 쓰는 멤버의 정규형을 소스 해시에 · 하위형 검사를 javac 속성 분석으로(또는 Spring 컨텍스트 `AopUtils.getTargetClass`).
- **5 (작음)** companyfacts 429 재시도 변이에 `datasource.us` 235개 전부 초록 → 429 → 1콜 RATE_LIMITED 시험. **6 (작음)** ADR 0단계 순서: stop 거절 뒤 기다리는 사이 바뀔 0-1·0-2를 다시 안 물음 · 0-1에 `worktree add`·`ln -s`가 있어 「읽기만」 이름이 틀림 → stop 성공 뒤 0-1 세 단언·`D` 같음·0-2를 한 번 더. **7 (작음)** 사후 대조 서명이 `financial_statement`만 — `financial_column_absence`(GrossProfit·UnavailabilityRule이 읽고 되돌리기도 되돌림)·보충 행 상태를 더한다(어긋날지는 안 잼). **8 (작음)** `call()` javadoc 떨어짐(`SecEdgarFinancialAdapter:160-173`) · 공백만 든 2xx는 재시도 안 하고 빈 본문은 재시도(둘 다 끝은 MALFORMED).

## 1부 v12(2026-10-06 밤 · 보류 브랜치 `df8e3005`·`a8d137cf` · 시험 1,846/0/5(clean build · `-PskipFrontend` · 전용 시험 DB) · 판 6) + main 운영 `676a58e6`
- **받기**: 공시 파일 · 색인 · 공시 목록 · companyfacts가 `SecFilingClient.fetch` 한 자리 · 시도 사이 2초 · 10초 · 다시 묻기를 다 쓴 콜이 연달아 셋이면 대기 건너뜀(응답 하나가 닫음) · 한도 · 리미터 인터럽트 · 4xx는 안 다시 묻음 · 공백 2xx도 다시 받음 · companyfacts 429 1콜 시험 · `call()` javadoc 정리.
- **판 고정**: 범위가 닿는 범위 밖 프로젝트 멤버의 선언을 폐포로 소스 해시에(지금 40항목 — `DataSourceException`·`Kind` 상수 · `DataSourceProperties.Source` 게터 · `ExternalApiClientFactory.create` · `RateLimiter` 셋 · `SecEdgarHeaders.of` · `SecTickerCikCache.padded`) · 빈이 범위 인터페이스를 받거나 내면 붉음(보충 운영 생성자는 원장을 구체형으로) · **대조군(사본 `r12ctl2`·`r12ctl3`)**: 반증 변이 a(옮김) 붉음 → b(판 7) 초록 → c(도우미만 바꿈) **붉음**(v11에선 초록) · 인터페이스 주입 · 람다 `@Bean` 둘 다 붉음.
- **ADR-0173**: 0단계 4 = 대상일 `2026-10-09`를 박은 SQL · 트리거 무관 · PARTIAL은 지수별로 가름 · 꼬리 끝 = 그 JVM 로그 「편입 결손을 다시 쟀다」(+ MANUAL은 부트스트랩 끝 줄) · 미국 명부가 안 움직인 주는 FR2 불요 · 내린 뒤 1·2를 다시 묻음 · `N`=1은 1부 배포 뒤 첫 토 20:00 전에(넘기면 재생을 비교 상대로) · 사후 대조 서명 v2(`f02d2738` — 재무 · 결손 표 · 보충 원장 · rs1d 사본에서 SELECT로 돌림) · 6의 RUNNING은 `id > E0`만 · 코드째 되돌리기의 시험 DB.
- **main 운영(`676a58e6`)**: 판정 = 이 JVM 기동 뒤 MANUAL 아닌 RUNNING · bootstrap 프로세스 참고 줄 · `stage.sh --check`를 종료 전에 · 종료 뒤 실패에 「앱이 내려간 채」 · 커밋 기록을 jar 교체 직전에 걷음 · `stop.sh --app --tunnel` 용법 오류 · progress:56·93 · reload-gate 버린 설계 두 줄 · 연습(가짜 홈 · 사본 `stock_screener_opsdrill_b`) 열세 갈래 + 행 둘 + 인자 다섯 + 배포 실패 셋.
- **남은 틈(기록 · 결함 줄)**: 앱 기동 뒤 bootRun 꼬리 GAP_FILL 유령은 판정이 막히는 쪽으로 남는다 — 뿌리는 소유 JVM 칸(JVM 쓰기 배타와 같은 설계 · progress 결함 줄).

# 2부 계획 v9 (2026-10-06 밤 · v8 두 렌즈 반영 · v8 결정 중 바뀐 것만 — 나머지는 v8·v7·v6·v5)
## 사용자 결정과 내 판정(갈라 적는다 · v8 계획 밖 10)
- **사용자 문장(결정 6 위임)**: 「claude.md 의 판단기준에 따라 선택해.」 — 사용자가 고른 것은 이 위임 한 줄뿐이다.
- **내 판정(전부)**: 시장 단위 보류 · 끝 상태 · 화면 표시 · 따라잡기 판정 · 판정 위치. v8 「사용자 결정과 내 판정」 절의 근거 문장 둘은 **거짓이었다**: 「보류는 영구가 되지 않는다」(미국 UNREADABLE·국내 (a)에 끝 상태가 없었다 · v8 계획 안 1a) · 「늦을 수는 있어도 낡은 값으로는 안 나간다」(따라잡기가 없으면 하루짜리 참 구간 알림이 늦는 게 아니라 사라진다 — 「저평가 가치주」 KOSPI 255거래일에서 참 구간 53 중 하루짜리 19 · 그중 8은 앞뒤 7일 안 다른 참 없음 · v8 계획 밖 1). 아래 6이 그 둘을 고친 판정이다 · 사용자에게 다시 물을 해석은 없다고 본다(spec §7 「충족되면 통지」에 글자로 더 맞다 — 두 반증 모두).

## 결정(v8에서 바뀐 것만)
1. **평일 사슬 전체를 13:30 `@Scheduled` 한 메서드의 호출 순서로 묶는다**(v8 계획 안 3 · 계획 밖 8 — 별도 크론으로 남긴 사슬·유도·화해는 수면이 끼면 13:30 메서드보다 먼저 돈다 · 실제 Spring 7.0.8 가상 시계 재현 · 문턱 G 날 사슬 35분 · 화해 55분): **머리에서 `today`·`now`를 한 번 정한다**(계획 밖 7) → ① 단계(국내 · 미국) → ② 입력 유도(미국 주식수 기저 · 거버넌스 사실 — 갱신 앞 · 계획 안 1e) → ③ 일일 갱신(원본 지표 · 최신일 파생층) → ④ 기업 행위 사슬(수집 → 계수 → 무효화 시 사슬 안 화해) → ⑤ 무거운 잡(결정 2·3) → ⑥ 입력 유도(다시 — 사슬의 새 APPLIED와 무거운 잡의 새 판 뒤 · 계획 안 3) → ⑦ 화해 ①② → ⑧ 워치리스트(따라잡기 포함 · 결정 6) → ⑨ 신선도 점검. **14:00·14:15·14:18·14:20·15:30 크론은 없어진다** · 토 20:00 리밸런싱만 따로 남는다 · 실패 격리는 단계마다(아래 2의 표) · 끝에 첫 예외를 던지고 나머지는 `addSuppressed` · **종료 신호(`BatchShutdownException`)는 그 자리에서 전파하고 뒤 단계를 안 돈다**(계획 안 6 · 계획 밖 9 — 종료 게이트는 `ContextClosedEvent` 때 열린 실행에만 붙어 그 뒤 시작한 잡은 종료 중에 쓴다).
   - 「일일 갱신에 초기 적재 분기를 섞지 않는다」(MUST NOT · `PipelineScheduler:127` 그 메서드 위 — v8의 「`DailyUpdateJob` 안을 말한다」는 사실과 다르다 · 계획 안 8): 규칙의 사유는 「재시작마다 850종목 초기 적재」 — 단계의 전 창 짝은 미소비 공시가 있는 종목만, DART 예산 안에서 돈다 · ADR에 사유로 답하고 그 MUST NOT 문장은 「일일 갱신 잡」으로 좁혀 고친다.
2. **단계 결과마다 무엇이 도는가**(v8 계획 안 4 · 계획 밖 2·9 — 시장별 FR은 잡 이름 단위 신선도와 0부 `scopeOf`에 안 맞물린다): **FR은 두 시장 단계가 다 성공한 날만 돈다 — 아니면 그날은 통째로 미룬다**(FR은 늘 두 시장 전량 · 0부의 FULL 단언 `everyScheduledCallIsFullScope`가 그대로 선다) · FSR은 국내 단계 성공일만 · G는 늘 · 갱신이 던져도 ④~⑨는 돈다(서로 입력이 아니다 · 운영 daily-update 08-10 뒤 38회 실패 0 — 기제만) · 미룬 날은 실행 기록에 사유와 함께 · 시험이 단계 결과(성공 · 목록 실패(시장별) · 예외 · 종료) × 갱신 예외 유무 표를 전부 주입.
3. **DART 예산 — 단계 우선 · 강제일만 무거운 잡 우선**(v8 계획 안 5 · 계획 밖 6 — v8 결정 3(예약 = 무거운 잡 몫)과 v7 결정 8(단계 우선 + 38일 강제)이 다른 규칙을 전제했다 · 예약 우선이면 11-17 FSR 날 단계가 17짝뿐이라 287짝이 사흘에 걸친다): 단계는 짝마다 `usedToday + need ≤ 18,000 − 사슬 몫 − (강제일이면 그 무거운 잡 몫)` · 사슬은 ④로 무거운 잡 앞이라 자기 몫을 실제로 먼저 쓴다 · 무거운 잡은 남은 예산이 자기 몫 이상일 때만(아니면 그날 미룬다 · 사유 기록) · **강제일 = 마지막 성공 뒤 38일**(v7 결정 8) · 사슬 몫 = 국내 창 편입 475 × covering 2 = **950**(700 아님 · 계획 안 5 · 내가 q03 확인: 국내 475 · 현 350) — 실제 콜 수를 실행 기록에 남기기 시작한 뒤에는 그 최근 최대 · **모든 DART 잡의 실행 기록 note에 그날 실제 DART 콜 수**(`usedToday` 차) · 시험: 「단계 + 사슬 + 무거운 잡 ≤ 18,000」은 모의 예산으로 순서 셋(평일 · 몰린 날 · 강제일)을 돈다 · 첫 피크 달력(계획 밖 s01b — 현 선택 규칙 모의: 11-16 G · 11-17 FSR · 11-26 FR)은 단계 우선이면 11-17 단계가 287짝을 다 받고 FSR이 그 뒤 날로 밀린다 — 수는 구현 뒤 모의로 다시 잰다(재지 않았다).
4. **탐침은 FR 응답의 `fs_div`로 라벨 성질을 0콜로 가른다**(v8 계획 안 7 — 1차 수집 60라벨: FR에 CFS 행이 있으면 FSR CFS 000 48/48 · OFS뿐이면 CFS 013 12/12): CFS 행이 있는 라벨은 CFS만 · OFS뿐인 라벨은 OFS만 묻는다(연결사의 CFS가 늦게 뜨는 순간 OFS로 거짓 소비하는 길이 막힌다 · 그 지연의 실재는 재지 않았다).
5. **(v8 결정 5 그대로 — 선다)**: FSR 전용 칸 9를 생성자 인자에서 유도(계획 안 · FR 전용 0).
6. **워치리스트 — 화해 뒤 맨 끝에서, 따라잡기와 함께**(내 판정 · v8 계획 안 1 · 계획 밖 1):
   - **판정 자리**: 일일 갱신 안(`DailyUpdateJob:404-409`)에서 빼 ⑧로 — 화해·유도가 그날 값을 다 고친 뒤 판정한다(⑦ 앞이면 무거운 잡·사슬이 무효화한 값으로 판정한다) · 알림은 13:5x에서 사슬 끝(≈14:30 · 무거운 잡 날은 더 늦게)으로 늦는다 · 값은 종가 기준이라 늦어져도 뜻은 같다.
   - **보류 술어(시장 M · 판정일 D)**: 「M의 단계가 D까지 확인했다(목록 OK · 예외 · 종료 없음) ∧ M 판정 모집단에 `published_at ≤ D`이고 끝 상태가 아닌 미소비 공시가 없다」가 아니면, M의 **재무 유래 조건**을 쓴 항목은 D를 보류한다 · `published_at`은 지표가 쓰는 것(국내 = 접수번호 앞 8자리 · `DartReportPeriods.publishedAt`) — 단계의 목록 창 끝은 실행일(오늘)이라 `rcept_dt`가 하루 늦은 공시(저장 list 475 중 4 · 현 종목 0)도 그날 목록에 든다(계획 안 1c).
   - **보류 = 기록 없음**: 보류 항목은 `recordEvaluation`을 안 한다(`last_satisfied`가 그대로) — 미표시·표지 null을 불충족으로 접으면 값이 돌아올 때 「새로 충족」이 다시 나간다(`WatchlistAlertRunner:121-125·213` · 계획 안 1b · 알림 항목 005930 조건 8 중 다섯이 재무).
   - **따라잡기**: 보류한 (항목, D)를 원장 표에 남기고(새 표 · 마이그레이션 · ADR), 보류 원인이 풀린 뒤 ⑧이 **날짜 순으로** 그 D들을 판정한 다음 오늘을 판정한다 — 알림은 그 D를 적어 나간다(「10-15 기준 충족」) · 따라잡기 판정은 ⑦ 뒤라 D의 파생층이 소비 뒤 다시 쓰인 값이다(일일 갱신은 최신일 파생층만 쓴다 — `DailyUpdateJob:384` · 그래서 자리가 ⑦ 뒤다) · 중복 방지 키 `watchlist:%d:%s`는 (항목, D)라 따라잡기와 안 겹친다.
   - **끝 상태(미소비 종류 전부 · 계획 안 1a · 계획 밖 1)**: 공시 하나가 N평일(=2 · 시도한 날 수) 미소비면 — 원인 무관(국내 (a)·(b)·(c)·(d) · 미국 보충 FAILED · UNREADABLE · 목록 실패 · 옛 판) — **그 종목의 재무 유래 지표를 「봉 ≥ 그 공시의 `published_at`」 범위에서 미표시**(null · 그 앞 날짜의 맞는 값은 그대로)로 두고 시장 보류 집합에서 뺀다 · 그 종목의 항목은 항목 단위로 보류(기록 없음 · 원장 표)하고, 공시가 소비되면(코드 고침 · FR) 따라잡기로 판정한다 · 결함 경보는 그대로(종목 · 공시 · 원인) · 국내 (a) 「FSR 미반영」은 그 라벨 값이 이미 null이라 **즉시** 시장 보류 집합에서 빼고 항목 단위로 · 시장 보류가 2평일을 넘으면 운영자 알림.
   - **재무 유래 = 성질로**(계획 안 1d): 「재무 행을 바꾸면 출력이 바뀌는 계산기」를 계산기 전수로 시험해 유도한다(입력 표 선언으로 유도하면 미국 주식수 넷 — `MARKET_CAP`·`SHARES_GROWTH_3Y`·`TURNOVER_RATIO`·`TURNOVER_RATIO_60` — 이 빠진다 · `FinancialMath.sharesOf`로 읽는다) + 재무 기간에서 유도되는 거버넌스 사실 종류(미국 `DPS_COMMON`·`CASH_DIVIDEND_TOTAL` — `AlpacaGovernanceFactMapper:117-160`)를 읽는 계산기 + 그 둘의 백분위 · 업종 상대.
7. **(v8 결정 7 그대로)** RF7은 채택 조건이 아니다 · 11-17 관찰.
8. **벽시계 창을 사건으로 바꾼다**(v8 계획 안 2 · 계획 밖 3·4): 「평일 13:30~15:40 밖」(손 쓰기 창)과 「13:20~15:45」(실키 창)는 v9 사슬의 끝(G 날 화해 끝 ≈15:50 · 계획 밖 m01)과 수면 밀림에 서지 않는다 → **둘 다 「`ops/crons-done.sh` = 0 ∧ bootstrap 프로세스 없음」으로** · 판정 표는 13:30 메서드 하나 + 토 20:00 · `CronsDoneTableTest`는 `PipelineScheduler.class`만 훑지 말고 src/main의 모든 `@Scheduled`를 성질로(계획 밖 4) · 순서 시험은 「명목 순서」가 아니라 **한 메서드 안의 호출 순서**를 묻는다(계획 밖 8 · 계획 안 9 — `heavyJobAndFreshnessComeAfterTheChain`의 70분 근거는 사라진다).
   - **고칠 소비자(하한 · 구현 첫 단계에서 `grep`으로 다시 센다)**: 알림 본문 넷(`FinancialRefillJob:327` · `InitialLoadJob:177` · `AdjustmentRebuildJob:211` · `CorporateActionJudgeJob:153`) · `FinancialRefillAlertTest:101` · `ops/crons-done.sh:38`과 `CronsDoneTableTest` · `PipelineSchedulerTest:223` · `PipelineSchedulerChainTest`의 `runHeavyJobOfTheDay()` 직접 호출(main 3 · 0부 수용 시험 :443-450) · `application.yaml:21` · data-pipeline.md:182(표 · 「14:15」 행) · batch-load SKILL:582 · progress:218·820·1533·1818 · reload-gate:56 · ADR-0116:23·26·230·241 · ADR-0166:20·26·39·79 · ADR-0113:241 · `PipelineScheduler` javadoc 33·127·207·385·448-455 · `AdminOverviewView.vue:318`(「14시에 닫습니다」) · PLAN v4 결정 14의 CLAUDE.md 제안문 · CLAUDE.md·progress의 「13:30·14:00·14:15·14:18·14:20·15:30」 나열.
9. **(v8 결정 2의 「종료 → 일일 갱신은 늘 돈다」 철회)** — 1의 종료 규칙으로.

## 드라이런 규칙 v9 더함(대조 전 커밋 · 실키는 `ops/crons-done.sh` = 0 ∧ bootstrap 프로세스 없음일 때 · 순차)
- RF9 순서: 실제 Spring 스케줄러 가상 시계로 v9 메서드 + 토 20:00을 수면 시나리오(앞 반증의 cronsim 6 + G 날 35·55분 문턱 꼴)에서 돌려 ①~⑨의 순서가 한 번도 안 뒤집히는가.
- RF10 워치리스트: 사본에서 공시 하나를 미소비로 두고 (가) 시장 보류 → 소비 → 따라잡기가 D의 알림을 내는가 (나) N평일 뒤 끝 상태 → 미표시 범위가 `published_at` 뒤뿐이고 항목 단위 보류로 옮는가 (다) 보류 동안 `last_satisfied`가 안 바뀌는가 · 대조군 = 지금 코드(보류 없음)에서 같은 사본이 낡은 값으로 판정하고 값 복귀 날 재알림하는가.
- RF11 재무 유래 성질 시험이 미국 주식수 넷 · 배당 둘을 잡는가(대조군 = 입력 표 선언 유도가 그것을 놓치는가).

## 1부 v12 확인 반증 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `rebut17/` · 0콜 · 운영·사본 SELECT만 · 시험 DB `stock_screener_r17a_test`)
- **선다(쟀다)**: 성공 경로 바이트 동일(companyfacts 61파일 v12 = v11 · 보충 켬 스윕 `SupplementReplay` 1,207종목 · 보고서 62,031 · extra 22,849줄 sha256 같음) · 단일 호스트 차단(셋 뒤 열림 · 응답 하나가 닫음) · 서명 v2 결정적(정렬 키 = 유일 색인 · 109 대상 fs/fca가 sc_a·rs1e·rs1c·운영 218줄 0차이 · 표 없는 DB는 psql rc 0에 빈 출력 → 전부 차이로 멈추는 쪽) · 10-10 target = 10-09(평일만 건너뜀 · 추석 금 09-25도 target) · 대기 인터럽트 = TRANSIENT · 보충 행 FAILED · v11 리미터 인터럽트와 같은 갈래(코드로 읽음) · N=1 토요일 경계의 서술 맞음(보충 쓰기 경로 = FR · 꼬리 `InitialLoadJob` 둘).
- **1 0단계 4가 같은 대상일의 이른 행을 고른다**: 09-05 토 13:00·19:59 되감기에서 #262(MANUAL · 금 09-04 09:48 · target 09-04)를 고르고 명부 조건도 통과 — 크론 #278은 그날 20:00(내가 확인 · 같은 target 행이 #156·157·170 꼴로도 있다) · 기제: `BootstrapRunner:278` `--as-of` 기본 = 오늘(금요일 손 실행 → target 금) · `AdminJobLauncher:101` `targetDateFor(오늘)`(토 낮 관리자 → target 금) → **`and started_at >= '2026-10-10 19:55:00+09'`**(SCHEDULER 6 중 5가 19:59:59.8~.96 시작이라 「20:00 이후」로 박지 않는다).
- **2 `Backoff` 상태 하나가 두 호스트를 섞는다**: 보충은 종목마다 `filings`(data.sec.gov)를 먼저 불러 그 성공이 차단을 닫는다 → Archives만 503인 날 종목마다 24~36초(대조군: Archives만 연달아면 셋 뒤 36초에서 멈춤) · 열려도 콜마다 3번 · 싱글턴 상태가 다음 실행까지 남는다 · 판 올림 재수신이면 CIK 311 × 최대 36초 ≈ 3.1시간(계산 · Archives 단독 장애 빈도는 안 잼) · 값은 안 바뀌고 시간만 → **호스트별 상태(data · files) · 열린 동안 1번만 · 시간이 지나면 닫힘 · 교차 시나리오 시험**.
- **3 판 고정 폐포가 인터페이스 디스패치를 못 본다(잠복 · 실물 0)**: `isLinkbase`를 면제 형의 `Predicate` 구현 클래스로 옮긴 뒤 그 `test` 본문만 바꾸면 전부 초록인데 `pickInstance([abc-20180331.xml, abc-20180331_LAB.xml])`가 UNREADABLE → OK · 폐포가 `new LinkbaseStem()`에서 머리와 생성자만 싣고 호출은 `Predicate#test`(JDK)로 풀린다 → **도달한 멤버가 만드는 프로젝트 클래스와, 도달한 프로젝트 추상·인터페이스 메서드의 범위 밖 구현은 파일 전체를 싣는다** · 빈 배선이 못 보는 주입 꼴(ObjectProvider · @Lookup · BPP · @Aspect · 상속 필드)은 범위 형에 닿는 운영 코드 0(grep) · 어댑터의 `this(...)` 사슬 람다는 두 검사 다 못 보지만 래퍼 입력(cik · facts · since)으로는 같은 판에 굳는 종료 상태를 못 만든다고 판단(코드로 읽음 · 변이로 안 잼).
- **4 (작음)** 「편입 결손을 다시 쟀다」는 적재를 건너뛴 날에도 찍힌다(다른 배치 도는 중 WARN · 자동 적재 상한 50 WARN — `MembershipGapFiller:164-176·241-255·278-300`) → 그 행 `finished_at` 뒤 두 WARN이 있으면 멈춘다 · 앱 로그는 10-03 21:11부터라 실제 발생은 못 잼. **5 (작음)** MANUAL 리밸런싱은 이 절차 밖 bootRun이라 「이 절차의 tee 로그」가 없다(#703 출력은 앱 로그에 없음 · 영구 멈춤 — 안전한 쪽) → 멈춘 뒤 갈 길을 글자로 · 지수 판별은 「부분 실패」 줄(개수만)이 아니라 「유니버스 갱신 실패. universe=…」·「…거부되었다. universe=…」 줄.

## 2부 v9 반증 판정 — 계획 밖 렌즈(2026-10-06 밤 · 「수정 후 진행」 · `refute-p2v9/` · 0콜 · 운영 SELECT만)
- **선다(쟀다)**: progress:56·146 · reload-gate:56이 PLAN만 가리킴 · 결정 6에서 사용자 문장과 내 판정이 갈라 적힘 · ADR-0166 §결정에 사용자 문장 없음(배포 승인만 — 14:18·14:20을 걷는 데 사용자 승인 불요) · ADR-0166 §버린 대안 「유도와 화해를 한 크론에」의 기각 사유(유도 실패가 화해를 막는다)는 v9의 단계마다 격리가 답함 · SCHEDULER daily-update 38회 FAILED 0 · 보류 브랜치 둘에 새 `@Scheduled` 없음 · v9가 v5~v8의 버린 결정을 안 되살림.
- **1 DART 예산이 다른 JVM을 모르고, 결정 8의 문은 시작만 묻는다**: `usedToday`는 앱 JVM 메모리뿐(`TokenBucketRateLimiter:32·65·94` — 내가 확인) · 재기동하면 0 · bootRun·실키 세션 미포함 · 실제 키 한도 20,000(`application.yaml:270`)과 여유 2,000 · 9월 뒤 평일 26일 중 15일에 MANUAL DART 가능 잡 · 09-21 국내 FSR #647+#648 실제 15,844콜 · 020 → RATE_LIMITED(`DartResponse:105`) · 끝 상태 「원인 무관 · 시도 N=2」면 손 세션 이틀로 종목이 미표시로 밀린다 · 「`crons-done` = 0 ∧ bootstrap 없음」은 오전 내내 참이고 「다음 명목 발화 전에 끝낸다」가 빠져 12:50 시작 48분 FR이 13:30과 겹치는 #305 꼴을 허용 → 단계 예산 = 18,000 − 오늘 모든 실행 기록 note의 DART 콜 합(트리거 무관) · MANUAL DART 잡이 RUNNING이면 단계를 미룸 · 020·TRANSIENT는 N에 안 셈 · 손 잡·실키 문 = 「오늘 13:30 메서드 끝 줄 뒤」 + 「다음 명목 발화 전에 끝낸다」 되살림.
- **2 ⑧이 판정 안 하고 지나가는 날이 보류 행 밖에 남고, 실패한 ③ 뒤에도 돈다**: 「갱신이 던져도 ④~⑨는 돈다(서로 입력이 아니다)」는 ⑧에 거짓(⑧은 ③이 쓴 D를 판정 · main은 갱신이 던지면 워치리스트를 안 돈다 — `DailyUpdateJob:409-417`) · ⑧의 시장별 D 출처 미정 · 따라잡기는 보류 행에만 — 앱이 종일 꺼진 날 2(08-17·09-16 · 그 D 08-14·09-15는 끝내 미판정) · 늦게 뜬 날 1(08-21 · ADMIN daily-update로 복구하며 워치리스트 판정 — v9가 그 경로를 잃는다 · `AdminJobLauncher:179` · `retryFailures` :226-242) · ⑦ 실패 뒤 ⑤ 무효화 종목은 null로 판정(기제) · **「중복 방지 키 `watchlist:%d:%s`는 (항목, D)」는 사실과 다르다 — (userId, 그 실행의 기준일 문자열)**(`WatchlistAlertRunner:271` — 내가 확인 · 억제 메모리 10분 · 같은 ⑧ 안 따라잡기와 오늘이 같은 문자열이면 뒤 알림이 사라짐 — 구성만) · spec:83 「일일 갱신 배치」·spec:79를 v9가 두 뜻으로 읽음 → 따라잡기를 시장별 「판정 완료 D」 워터마크로(보류 행은 그 위 항목 단위 예외 · 몰아 판정 날 수 상한) · ⑧의 시장별 D = 「③ 또는 ⑦이 그 시장 D를 썼다」(못 썼으면 그 시장을 asOf에서 빼 기록 없이 — `evaluateScreen`이 이미 함) · 보류 술어에 「그날 무효화됐는데 ⑦이 SUCCESS가 아닌 종목」 · 알림 키를 시장·D 단위로 + 시험 · 관리 화면·부분 재실행 경로를 정함 · 「일일 갱신 배치」를 한 정의로.
- **3 한 번의 발화가 하루 전부를 쥐고, 신선도 점검이 자기 감시 대상 안에 든다**: 13:30을 놓친 날(늦은 기동 · 도중 종료) 사슬 · 무거운 잡 · 유도 · 화해 · ⑧ · ⑨ 전부 빠짐(main은 14:00~15:30이 새 JVM에서 돈다) · 늦은 기동 1/39(08-21) · SCHEDULER ABORTED 0 · **ADR-0116 §결정의 사용자 문장 「평일 매일 14:00」 · 「노트북 종료 등으로 안 돌았을 때를 위해 알림까지 추가」를 PLAN이 한 번도 인용 안 함**(내가 확인: ADR-0116:30-32) · v5의 「단계가 안 돈 날 감시」도 ⑨ 안 · 신선도 예산은 전부 5일 이상(`JobFreshnessPolicy:82-110`)이라 ⑨는 사슬 밖이어도 순서 역전으로 판정이 안 틀림 → 같은 메서드를 여러 시각(예: 13:30 · 15:00 · 17:00)에 걸고 「오늘 사슬이 시작됐나(ABORTED면 아니다)」 문으로 한 번만 · ⑨(또는 「오늘 사슬 미시작」 경보)는 독립 크론 · **14:00이 사슬 넷째 단계로 바뀌는 것을 사용자 문장과 내 해석으로 갈라 적고 사용자에게 한 줄로 올린다.**
- **4 배포 순간**: `crons-done.sh`의 표는 저장소 커밋 것인데 판정 대상은 지금 도는 jar — 2부 배포 때 새 표(13:30 + 토)로 옛 jar(크론 여섯)를 판정해 14:13에 rc 0 → 옛 jar의 14:15·14:18·14:20·15:30이 그날 빠짐(코드로 읽음) → 표를 `app.jar.commit`의 커밋에서 읽거나 그 배포만 두 표의 합집합.
- **5 결정 2×3 빈칸**: 강제일에 단계가 실패하면 FR? · 한 시장 단계가 계속 실패하면 다른 시장 월 FR까지 무기한(40일 경고뿐) → 강제일 칸 · 또는 (잡, 시장) 시계(v8 계획 밖 2의 ③).
- **6 결정 8 소비자 목록은 하한이고 수가 틀림**: 시각이 박힌 알림·로그 본문 11곳 이상(`FinancialRefillJob:283·323` · `AdjustmentRebuildJob:209-210` · `CorporateActionJudgeJob:152` · `DailyUpdateJob:391` · `MembershipGapFiller:177-180·188·257` · `FinancialRefillAlertTest:86·91`) · 0부 수용 시험이 사라질 메서드 넷을 부름(g04) · `ops/apply-window/00-precheck.sh:8-14`(15:10 벽시계) · V59 COMMENT(마이그레이션 필요) · SKILL:57·58·61·63·153·401·525 · reload-gate:124-125 · CLAUDE.md:94 · ADR-0141:21 §결정 · `AdminJobLauncher:179` · `BatchExecutionContext:309` · `PipelineEndToEndTest:744` · 전체 209줄은 `refute-p2v9/g01·g03`.
- **7 (작음)** 화면 바뀌면 `last_satisfied` NULL인데 보류 행이 남음 → 같이 지움 · 끝 상태 원인 「목록 실패」에는 「공시 하나」가 없어 시장 보류 무기한 가능 · 미표시가 `PointInTimeScreener`에 닿음 → 「0행이면 바이트 불변」 단언을 끝 상태에도 · `reload-gate.md:65` 둘째 칸 「소비한 공시까지만 표지가 간다」(v4가 버린 어휘 — `676a58e6`은 넷째 칸만 고침 · **내 누락**) · v9 「두 반증 모두」는 PLAN 기록과 다름(계획 밖에만).

## 2부 v9 반증 판정 — 계획 안 렌즈(2026-10-06 밤 · 「수정 후 진행 — 결정 6은 고친 뒤 다시 받는다」 · `rebut-p2v9/` · DART 0 · SEC 0(`crons-done` 20:12:07 rc 0 확인 뒤 · 결정 4를 저장 응답 65라벨로 판정) · 운영 SELECT만)
- **선다(쟀다)**: 한 메서드 안 순서 · 토 20:00 겹침 0(실제 Spring 7.0.8 `CronTrigger` 가상 시계 + 스윕 1,264칸) · `CorporateActionJob`·`AdjustmentRebuildJob`은 재무를 안 읽어 ① 뒤로 옮겨도 입력 같음(`financial_statement`는 `ShareBasisDeriver`만) · 사슬 asOf = 오늘 · 사슬 안 화해 `dataAsOf` 같음 · 사슬 몫 950(국내 창 시작 2020-01-02 · covering 이식 2026~2027 매일 종목당 2콜) · G 날 화해 끝 ≈15:50(단계 111짝) · KNOWN_ON 경계(`publishedAt.isAfter(bar)`) = 「봉 ≥ published_at」 · 결정 4 반례 0/65(1차 60 + rebut-p2v6 저장 5 · OFS 전용 근거 7종목).
- **1 ⑧에 ⑦ 성공 문이 없다 · 「갱신이 던져도 ④~⑨는 돈다」는 ③→⑧에서 거짓(묶어서 판정)**: 무효화는 가격 지표까지 전부 지운다(`StoredIndicatorInvalidationPort` `DELETE … trade_date >= ?`) · ①이 FAILED면 ②를 안 돎 · ⑦이 던져도 ⑧은 돎 → 값 없음 → 불충족 → `recordEvaluation(false)` → 재알림(v9 1b의 기제를 재무 하나로만 묶었다) · ⑧의 D(`asOfByMarket`)는 `DailyUpdateJob:361-365`가 봉 적재 뒤 만든다 — 밖으로 빼면 출처가 없다 · 빈도: SCHEDULER ABORTED·FAILED 0 · PARTIAL 2 · 화해 SCHEDULER 2회(기제만) → ⑧의 D를 ③과 무관하게 · 「종목의 D에 `ADJ_CLOSE` 행 없음 = 일시 구멍 → 그 항목 기록 없음」 성질 · 결정 2 표에 「⑦ 실패 → ⑧」 칸.
- **2 따라잡기가 보류뿐이라 판정이 안 돈 D를 영영 안 본다**: 앱이 안 뜬 평일 42일 중 4 · 수면 밀림(월 18:00부터 15시간 → 화 발화가 수 04:30 · D=월 미판정) · ①~⑦ 사이 종료면 ⑧이 안 돎(노출 창 main ≈20분 → G 날 ≈140분) · 관리자 경로(`AdminJobLauncher:90` 일일 갱신 · `:262` retryFailures)가 판정을 잃음 → **시장별 판정 워터마크**(마지막 판정 D) · ⑧은 (워터마크, 오늘 D]를 날짜 순으로 판정하고 보류인 날에서 멈춘다 — 보류 · 종료 · ⑧ 실패 · 앱 꺼짐 · 수면 밀림을 한 규칙으로 · 같은 D 재판정이 원리적으로 없음 · 과거 D의 재료는 ③ 창 · ⑦① 원본층 · ⑦② 파생층이 씀(계획 밖 2와 같은 처방).
- **3 중복 키는 (항목, D)가 아니다**(계획 밖 2와 같음 · 내가 확인) · `AlertSuppressor` 메모리 `심각도|키` 10분 · `triggered()`는 `run()` 머리의 `findAlerting()` 스냅숏 → 한 `run`에 D 여럿이면 둘째 D가 첫째 기록을 못 봄 → D마다 두 시장을 한 호출로 · 키에 시장 · D마다 다시 읽음.
- **4 재무 유래는 (계산기 × 시장)이다(폴백 근거의 이식)**: `listed_shares` NULL 봉 — 국내 0 · 미국 전부(rebut q02 · 내가 09-01 뒤로 확인: KOSPI 0/4,772 · KOSDAQ 0/4,414 · US 25,928/25,928) · `FinancialMath.sharesOf:621-624`는 봉에 주식수가 있으면 재무를 안 읽음 → 주식수 넷은 미국에서만 재무 유래 · 계산기 단위면 국내 끝 상태 종목의 MARKET_CAP을 숨기고 MARKET_CAP 필수 프리셋에서 뺀다 → 픽스처를 시장마다 원자료 모양으로 · 러너 경로(위상 정렬 · `dependsOn`)로.
- **5 끝 상태 N=2 「원인 무관」이 반영 대기를 진단 전에 미표시로 보냄 · 전이 자리 미정**: v6 결정 3은 반영 대기를 진단 N(첫 값 5)에 태움 · 첫 관찰이 11-17 실전 · ⑧에서 전이하면 그날 ③·⑦은 낡은 값으로 백분위를 이미 씀 → 전이는 ①에서 · 미표시는 계산기 경로(안 낸 날짜 걷기 ADR-0130 · `IndicatorRunner:158-171`) · 반영 대기의 N은 실측 뒤(또는 진단 N과 맞춤) · 관찰을 11-17 전에(계획 밖 1의 「020·TRANSIENT는 N에 안 셈」과 함께).
- **6 수와 표**: (a) 「11-17 단계가 287짝을 다 받고」 거짓 — 18,000 − 950 = 17,050 · 짝당 81(73~108)이면 210(157~233)짝(v6 반증 「209/일이면 이틀」과 같음) · 비강제일 같은 날 무거운 잡 동반 시 단계 상한 G ≤ 111 · FR ≤ 54 · FSR ≤ 14 (b) 결정 2 표에 「이월(예산)」 칸 없음 — 「이월 = 성공」을 글자로(아니면 강제일 예약 → 단계 이월 → FR·FSR 막힘 · 자기 패배) (c) 「≤ 18,000」은 이 JVM 안 부등식 — 최근 60일 평일 오전 MANUAL DART 잡 79실행(국내 150·129·200 대상 포함) · 결정 8이 오전을 열어 v5 반증 8의 「아침 실키 금지」가 사라짐 → 일별 DART 사용량을 DB에 영속 · 손 DART 잡 문 = 「그날 메서드 끝 줄 있음」(계획 밖 1과 같은 처방).
- **7 `crons-done`은 시작 문 — 5분은 재기동 4초용 여유라 손 쓰기 잡에 옮기면 폴백 근거의 이식** · progress:93의 「다음 명목 발화 전에 끝낸다」가 v9에서 빠짐(계획 밖 1과 같음).
- **8 (작음)** 한 시장 단계 계속 실패 → 다른 시장 월 FR 그물도 멈춤(대가를 적는다) · 「목록 실패」는 공시별 끝 상태 원인이 아니다(시장 단위 확인 항은 따라잡기가 있으면 지연일 뿐) · 알림 시각 ≈14:30은 틀림(⑧은 ⑦ 뒤 · 무거운 잡 없는 날도 단계가 크면 ≈15:14) · 결정 4 「60라벨」 중 OFS는 4종목 × 3라벨.

# 2부 계획 v10 (2026-10-06 밤 · v9 두 렌즈 반영 · v9 결정 중 바뀐 것만 — 나머지는 v9·v8·v7·v6·v5)
## 사용자 결정 대기(하나 — 구현 전에 묻는다)
- **ADR-0116의 사용자 문장 「평일 매일 14:00」**(2026-09-01 · ADR-0116:30-32 — v9가 인용하지 않았다 · 계획 밖 3): v9의 한 메서드 사슬은 기업 행위 사슬을 「13:30 메서드의 넷째 단계」로 바꾼다(보통 13:5x~14:4x 시작 · 단계가 큰 날 더 늦게). 내 해석: 사용자가 닫은 것은 「평일 매일 자동으로」이고 14:00이라는 시각은 내가 올린 선택지의 값이었다 — 그러나 이것은 해석이라 묻는다. 선택지 둘(아래 1의 A·B) · 내 권장 A(순서가 호출 순서라 원리적으로 안 뒤집히고 문이 하나다).

## 결정(v9에서 바뀐 것만)
1. **사슬의 발화와 문**(계획 밖 3 · 계획 안 선다): 같은 메서드를 **13:30 · 15:00 · 17:00 세 시각에 걸고 「오늘 이 사슬이 시작됐나(실행 기록 · ABORTED는 시작 아님)」 문으로 한 번만 돈다** — 늦은 기동(08-21 꼴 1/39)과 도중 종료가 그날 안에 복구된다 · ⑨ 신선도 점검은 **독립 크론(15:30)으로 남긴다**(예산이 전부 5일 이상이라 순서 역전이 판정을 안 틀리게 한다 — `JobFreshnessPolicy:82-110` · 「오늘 사슬 미시작」 경보를 거기 더한다 — ADR-0116의 「노트북 종료 등으로 안 돌았을 때를 위해 알림」).
   - A(권장): 사슬 전체가 한 메서드(v9 ①~⑧). B: 13:30 메서드(①~③)와 14:00 메서드(④~⑧)를 두고 14:00 메서드는 「오늘 13:30 메서드 끝 줄 뒤」 문으로만 돈다(먼저 발화하면 다음 시각으로 미룸 · 14:00·15:00·17:00) — 시각 문장을 글자로 지키되 문이 둘이다.
2. **DART 예산은 JVM을 건넌다**(계획 안 6c · 계획 밖 1 — `usedToday`는 앱 JVM 메모리뿐 · 재기동 0 · bootRun·실키 미포함 · 키 한도 20,000): **모든 DART 잡의 실행 기록에 실제 콜 수를 남기고**(트리거 무관) 단계 예산 = `18,000 − 오늘 실행 기록 DART 콜 합 − 사슬 몫 − (강제일이면 무거운 잡 몫)` · **MANUAL DART 잡이 RUNNING이면 단계를 미룬다**(같은 메서드의 다음 시각) · 020 · TRANSIENT 시도일은 끝 상태의 N에 안 센다.
3. **손 쓰기 잡 · 실키의 문 = 「오늘 13:30 사슬의 끝 줄 뒤(평일) ∧ `ops/crons-done.sh` = 0 ∧ bootstrap 없음」 + 「그 출력의 다음 명목 발화 전에 끝낸다」**(계획 안 7 · 계획 밖 1 — v9가 끝 조건을 뺐고 「`crons-done` = 0」은 오전 내내 참이라 12:50 시작 48분 FR이 13:30과 겹치는 #305 꼴을 허용했다 · 5분 여유는 재기동 4초용이라 손 잡에 옮기면 폴백 근거의 이식) — 평일 오전 손 DART 잡은 그날 사슬 뒤로 간다(v5 반증 8의 「아침 실키 금지」를 되살린다).
4. **워치리스트 — 시장별 판정 워터마크**(두 렌즈 같은 처방): ⑧은 시장마다 (워터마크, 오늘 D]를 **날짜 순으로** 판정하고 **보류인 날에서 멈춘다**(그 시장 워터마크는 거기까지) — 보류 · 도중 종료 · ⑧ 실패 · 앱 꺼짐(42평일 중 4) · 수면 밀림 · 관리자 경로를 한 규칙으로 덮고 같은 D 재판정이 원리적으로 없다 · 과거 D의 재료는 ③ 창 · ⑦① 원본층 · ⑦② 파생층이 쓴다 · 보존 창 밖으로 밀려난 D는 판정 불가로 기록하고 운영자 알림(몰아 판정 상한은 두지 않는다 — 창 안이면 판정할 수 있고 알림은 spec §7대로다).
   - **⑧의 시장별 D = 그 시장의 원본층을 ③ 또는 ⑦이 쓴 가장 늦은 날**(지표 달력 · 계획 안 1 — `asOfByMarket`이 `DailyUpdateJob` 안에서 만들어지던 것을 밖으로) · 못 썼으면 그 시장은 이번에 판정하지 않는다(`evaluateScreen`이 asOf 없는 시장을 건너뛰는 장치).
   - **항목 단위 보류(기록 없음 · 워터마크 위의 예외 행)**: 끝 상태 종목 · **그날 무효화됐는데 ⑦이 SUCCESS가 아닌 종목**(계획 안 1 · 계획 밖 2) · 성질: 「그 종목의 D에 `ADJ_CLOSE` 행이 없으면 일시 구멍 → 그 항목 기록 없음」 · 화면이 바뀌면(`last_satisfied` NULL) 그 항목의 보류 행도 지운다.
   - **알림 키 = (사용자, 시장, D)** · D마다 `findAlerting()`을 다시 읽는다(계획 안 3 — 한 `run`에 D 여럿이면 둘째 D가 첫째 기록을 못 본다 · `AlertSuppressor` 10분 억제가 같은 문자열의 뒤 알림을 지운다) · 시험: 두 시장 같은 D · 따라잡기 여러 D.
   - **관리자 경로**: 관리자 일일 갱신(`AdminJobLauncher:90`)과 `retryFailures`(:262)는 끝에 같은 ⑧ 루틴을 부른다 — 워터마크가 중복을 막는다 · 관리 화면 설명(`:179`)을 고친다.
   - 「일일 갱신 배치」의 한 정의: spec:83의 「일일 갱신 배치」 = 평일 사슬(13:30 메서드) · spec:79 「초기 적재와 따로」에는 실질로 답한다(단계는 최초 가동 시 1회성 초기화가 아니라 그날 공시만 다룬다) — `PipelineScheduler:127` MUST NOT은 「일일 갱신 잡에 초기 적재 분기를 섞지 않는다」로 좁힌다.
5. **끝 상태 — 전이는 ①에서, 미표시는 계산기 경로로**(계획 안 5): ⑧에서 전이하면 그날 ③·⑦이 낡은 값으로 백분위를 이미 썼다 → 단계(①)가 N을 넘긴 공시를 끝 상태로 전이 · 미표시는 계산기가 그 종목 · 그 날짜 범위에서 값을 안 내고 안 낸 날짜 걷기(ADR-0130 · `IndicatorRunner:158-171`)가 옛 행을 지운다 · **N: 진단 N(첫 값 5)과 맞춘다**(v9의 2는 반영 대기를 진단 전에 미표시로 보냈다) · 반영 지연 관찰을 11-17 전에 놓는다(RF7을 관찰로 되살리되 10월 국내 정정·분기 공시로) · 「목록 실패」는 공시별 끝 상태 원인이 아니다 — 시장 단위 확인 항은 끝 상태가 없고(원장이 목록을 안 주는 동안 재무 항목 판정을 못 하는 것은 진짜 한계 — 보류 + 2평일 운영자 알림) 워터마크가 그 날을 따라잡는다 · `PointInTimeScreener`의 「0행이면 바이트 불변」 단언을 끝 상태 미표시에도.
6. **재무 유래 = (계산기 × 시장)**(계획 안 4 — 국내는 봉에 `listed_shares`가 있어 `sharesOf`가 재무를 안 읽는다 · 내가 09-01 뒤 봉으로 확인: KOSPI NULL 0/4,772 · KOSDAQ 0/4,414 · US 25,928/25,928): 성질 시험 픽스처를 시장마다 원자료 모양으로 · 러너 경로(위상 정렬 · `dependsOn`)로 돌린다 · 주식수 넷은 미국에서만 재무 유래.
7. **결정 2·3 표 고침**(계획 안 6a·b · 계획 밖 5): 11-17 단계 상한은 287이 아니라 **210(157~233)짝**(18,000 − 950 = 17,050 · 짝당 81(73~108)) — v9의 「287짝을 다 받고」는 거짓 · 비강제일 무거운 잡 동반 시 단계 상한 G ≤ 111 · FR ≤ 54 · FSR ≤ 14 · **「이월(예산) = 성공」을 글자로**(아니면 강제일 예약 → 단계 이월 → FR·FSR 막힘의 자기 패배) · **강제일(38일)에는 단계 결과와 무관하게 무거운 잡이 돈다**(진단 전 미소비 제외는 마지막 성공 단계의 집합으로) — 한 시장 단계가 계속 실패해도 다른 시장의 월 FR 그물이 38일을 넘겨 멈추지 않는다 · 표에 「⑦ 실패 → ⑧」(그 시장의 무효화 종목은 항목 보류) · 「종료」 칸.
8. **배포 순간의 판정 표**(계획 밖 4): 앱이 기동 때 자기 크론 표를 로그 한 줄로 찍고 `ops/crons-done.sh`는 그 JVM의 그 줄을 읽는다 — 그 줄이 없는 JVM(옛 jar)이면 저장소 표와 `app.jar.commit` 커밋의 표의 합집합으로 판정한다(2부 배포 때 새 표로 옛 jar를 판정해 그날 14:15~15:30이 빠지는 갈래).
9. **소비자 목록**(계획 밖 6): v9 결정 8의 목록 대신 `refute-p2v9/g01·g03`의 209줄을 구현 첫 단계의 재고로 쓴다(알림·로그 본문 11곳 이상 · 0부 수용 시험의 사라질 메서드 넷 · `ops/apply-window/00-precheck.sh:8-14` 15:10 벽시계 · V59 COMMENT(마이그레이션) · SKILL 7곳 · reload-gate:124-125 · CLAUDE.md:94 · ADR-0141:21 · `AdminJobLauncher:179` · `BatchExecutionContext:309` · `PipelineEndToEndTest:744`) — 그때 다시 `grep`으로 센다.
10. **(작음)** 알림 시각 서술: ⑧은 ⑦ 뒤라 보통 날도 단계가 크면 ≈15:14 · 결정 4의 1차 수집 OFS 표본은 4종목 × 3라벨(근거 종목 7) · v9 「두 반증 모두」는 계획 밖 판정에만 있었다(정정).

## 드라이런 규칙 v10 더함(대조 전 커밋)
- RF10을 워터마크로 고친다: 사본에서 (가) 시장 보류 이틀 → 풀림 → 워터마크가 두 날을 날짜 순으로 판정 (나) 앱 꺼진 날 하나를 끼운 사본에서 그 D가 다음 실행에 판정 (다) ⑦을 실패시킨 사본에서 무효화 종목 항목이 기록 없음 (라) 두 시장 같은 D 알림 둘 다 나감 · 대조군 = 지금 코드에서 같은 사본이 (나)의 D를 영영 안 보고 (다)에서 재알림.
- RF12 예산: 오전에 MANUAL DART 잡 기록(콜 수 note)을 넣은 사본에서 단계 예산이 그만큼 준다 · RUNNING MANUAL DART 잡이 있으면 단계가 다음 시각으로 미뤄진다.
