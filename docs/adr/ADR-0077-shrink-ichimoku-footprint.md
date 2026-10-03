---
id: ADR-0077
status: 채택됨
date: 2026-08-20
applies_to:
  - "src/main/java/**/indicator/calculator/ichimoku/**"
  - "docs/adr/ADR-0046-indicator-layer-precision-and-boundaries.md"
---

# ADR-0077. 이치모쿠를 스크리닝 조건 하나 크기로 되돌린다

## 결정

**사용자 결정이다(2026-08-20).** 이치모쿠의 문서·하네스 자국을 지표 18개 크기로 줄인다.
지운 것은 `ichimoku-port` skill(195줄)과 사용자가 반입했던 Pine 원문
`docs/external/ichimoku_sp_screener_patched.txt`(1,166줄)이고, `ADR-0046`은 번호를 남긴 채
**지표 계층 공용 규칙 문서로 갈아끼웠다**(289 → 179줄).

**코드와 지표는 건드리지 않았다.** 계산기 30파일·지표 18개·`indicator_value` 992,988행이
그대로다. 이번 변경은 문서·javadoc·주석에만 걸린다.

**IMPORTANT — 위 문장이 적힌 2026-08-20에 이미 계산기 13개는 없었다.** 엿새 전
`ffd8f24`가 지웠고 그것은 **에이전트 판단이었다.** 여기 "지표 18개"라 적힌 것은 그
상태를 사실로 받아들인 결과이지 사용자가 18을 고른 것이 아니다 — 사용자가 고른 문장은
아래 표의 *"유지 — 문서·하네스만 축소"*뿐이다. **13개는 2026-09-02에 되살아났다**
(ADR-0122). 지금 활성은 **31개**다.

### 사용자가 고른 문장과 내 해석

**MUST NOT: 둘을 붙여 적지 않는다** — 붙이면 다음 세션이 해석을 결정으로 읽는다.

| 갈림 | 사용자 문장 | 내 해석 (결정 아님) |
|---|---|---|
| 지표 18개 | *"유지 — 문서·하네스만 축소"* | 조건 빌더 노출과 매일 계산을 그대로 둔다. `ADR-0061`의 MUST NOT은 유지하되 **죽은 근거를 살아 있는 근거로 갈았다** |
| ADR-0046 | 1차 *"삭제 + 이관"* → 2차 ***"번호만 남기고 문서를 갈아끼운다"*** | **1차는 무효다.** 아래 §배경 참조 |
| Pine 원문 | *"지움"* | `.claude/rules/doc-facts.md`가 이 파일을 「사람이 쓴 원자료」 보호 목록에 넣어 뒀다 — 사용자가 그 보호를 해제한 것이므로 그 줄도 함께 뺐다 |

## 규칙

- **MUST NOT: 이치모쿠 전용 skill을 다시 만들지 않는다.** 조건 하나가 자기 skill을 가지면
  그 파일을 여는 것만으로 낡은 사실이 컨텍스트에 들어온다. 지금 남은 skill 셋은 전부
  **작업 절차**(적재·소스·브라우저)이지 지표 하나가 아니다.
- **MUST NOT: 「이치모쿠는 이랬다」를 문서로 늘리지 않는다.** 철회된 계층·폐기된 프리셋의
  경위는 이 ADR 한 곳에 있고, 그 이상은 `git log`가 답한다.
- **MUST: 창에서 유도된 수를 적을 때 측정 날짜를 붙인다.** 이번에 걷은 일곱 중 넷이
  날짜가 없어 **낡았는지 물을 수조차 없었다.** 유도값은 `1,100|751` 같은 패턴에 안 걸려
  검증 명령이 원리적으로 못 잡는다.
- **MUST: 문서를 지우기 전에 `rg --hidden`으로 참조를 전수로 센다.** `--no-ignore`는 ignore
  파일만 끄고 **숨김 디렉터리는 여전히 건너뛴다** — `.claude`가 통째로 빠져 66 vs 75건이
  갈렸다.

### 알려진 낡음 — 고칠 수 없거나 고치면 안 되는 것

- **`V23__market_index.sql:31`과 `V25__drop_ichimoku_scoring_values.sql:1`.** 둘 다 철회된
  게이트와 사라진 절을 가리킨다. **적용된 마이그레이션이라 주석 한 글자만 바꿔도
  `flyway_schema_history`의 체크섬과 어긋나 운영 기동이 `FlywayValidateException`으로 죽는다**
  (`application.yaml`에 `validate-on-migrate` 재정의가 없어 기본값 `true`).
  **`application-test.yaml`의 `clean-on-validation-error`는 Boot 4.1·Flyway 12.4에 바인딩되지
  않아 아무 일도 하지 않는다** — 여덟을 어긋내 실측했고 **테스트도 운영과 똑같이 죽는다.**
  그래서 `./gradlew build`가 이 사고의 탐지기다.
- **`docs/phase-criteria.md`의 「대상이 없다는 것과 통과했다는 것이 다르기 때문이다」.**
  `check-doc-staleness.sh` §5b가 `통과했다`를 문자열로 잡아 매 세션 경고한다. **오탐이며
  그 문장은 통과 읽힘을 되레 부인한다.** 훅을 조용하게 만들려고 고치면 훅이 지키려는
  구분 자체가 사라진다.
- **`.claude/hooks/check-premises.sh`의 전제 개수 주석.** 67이라 적혀 있고 실제는 69다.

## 검증

```premise 일목 자국 — 관측 18개와 되살린 점수화 13개가 다 활성인가
psql -d stock_screener -At -c "WITH RECURSIVE days AS (SELECT min(trade_date) d FROM indicator_value UNION ALL SELECT (SELECT min(trade_date) FROM indicator_value WHERE trade_date > days.d) FROM days WHERE days.d IS NOT NULL)
SELECT CASE
  WHEN total = 0 THEN '잴 것이 없었다 — indicator에 ICHI_ 행이 하나도 없다'
  WHEN active <> 31 THEN '활성 ICHI_ 가 ' || active || '개다 — 31이 아니다'
  ELSE 'OK 활성 ' || active || ' / 비활성 ' || (total - active)
       || ' · 점수화 13코드 값 ' || scoring_rows || '행' END
FROM (SELECT count(*) total, count(*) FILTER (WHERE is_active) active,
             (SELECT count(*) FROM days JOIN indicator_value iv ON iv.trade_date = days.d
               AND iv.indicator_code IN ('ICHI_TIER','ICHI_SIGNAL','ICHI_PILLAR','ICHI_CONFIDENCE',
                     'ICHI_CONCLUSION','ICHI_BULL','ICHI_BEAR','ICHI_EXT_SIGNAL','ICHI_STAGE',
                     'ICHI_LEAF','ICHI_FRESHNESS','ICHI_T3YEOK','ICHI_KAIZEN_MATCH')) scoring_rows
      FROM indicator WHERE code LIKE 'ICHI/_%' ESCAPE '/') s"
```

**MUST NOT: 점수화 13개의 값이 0행인 것을 붉히지 않는다.** 되살린 직후와 백필 전 사이가
그 상태이고(ADR-0122), 그때 붉히면 **의도한 동작에 우는 펜스**가 된다. 세는 것은 활성
개수이고 행 수는 함께 찍어 주기만 한다.

**문서 쪽은 정적으로 잰다.** **MUST: `--hidden`을 붙이고 이 파일을 뺀다** — `--no-ignore`는
숨김 디렉터리를 안 열어 `.claude`가 통째로 빠지고, 이 파일 자신이 아래 문자열을 이력으로
들고 있어 빼지 않으면 **떨어질 수 없는 검사**가 된다.

```
X='docs/adr/ADR-0077-shrink-ichimoku-footprint.md'
rg -n --hidden --no-ignore -g '!build/**' -g "!$X" 'ichimoku-port|ichimoku_sp_screener' .  # → 0건
rg -n --hidden --no-ignore -g '!build/**' -g "!$X" '29개|29코드' src docs                  # → 0건
rg -n --hidden --no-ignore -g '!build/**' -g '!**/node_modules/**' -g '!frontend/dist/**' \
   -g "!$X" '137일자|136\.8|236주|571거래일|43,093' .                                     # → 0건
```

## 배경

**증상은 「이치모쿠를 고칠 때마다 이미 제거된 junk 데이터가 주입된다」였다.** 재보니
데이터가 아니라 **문서였다.**

`V25`가 지운 13코드는 계산기가 전부 삭제돼(`ffd8f24`) 재생성 경로가 없다 — DB의 13행은
`is_active = false`로 얼어 있고 `git log -S "ICHI_TIER" --all -- src/`에도 재유입이 없다.
되살아난 것은 **낡은 사실**이다. 이치모쿠 파일을 열면 `ichimoku-port` skill이 로드되는데
그 skill과 코드 javadoc이 **철회된 29코드 체제**와 **죽은 읽기 창 1,100**을 현재형으로
말했고, 죽은 13코드의 이름이 문장으로 살아 있는 곳은 사실상 옛 `ADR-0046` 하나였다.

**부피는 근거가 아니다.** `ICHI_*` 18개가 `indicator_value`의 17.4%(992,988행)를 쓰고 활성
프리셋 여덟 중 **0개**가 읽는 것은 사실이지만, 활성 지표 102개 중 **57개**가 같은 처지이고
그것이 저장 부피의 **56.1%**다. 게다가 미사용 지표 중 코드당 부피 상위 열둘
(`ATR_14`·`RSI_14` 각 63,481 · `SMA_20` · `PBR` · `MACD` …)이 **전부 `ICHI_*` 하나치
(55,166)보다 크다.** 「안 읽는데 부피를 쓴다」는 잣대로 고르면 `ICHI_*`가 먼저 걸리지
않는다 — **이 실측이 「지표는 유지한다」의 근거다.**(2026-08-20)

### 1차 승인이 잘못된 전제 위에서 나왔다

처음 사용자에게 **「ADR-0046 삭제 + 이관 대상 넷」**을 권했고 승인을 받았다. 그 뒤 전수로
세니 **참조가 66곳이고 그중 47곳이 `src/`**였으며, **일목 패키지 밖 파일 15개**가 이 문서를
공용 규칙의 정본으로 인용하고 있었다. 이관 대상도 넷이 아니라 아홉이었다 —
`HOSODA_ANCHORS`·`n_54` 도달 불가·죽은 코드 목록·`SeriesMath`의 정밀도 MUST가 전부 코드에
살아 있었다. **즉 「이치모쿠 전용 ADR이 있다」는 내 전제 자체가 틀렸고, 그 위에서 받은
승인은 승인이 아니었다.** 사용자에게 실측을 들고 다시 물어 **「번호만 남기고 갈아끼운다」**를
받았다.

**놓친 이유**: 옛 `ADR-0046`의 `applies_to`에 **코드 glob이 하나도 없었다**(문서 셋뿐).
그래서 `remind-adr.sh`도 이치모쿠 java 편집 때 이 ADR을 안 띄웠고, **문서가 스스로
신고하지 않는 하중을 문서 안쪽만 보고 판단했다.**

## 버린 대안

- **`ADR-0046`을 삭제한다** — `src` 47곳이 끊긴다. `check-doc-staleness.sh` §6은
  `git ls-files '*.md' '*.sh'`만 훑으므로 `.java`·`.sql`의 죽은 포인터를 **원리적으로 못
  잡는다.** 게다가 `V23`·`V25`는 Flyway 체크섬 때문에 고칠 수도 없다.
- **지표 18개까지 지운다** — 사용자가 유지를 골랐다. 부피 근거는 위 실측이 죽였고,
  `ADR-0061`의 남은 근거(조건 빌더에서 고를 수 있다)는 **미사용 지표 57개 전부에 똑같이
  걸린다.** 18개만 지우면 나머지 39개와 잣대가 갈린다. (2026-08-20 실측으로 사용자가 저장한
  전략은 0건이라 「사용자 전략이 건다」는 근거는 지금 비어 있다 — 그것 하나로 뒤집기에는
  잣대 문제가 더 무겁다.)
- **공용 규칙을 javadoc으로만 분산한다** — `.claude/rules/doc-facts.md`의 「사실 하나의
  정본을 한 곳에 둔다」에 걸린다. 같은 규칙이 2~3곳으로 복제되고 거울 표 등록도 없다.
- **`ADR-0046`을 축약만 하고 이름을 남긴다** — 파일이 「이치모쿠 전용 ADR」로 계속 읽힌다.
  제목과 `applies_to`가 그 인상을 만들고, 그것이 이 결정이 겨눈 것이다.

## 결과

- **쉬워지는 것**: 이치모쿠 파일을 열어도 skill이 안 뜬다. 남은 문서는 `ADR-0046` 179줄
  하나이고, 그 안의 규칙은 대부분 일목 밖 지표가 쓴다.
- **어려워지는 것**
  - **Pine 원문이 없어 이식 정확성을 되짚을 경로가 없다.** 원문 줄번호로 가리키던 근거는
    전부 사라졌다. 최소 대비로 **원본이 `mod_i`를 건너뛴다는 사실**(이름은 「13 수식자」인데
    실제 12개)을 `WeeklyAnalyzer` javadoc에 남겼다. `ADR-0046 §승인된 예외`가 계속
    **「지표 18개의 값을 검증된 것으로 읽지 않는다」**를 든다.
  - **`ADR-0046`의 이탈 번호가 굳었다.** 15파일이 번호로 가리키고 `V23`·`V25`는 못 고치므로,
    철회된 이탈 2도 스텁으로 남겨야 한다.
- **되돌리려면**: `git revert`. 지운 두 파일은 이력에 있다.
