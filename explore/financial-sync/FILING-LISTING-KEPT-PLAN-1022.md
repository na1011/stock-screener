# 공시 목록 잡이 10-20부터 선다 — 장부 계수의 단위 · 시장 실패 격리 (2026-10-22)

★ = 운영 SELECT · 코드로 직접 쟀다.

## 1. 실측
- ★ `filing-listing` 실행 869(10-20 14:44) · 887(10-21 14:19)이 FAILED — `DataIntegrityViolationException`:
  `filing_listing_day` (US, 2026-10-19, LISTED, source 90, kept 92)가 `ck_filing_listing_day_rows`(`kept_rows <= source_rows` · V67)를 어긴다.
- ★ 두 시장 커서가 모두 10-18에 멈췄다(`SELECT market, max(listing_date) FROM filing_listing_day` → US 10-18 · KR 10-18).
  `FilingListingJob.MARKETS = [US, KR]`이고 예외가 시장 루프 밖 `catch (RuntimeException)`으로 실행 전체를 세워 **국내도 같이 멈췄다**.
- ★ 뿌리: `SecFilingListSource.toDay`의 `sourceRows`는 정기 공시 **줄 수**인데, `FilingLedgerStore.recordDay`의 `kept_rows`는
  `filings.size() − 시대 밖` = **줄 × 그 CIK의 종목 행 수**다. V67 주석은 「종목이 여럿인 CIK는 종목마다 센다」인데 제약은 `kept ≤ source`라
  정의와 제약이 처음부터 모순이다. 10-17 「묶인 등록인」(`10fe3c6f`) 뒤 CIK당 행이 늘었고 10-19에 처음 넘었다(90줄 → 92행).
- ★ `kept_rows`를 읽는 곳 0(`grep -rn kept_rows src ops docs .claude` — 넣는 한 줄뿐).
- 국내(`DartFilingListSource`)는 줄 하나가 종목 하나(종목코드 매핑)라 지금 정의로도 안 넘는다.

## 2. 계획
1. `kept_rows` = 그날 장부에 붙은(시대 안 · 새로 넣었든 이미 있었든) **서로 다른 공시 수**. 공시 하나가 정기 줄 하나에서 오므로
   `kept ≤ source`가 구조로 선다. 마이그레이션 없음(V67 제약 그대로) — V67 컬럼 주석 「종목마다 센다」는 낡은 서술이 된다(기록: ADR-0176 §결과).
2. 시장 단위 실패 격리: 시장 하나의 `DataAccessException`(DB 쓰기 실패)은 그 시장의 실패로 세고(`failed++` · `log.error` · 운영 알림 ERROR ·
   메모) 다음 시장을 돈다. `BatchShutdownException`은 다시 던진다. 그 밖의 `RuntimeException`은 지금처럼 실행을 세운다(넓은 catch를 안 만든다).
3. 시험: ① CIK 하나에 종목 행 둘 · 정기 줄 하나 → kept 1 · 제약 통과(고치기 전 코드에서 붉다) ② 시대 밖만 붙은 공시는 안 센다
   ③ US 쓰기가 DB 예외로 서도 KR이 닫힌다(대조군: 고치기 전 코드에서 KR 커서가 안 움직인다).
4. 배포: 오늘(10-22 목) 13:30 전 — 14:16 사슬의 `filing-listing`이 10-19~10-21을 닫는다(커서가 밀린 날을 차례로 받는다).

## 3. 드라이런
- 0콜: 단위 · 통합 시험(빈 시험 DB · 꼴마다 최소 행).
- 1~3콜: 운영 DB 읽기 전용 + SEC 일일 색인 10-19 · 10-20 · 10-21로 `toDay` → 새 계수가 날마다 `≤ source`인지, 줄 × 행이 몇이었는지 잰다(쓰기 0).

### 드라이런 결과 ★ (SEC 일일 색인 2콜 + 티커 파일 1콜 + 운영 SELECT · 쓰기 0)
- 잡의 CIK 묶음 술어를 그대로 복제(`sec_cik` 있으면 그것 · 없으면 SEC 티커 파일): 10-19 원본 90 · **옛 계수 92(운영 실패 행과 같다)** · 새 계수 86 /
  10-20 원본 113 · 옛 97 · 새 91. 넘친 이유는 한 공시가 발행사의 워런트 · 유닛 티커 행까지 붙는 CIK다(10-19 CIK 2102123 행 4 · 나머지 셋 행 2).
- 첫 재현은 `sec_cik`만 써서 옛 계수 46을 냈다 — 티커 파일 경로를 빠뜨린 내 재현의 결함이었다(잡의 술어를 복제해 닫음).
- 10-21 색인은 403(아직 안 올라온 날 — 받기 쪽 일상).
- 단위·통합 시험 6/6 · 대조군: 고치기 전 코드에서 새 시험 둘이 운영과 같은 `DataIntegrityViolationException`으로 붉다.

## 4. 남는 것
- 그날 못 먹은 공시 소비는 커서가 따라잡는 실행에서 장부에 붙고 다음 소비가 먹는다 — 지연(10-20~배포일)은 되돌릴 수 없다.
