# (가) companyfacts가 빠뜨린 공시를 그 공시의 XBRL 인스턴스로 받는다 · 계획 v1 (2026-10-06 00시 40분)

실측은 `MEASURE.md`. 크기(B v6·v7 계획 밖 반증): 최신일 FUNDAMENTAL 4,035칸(58코드 전부 · 상한) · 법정 기한 뒤 누적 136,638칸 · 마감 10-30 정기 FR(놓치면 약 11-26까지 약 76,700칸 더 · 상한).

## 결정 v1
1. **`SecEdgarFacts`의 「공시 보충」** — companyfacts를 받은 뒤 같은 CIK의 submissions(`recent` + 적재 창이 거기서 넘치면 `files` 쪽)에서 적재 창 안의 정기공시(`PERIODIC_FORMS`)를 고르고, **companyfacts에 그 접수번호의 팩트가 하나도 없는 공시만** 묶음 색인(`Archives/edgar/data/{cik}/{acc}/index.json`)에서 인스턴스(`*_htm.xml` · 없으면 `_cal/_def/_lab/_pre`·`FilingSummary` 아닌 `.xml`)를 받아 **축 없는 us-gaap·dei 팩트**를 companyfacts 노드 모양(accn · start · end · val · filed = submissions `filingDate` · form = submissions `form` · fy·fp = 인스턴스 dei · unit = measure 이름 · 분자/분모는 `A/B`)으로 바꿔 **같은 JSON 트리에 합친 뒤** 기존 해석을 그대로 탄다(매핑·라벨·판 조립 무변경).
   - 같은 (개념 · 기간 · 단위)가 인스턴스에 여럿이면 `decimals`가 가장 정밀한 것.
   - companyfacts에 있는 공시는 **안 건드린다** — 섞일 옛 행이 0이다.
2. **설정 뒤에 두지 않는다(에이전트 판단 · ADR-0173에 근거)** — CLAUDE.md의 「값을 바꾸는 어댑터 코드는 꺼진 설정 뒤」가 막는 것은 같은 원자료를 새 규칙으로 다시 읽어 옛·새 규칙이 섞이는 것(`.claude/rules/data-pipeline.md:71` · `reload-gate.md:108`)인데, 여기서 바뀌는 행은 지금 **없는** 공시의 행뿐이고 있는 공시의 해석은 바이트까지 같다(AAPL 283/283) — 새 공시가 들어오는 것과 같은 데이터 변경(ADR-0166). 그 판단을 수용 기준(아래 RG0)이 받친다.
3. **콜**: FR 종목마다 submissions 1(+ 오래된 쪽 페이지) + 빠진 공시마다 색인 1 + 인스턴스 1. 미국 FR 1,000 → +약 1,000콜(5/초 · +약 3.3분) + 결손 약 80 × 2.
4. **실패 계약**: 색인·인스턴스 404 → 그 공시만 건너뛰고 WARN(그 종목 실패 아님 — companyfacts 몫은 그대로 들어간다) · 5xx·전송 실패 → 그 종목 실패(TRANSIENT · 다음 FR 재시도) · 인스턴스 파싱 실패 → 그 종목 MALFORMED(조용히 빠지지 않게).
5. **적용**: 배포 뒤 **두 시장 전량 FR 한 번**(손 · 평일 13:30~15:40 밖 · 사용자 확인) — 좁은 손 FR은 [A56]으로 무거운 잡 시계를 민다(SCHEDULER 성공 0 · 폴백이 MANUAL을 셈) · 전량은 정기 FR과 같은 일이라 시계가 정당하게 돈다 · 그 뒤 화해(14:20 또는 손). 안 하면 10-30 정기 FR이 받는다(앱이 그날 떠 있어야).
6. **감시(DB 펜스)**: 「현 편입 미국 종목 중 최신 기말이 135일보다 오래된 것」(분기 공시 기한 45일 + 분기 90일) — 예외 갈래(20-F 연간 공시 · 상장 1년 미만)는 구성원을 열어 정한다(재지 않음 — 계획 v2 전에 잰다).
7. **소비자**: `SecEdgarFinancialAdapter` · 스윕 `SecEdgarFactsSweepTest`(캐시가 companyfacts뿐이라 보충 경로를 안 탐 — 스윕에 인스턴스 캐시를 더할지) · `.factcache` · `docs/api-spec/sec-edgar-financial.md` · `[S]` 탐색 브랜치의 판 조립(같은 트리를 읽음).

## 드라이런 규칙 v1(대조 전 커밋)
- **RG0(0콜 · 동일성)**: 저장한 AAPL·MSFT·JPM의 companyfacts에서 최근 정기공시 하나씩을 **지운** 트리 + 그 공시의 인스턴스로 보충한 트리 → 어댑터 출력(`FinancialReport` 목록)이 원래 트리의 출력과 **같다**(필드 전부). 원래 결함 재현 대조군: 지운 트리(보충 없음)의 출력은 그 분기가 없다.
- **RG1(사본 · FR)**: 사본에서 78 + SPGI에 FR(새 코드) → 6월 분기 행이 생긴다(종목마다 기말 2026-06/07 · 공시일 = submissions `filingDate`) · 대조군 AAPL·MSFT·JPM은 신규·정정·채움·교정 0.
- **RG2**: 화해 뒤 바뀐 칸 수(FUNDAMENTAL · 최신일 · 창 전체) — 상한 4,035/일 대비 실측.
- **RG3**: 실패 갈래 단위 시험(색인 404 · 인스턴스 404 · 5xx · 파싱 실패 · 축 있는 팩트 제외 · 회사 택소노미 제외 · 정밀도 고르기 · `filed`·`form`은 submissions에서).

## v1 반증 판정 — 계획 안 렌즈(2026-10-06 01시 · 「수정 후 진행」 · `rebut1/`)
- 선다(쟀다): AAPL 양방향 — companyfacts 283노드 전부 인스턴스에 있음(cf−inst 0) · 인스턴스에만 `aapl:` 13 · ABT에 `decimals` 규칙 적용 시 겹치는 기간 147/147 같음 · companyfacts `filed`·`form` = submissions 2,944/2,944 accn.
1. **[오류 4] 「404면 그 공시만 건너뜀」 + `complete=true`면 판이 지워진다** — `SecEdgarFinancialAdapter:136`이 판 이력 전부를 냈다고 선언 → `FinancialWriter:283-350`이 낸 라벨마다 `published_at NOT IN keep` 행을 DELETE · 이른 판을 다시 넣는 길(`:184-189`)은 손 실행만 · 실례 BE(같은 분기 10-Q `-050247` + 10-Q/A `-050325` 14.8MB 둘 다 빠짐) → **보충이 하나라도 빠지면 그 종목 실패(또는 `complete=false`)** · RG3 「BE /A 404 주입해도 삭제 0」.
2. **술어 「팩트가 하나도 없는 공시」가 진짜 결손을 놓치고 엉뚱한 것을 잡는다** — 놓침: GEHC 10-K `0001932393-23-000025`(inline 5.1MB)는 companyfacts에 dei 2 · us-gaap 0(DB에도 GEHC 2022/FY 없음) · KO 10-Q/A 2024-05-30 · DUK 10-Q/A 2020-06-02 dei 1씩 · `SecEdgarFacts:558-561` 기록의 ORKA·DSGX · 잘못 잡음: 83 CIK `recent` 2018 뒤 정기공시 2,601 중 팩트 0 128(XBRL 81 · 비XBRL 47 = NXPI 6-K 42 · WSE 6-K 4 · FE 10-K/A 1 · 2015부터면 183) → **술어 = `isXBRL == 1` ∧ 그 accn의 us-gaap 팩트 0** · `isXBRLNumeric`은 옛 공시에서 null(SPGI 2025 10-K) · 「XBRL인데 인스턴스 없음」은 별도 실패.
3. **콜 산술이 틀림** — FR 기본 8년(`FinancialRefillJob:50`) · 2018부터면 83 중 34 CIK가 추가 페이지 · 합계 85장 · JPM 한 종목이 FR마다 52장(2015부터 59) · 「5xx면 그 종목 TRANSIENT」면 결손 없는 JPM도 50여 콜 중 하나만 실패해도 재무 전체를 못 씀(지금은 1콜) → 수를 싣고 · 목록 소스 대안 분기별 EDGAR full-index `xbrl.idx`(지난 분기는 안 바뀜 → 캐시).
4. **[오류 2] 「옛 행 0」을 보충 0건인 종목으로 잼** — 보충되는 79종목에서 옛 라벨이 움직일 수 있는 다섯 경로(코드로 읽음 · 재지 않음): 비교표가 보고자가 됨(`:812-905`) · 전역 플래그 `MINORITY_INTEREST`·`DISCONTINUED`의 isEmpty(`:888-890`) · `lastNoncontrollingIncome`(`:803`) · `chooseTags` 2순위(`:926-930`) · Q4 유도 판 → **보충을 반영하는 스윕(인스턴스 + submissions 캐시)으로 79종목과 대조군의 전/후를 뜨고 기존 라벨에서 바뀐 칸 전수** — 해야 하는 일.
5. **`decimals` 규칙을 대조군이 안 태움** — ABT 인스턴스 안에 같은 기간 다른 값 중복(Goodwill 2025-12-31: -6 정밀도 24,035,000,000 · -8 24,000,000,000.0) — MEASURE.md의 「공시마다 정밀도가 다르다」는 틀린 설명(같은 공시 안 중복) · AAPL 중복 키 53 전부 값 같음 · companyfacts는 211만 노드에서 (개념·단위·accn·기간)당 하나 — 무엇을 고르는지 재지 않음 → companyfacts에 이미 있고 다른 값 중복을 가진 공시를 대조군으로 · 같은 정밀도 다른 값 규칙 · `compareTo`.
6. RG0 미탐침 갈래: 10-K(CLX FY2026 · SPGI FY2024 — 회계연도 창 · Q4 유도) · 비인라인 `.xml`(색인 둘 다 inline) · GEHC 부분 결손 · 파서 고칠 것 셋: 택소노미를 접두어가 아니라 **네임스페이스 URI**로 · `unitRef` 없는 dei(DocumentFiscalYearFocus · CIK · 우편번호)를 수로 읽지 않음 · 컨텍스트 식별자가 그 CIK인지 · fy/fp는 읽히지만 쓰는 곳 없음(`labelOf:698`) — 그 대조엔 하중 없음.
7. [가설] 결손 원인 = us-gaap/2026 택소노미면 3분기 공시로 커진다 · 감시 펜스 6은 HOLE 형태를 못 봄 — DB FY 결번 10종목 12회계연도(AOS·CHRD·CIFR·DSGX·ELF·EXE·ORKA·SPGI·VAL·WULF) · GEHC처럼 앞쪽이 빈 것은 셈에도 안 듦.
- **가설 7 실측(내가 · SEC 14콜)**: 빠진 6월 공시 ABT·KO·NEE·DUK·CLX(10-K) 전부 `us-gaap/2026` · 들어 있는 AAPL·MSFT(10-K) `us-gaap/2025` — **그런데 JPM 6월 10-Q(`0001628280-26-054343`)는 `us-gaap/2026`인데 companyfacts에 있다** → 택소노미 연도만으로는 안 갈린다(가설 반증 · 진짜 원인은 재지 않음 — 3분기에 커질지는 미리 못 말하고 10월 공시를 감시로 잰다) · `xbrl.idx`는 303(리다이렉트 — 탐침이 안 따라감 · 재지 않음).
