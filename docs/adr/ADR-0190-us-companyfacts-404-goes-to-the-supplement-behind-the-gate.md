---
id: ADR-0190
status: 채택됨
date: 2026-10-24
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/CompanyFactsHistory.java"
---

# ADR-0190. 미국 companyfacts 404를 관문 뒤에서 공시 보충으로 잇는다

## 결정

관문 켬(`stockscreener.gate.us`)에서 `SecEdgarFinancialAdapter.fetchReports`가 companyfacts HTTP 404를 받았을 때의 처리다. 그 종목의 재무가 companyfacts 없이 공시 보충만으로 쌓였으면(`CompanyFactsHistory.supplementOnly`) 빈 `facts` 트리로 잇는다. 그 뒤는 「200인데 facts가 빔」과 같은 길이다. 공시 보충(ADR-0173)이 그 CIK의 정기 공시를 인스턴스로 채운다.

`supplementOnly`가 참이 되는 조건은 둘이다.
- 저장 재무 행이 전부 같은 CIK의 보충 행(OK · NO_XBRL)에서 왔다. 행이 없어도 참이다.
- 그 CIK에 COVERED 보충 행이 없다.

그 밖의 404와 관문 꺼짐은 지금처럼 `NOT_FOUND`다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/CF404-PLAN-1024.md`다. 두 렌즈 반증은 모두 「고쳐서 간다」였고, 지적은 재서 닫았다(같은 파일의 「반증 판정」 절).

## 왜

- 같은 성격인데 처리가 갈렸다.
  - 「200인데 facts가 빔」(AUGO · AERO)은 이미 보충으로 이어 IFRS 인스턴스에서 행을 낸다.
  - 404만 보충 전에 멈췄다.
  - 원장은 인스턴스로 재무를 준다. MAKO · SCZM(40-F) · TGE(20-F)는 IFRS 재무 전체를 실었다(2026-10-24 인스턴스).
- 실측(2026-10-24)
  - 운영 활성 미국 종목 중 재무 0행이고 SEC 매핑이 있는 것은 1,618종목 · CIK 1,221이다.
  - companyfacts 상태: 정상 962 · 404 141 · 1,000바이트 미만 118.
  - submissions에 2024 뒤 XBRL 정기 공시가 있는 것은 404 9 · 빈 facts 11이다. 나머지는 원장이 구조화된 재무를 안 준다 — 진짜 한계다(신규 등록 · 신탁 · 6-K만 · XBRL 없는 40-F · IBN은 최신 20-F가 XBRL 0).
- 지금 노출은 0칸이다. 404인 USD 발행사 중 가장 큰 것이 MAKO 약 $0.69B로, 순위 문턱(약 $5.2B) 밑이다.

## 규칙

- MUST: 잇는 것은 관문 켬 · HTTP 404 · `supplementOnly` 셋이 다 설 때뿐이다.
  - companyfacts가 한 번이라도 그 발행사의 공시를 실었으면 그때의 404는 일시 사건이거나 등록인 교체다.
  - 그때 빈 트리로 이으면 한 판이 셋을 덮는다(반증 안 렌즈 1).
    - 클래스 축 재료(`sec_share_class_input` 갈아 넣기)
    - 부재 표
    - 보충 판(창 안 전 공시가 이른 판으로 열림)
- MUST NOT: 신원 판정용 `companyFacts()`의 404 뜻을 바꾸지 않는다. `registrantAccessions`의 「빈 집합」 계약이 그것을 읽는다.
- MUST NOT: 관문 꺼짐에서 잇지 않는다. 꺼진 갈래에서도 보충 표 쓰기 · 실패 해소 · 시장 완료 판정 · 부재 표가 바뀐다 — 꺼진 출력이 `main`과 같아야 한다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| 조건 없이 관문 켬 404를 빈 트리로 | 위 §규칙 첫 줄 — 재무 행이 있는 종목의 404 한 판이 여러 표를 덮는다. 운영에서 그 꼴의 관측은 0/169종목(2026-08-30~10-23)이지만, 기준은 「언제 실행해도」다 |
| `NOT_FOUND` 전부(매핑 부재 포함) | 매핑 부재(GFAIW · VACI · WALDW)는 CIK가 없어 이을 대상이 없다 |
| 404를 「답함」으로 표지해 GAP_FILL 미완료 잡음을 없앤다 | ADR-0174가 「상장폐지 증거가 있을 때만」으로 정했고, 이 종목들은 활성 후보다. 잡음은 신선도 시계에 안 닿는다(GAP_FILL은 `NARROW`) |

## 결과

- 0콜 재생(`SecFilingSupplementCacheLiveTest` 74콜로 원자료 · 수집 도구 스위치 `SUPP_EMPTY_ON_404=on`으로 이 결정을 재현)
  - 재무 행(`SecEdgarFactsSweepTest`, 관문 켬): MAKO 1 · SCZM 2 · TGE 1행. NICM · BMM · BTQ · THH는 비USD라 통화 게이트에서 0이다.
  - 클래스 축(`SecShareClassSweepTest`): MAKO · SCZM `cur` 보임 · TGE `no-wavg` 숨김.
  - 꺼짐은 전부 0행이다(IFRS를 안 읽는다).
- 시험: `SecEdgarFinancialAdapterTest`에 세 갈래를 넣었다 — 켬 + 보충만 → 보충 호출 · 켬 + 이력 → `NOT_FOUND` · 꺼짐 → `NOT_FOUND`. 대조군(갈래 끔)이 붉다.
- 이력 판정 SQL을 운영 읽기 전용으로 돌렸다: MAKO 참 · AAPL 거짓.

## 검증

- `./gradlew test --tests '*SecEdgarFinancialAdapterTest'`
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 뒤 경로다.
