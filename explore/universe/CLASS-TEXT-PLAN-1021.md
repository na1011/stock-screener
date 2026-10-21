# 클래스 축 — 표지 글의 NBSP와 닫힌 행의 비운 표지 (최종 계획 v1 · 2026-10-21)

관문 뒤 경로(`stockscreener.gate.us` · V70)만 바꾼다. 운영 값은 안 바뀐다 — 운영에 `sec_filing_class_fact`가 없고, 아래 두 읽기 경로
(`SecListingFormClient.lastCoverBefore` → `UsPastRanking` · `SecRegistrantLookup`)도 관문 · 손 잡(`us-delisted-intake` · `universe-restore`)에서만 돈다.

## 0. 잰 것 (★ = 내가 재료 · DB · 코드에서 직접)

- ★ **발단**: 3번 항목 「켠 클래스 축이 닫힌 행을 덮는가(LBRDA · LBRDK)」를 코드로 읽었다.
  - 덮는다: 닫힌 행은 `is_active = true`다(운영 6행 전부). `ShareClassStore.inputs` · `lines`가 `s.is_active`로 거르므로 닫힌 행을 낸다. 재료 받기는 FR 모집단(`REFILL_WINDOW`)을 따른다(R-D4b §10).
  - **그런데 판정에 넘기는 기호가 비운 표지 그대로다**: `ShareClassDeriver:85`가 `input.ticker()`(「WKEY~2026-10-04」)를 `IssuerTimeline.judge`에 준다. `DenominatorJudge.nk`가 「WKEY20261004」로 만들어 표지의 어떤 기호와도 안 맞는다. `SecurityRef.currentSymbol()`이 이미 있는 되찾기를 이 경로만 안 쓴다.
  - LBRDA · LBRDK는 클래스 주당이익이 같아 「B-equal-ok」(합)로 가므로 기호가 판정을 안 바꾼다(코드 `DenominatorJudge:268`). 기호가 닿는 것은 주당이익이 다른 다중 클래스(B-sum · B-member · P-ab 비동일)뿐이다.
- ★ **B-unmapped 1,491행(10-16 스윕 · 판 1)을 원인으로 갈랐다**(`fact/` 표지 대조): 기호가 표지에 없음 490 · 유닛·워런트·권리 줄 475 · 기타 388 · **제목 NBSP 93** · 표지에 기호 없음 45.
- ★ **NBSP**: 저장 표지 12,529건 중 1,042건(182 CIK)의 `Security12bTitle`에 U+00A0이 든다(「Class A Common Stock」). 비ASCII 문자는 그것과 대시 · 통화 기호 · 따옴표 · LTR 표시(4 — 유닛·워런트 줄) 뿐이다.
  - `DenominatorJudge.TITLE_CLASS`(`Class\s+([A-Z])`)의 `\s`가 U+00A0을 안 받는다(자바 기본). 그래서 「Class A」를 못 읽고, `COMMON` 갈래로 가 `CommonStockMember`를 찾다 실패한다 → B-unmapped → 숨김.
  - 같은 꼴이 상장 쪽 `ClassLines.CLASS_LETTER` · `COMMON`(`ordinary\s+shares` 등) · `NOT_COMMON`(`due\s+\d{4}`)에도 있다. 저장 표지 NBSP 제목 1,506에 자바 `\s`로 대 보니 글자가 갈리는 것 1,389 · 보통주 / 비보통주 판정이 갈리는 것 0.
  - 공시 HTML 경로(`ListingFormReader:173`)는 이미 U+00A0을 공백으로 바꾼다. XBRL 표지 경로만 안 바꾼다.
- ★ **판정 층 대조 (0콜 · 임시 하네스 · 10-16 재료 6,482줄 · 187,497행 · 판 무시)**:
  - 정규화 없음: 숨김 5,887(ADR-0179 §결과 판 2 수와 같다 — 대조군).
  - 표지 글 U+00A0 → 공백: **33행이 바뀐다.** 숨김 → 보임 24(전부 ERIE · P-ab/cover) · 숨김 → 숨김(사유만) 9(SBXD 4 · SPKL 2 · STZ 2 · YICC 1). 주식수 · 분모가 바뀐 것 26.
  - 여기에 비운 표지 → 기호: **1행이 더 바뀐다**(WKEY~2026-10-04 · B-unmapped → B-FAIL/title/원장 · 숨김 그대로). 재료에 닫힌 행이 그것 하나다.
- ★ **ERIE의 무게**: 운영 `financial_statement` 37행의 `shares_outstanding`이 전부 NULL(꺼진 판독기가 다중 클래스 표지를 안 더함)이고 `US_TOP1000` 편입이 없다(`US_LISTED`만). 스윕 `rank.tsv`도 꺼짐 · 켬 둘 다 순위가 없다(상태 CONSECUTIVE). 고치면 켬에서 보임 24 — 시총은 재지 않았다(Class A 4,619만 주 × 종가).
- ★ **기호가 표지에 없음 490행(167종목)** — 거의 전부 SPAC 합병 전 공시(옛 SPAC 기호)다. 2025-07 이후 공시로 좁히면 47행 · 26종목이고 꺼짐 순위가 가장 높은 것이 2,118위(ANGX)다. 행의 옛 기호 이력이 `stock_symbol_era`에 38행뿐이라 지금은 못 잇는다. **이번 계획 밖**(아래 4절에 세 답).

## 1. 고친다

1. **`ClassFactReader`가 표지 글을 읽을 때 정규화한다** — 두 모드(COVER · LISTING_COVER) 다. `\p{Zs}` → 공백 · `\p{Cf}` → 지움. 기간 사실(수)에는 안 건다.
   - 자리를 판독기로 고르는 이유: 판정기 · 상장 `ClassLines` · `ListingCover` 셋이 같은 글을 읽는다. 정규식마다 고치면 다음 정규식이 또 빠진다. HTML 경로가 이미 읽는 자리에서 바꾼다(`ListingFormReader:173`).
   - `READER_VERSION = 3`. 저장 행의 글이 바뀌므로 옛 판 행은 없는 것으로 친다(`okFacts`). 운영 표가 없고, 스윕 재료 `fact/`는 판 2로도 어차피 다시 받아야 했다(`reload-gate.md`).
   - `IssuerTimeline.ENGINE_VERSION`은 안 올린다. 판정 규칙이 안 바뀌고 입력 글만 바뀐다. 판독기 판이 오르면 재료를 다시 받고 유도가 매 평일 판정을 통째로 다시 쓴다(`replaceVerdicts`).
2. **판정에 넘기는 기호를 비운 표지에서 되찾는다** — `ShareClassStore.inputs`가 `split_part(s.ticker, '~', 1)`로 기호를 낸다(`StockReader.DISPLAY_TICKER`와 같은 꼴). 시험용 `ShareClassFileStore.inputs`도 같이.
   - 닫힌 행의 공시는 그 CIK 것이고 시대 안 표지는 그 기호를 싣는다 — 같은 기호를 뒤에 받은 다른 실체는 CIK가 달라 안 섞인다.

## 2. 소비자 (grep · src · test · docs · ops)

- `ClassFactReader.read` → `ShareClassCollector.fetch`(켠 FR) · `readListingCover` → `SecListingFormClient.lastCoverBefore` → `UsPastRanking`(클래스 글자) · `RegistrantFinder`/`SecRegistrantLookup`(보통주 여부).
- `READER_VERSION` → `ShareClassStore.okFacts` · `saveFact` · `ShareClassFileStore`(`okFacts` · `staleFactRows` · `factStatusCounts`) · `UsCandidateRankSweepLiveTest`(옛 판 행이면 멈춤) · 문서 `reload-gate.md` · ADR-0179.
- `ShareClassStore.Input.ticker` → `ShareClassDeriver.derive`(판정 · 실패 로그) 뿐.

## 3. 시험 · 드라이런

- `ClassFactReaderTest`: U+00A0 · U+200E가 든 표지 제목이 공백 · 지움으로 읽히고, 그 사실로 `DenominatorJudge.listedMember`가 Class A 구성원을 잇는다(ERIE 꼴 — 표지 축 없는 기호 + 「Class A common stock」 + 클래스 A·B 주당이익). 옛 코드에서 붉다.
- `ShareClassAxisReadTest`: 닫힌 행(「CLSV~2026-01-31」)의 `inputs`가 기호 「CLSV」를 낸다. 옛 코드에서 붉다.
- 판정 층 대조는 위 0절(재료 그대로 · 정규화를 하네스에서 흉내). 실제 판독기 판 3의 글이 하네스 흉내와 같은지는 `fact/` 다시 받기(관문 2단계) 전에는 **재지 않는다** — 관문 2단계 스윕이 그 수를 낸다.
- `./gradlew build` 한 번.

## 4. 기록

- ADR-0179 §결과에 한 덩어리(넷: 물은 것 · 대조군 · 모집단 · 결과) · `reload-gate.md`의 판 문장(2 → 3) · `progress.md` 3번 줄의 LBRDA 항목을 닫고 결과 한 줄.
- **기호 이력 490행은 세 답으로**: 무엇이 읽나(관문 켬의 순위 · 관측 평면 복원의 클래스 판정) · 무엇이 틀리나(옛 기호 시절 공시가 숨김) · 지금 닿나(2025-07 이후 47행 · 26종목 · 최고 꺼짐 순위 2,118 — 1,000위 안 0). 닿지 않으므로 progress에 줄을 안 늘린다.

## 5. 반증 판정 (v1 · 두 렌즈 · 둘 다 「고쳐서 간다」) — 내가 재서 닫은 것

| 지적 | 렌즈 | 내가 잰 것 · 고침 |
|---|---|---|
| `\p{Cf}` 지움이 너비 0 구분자(U+200B · U+2060 · U+FEFF)를 지워 낱말을 붙인다(「Common​Stock」 → `\bcommon\b` 깨짐) | 안 | 규칙을 고친다: `\p{Zs}` · U+200B · U+2060 · U+FEFF → 공백, 지우는 것은 방향 표시(U+200E · U+200F · U+202A–202E · U+2066–2069)와 U+00AD뿐, 그 뒤 `strip()`(끝 NBSP 제목 5) |
| 「운영 값은 안 바뀐다」 — `sec-cik-bind`(`SecCikBindJob:99-104` → `SecRegistrantLookup` → `readListingCover`)와 `us-delisted-intake`는 관문 설정 없는 손 잡 | 안 | 문장을 고친다: **정기 잡의 값은 안 바뀐다.** 두 손 잡은 다음 실행부터 정규화된 글을 읽는다(그것이 고침이다). 재료로 잰 것 — NBSP 제목 1,506 중 보통주 / 비보통주 판정이 갈리는 것 0 · 글자가 갈리는 것 1,389(글자는 `UsPastRanking`만 읽음). 거래소 코드에 NBSP가 드는지는 LISTING_COVER가 저장되지 않아 **재지 않음** |
| 「33행」은 NBSP만의 수 · Cf는 재생 안 됨 | 밖 | 그렇게 적는다. 재료의 Cf는 U+200E 4제목(2파일 · 전부 유닛·워런트 줄)뿐 |
| B-unmapped 원인 「제목 NBSP 93」을 묶어 판정 | 안 | 지금 엔진으로 NBSP 제목 행 94 → 정규화 뒤 33 바뀜 · 61 그대로(유닛·워런트 줄 48 · 보통주 줄 13 — CEPO · SVIV · TVA · SPEG · AAC 등 SPAC, 다른 원인). 갈래로 적는다 |
| 기호가 표지에 없음 490의 미룸 근거(오늘 순위 · 2025-07 절단)가 틀린 꼴 | 안·밖 | 관측 창 월말 격자(2025-09-30~2026-08-31 · 12일)마다 그날 유효한 판정이 「기호가 표지에 없음 · 숨김」인 줄을 셌다: 29줄 — SIC 6770(SPAC) 20 · METCI/METCZ(1220) · HTT(6199) · ANGX · ENHA · FRNM · MRLN · TMS(오늘 꺼짐 순위 2,118~2,939). 과거 시총은 **재지 않음**. 이것은 progress의 기존 항목 ③ 「클래스 축의 공시일 기호 맞추기」 그 자체다 — 이 계획에서 안 고치고 ③에 이 수를 싣는다. 표지 보통주 줄이 하나뿐인 457/490은 원장이 상장 클래스를 말해 준다는 지적은 ③의 처방 재료로 싣는다(그 처방이 맞는지는 재지 않음) |
| `Input.ticker`의 뜻을 바꾸면 실패 로그 · `Tally.failedLines`에서 표지가 사라짐 | 안 | `Input`에 `symbol`을 더하고(`split_part(s.ticker, '~', 1)`) 판정 호출에만 넘긴다. 로그는 표지 그대로 |
| 소비자 누락 — `ops/apply-window/04-unresolved.sh:14-20`(sed로 판 번호) | 안·밖 | 선언 꼴(`public static final int READER_VERSION = 3;`)을 그대로 두면 무해. 확인한다 |
| 문서 소비자 — ADR-0179의 들어옴·나감 14 · `reload-gate.md:43`의 13~14가 ERIE 등으로 바뀔 수 있다 | 밖 | ADR-0179 §결과의 「재지 않음」을 판 3까지 넓히고 ERIE를 적는다 |
| 「추가 비용 0」은 비용 논거 | 밖 | 지운다 |
| HTML 경로가 `ISO_8859_1`로 풀어 UTF-8 NBSP가 「Â」로 남는다(`SecRegistrantLookup:123` · `ListingEventSource:70`) | 밖 | SEC에 물었다(10-21 19:07 · 170콜): 최근 일별 색인의 8-A12B 67 · 25 13 주 문서에 UTF-8 다바이트가 0 · NBSP는 전부 엔티티(`&nbsp;`/`&#160;` — 8-A 65 · 25 12). 지금 닿지 않는다 — 고치지 않고 이 수를 적는다 |
| 임시 하네스 `NbspReplayTest`가 작업 트리에 남음 | 밖 | 빌드 전에 지운다 |
| ERIE · BRK.B · V · STZ — 다중 클래스로 주식수 NULL 166종목이 운영 편입 밖 | 밖 | 기존 관문 항목(클래스 축)의 몫이다(켬 순위 BRK.B 10 · V 15 · STZ 421 · 켬에서도 숨김 95). 순서는 그대로 |

판정: 뼈대 그대로 — 다시 받지 않는다.
