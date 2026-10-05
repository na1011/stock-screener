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
