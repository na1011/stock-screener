---
name: batch-load
description: 시세·재무를 적재·백필하거나 일일 갱신을 돌릴 때. BootstrapRunner의 --job=universe|initial-load|backfill 명령 형태와 각 플래그가 실제로 하는 일, 조용히 아무 일도 하지 않는 플래그 조합, 적재 후 확인 항목, 소스가 전 거래일을 아직 안 내놨을 때의 함정.
---

# 적재 배치 (`BootstrapRunner`)

`bootstrap` 프로파일 전용이다. 일반 기동에서는 실행되지 않는다.

**이 문서는 따라야 할 순서가 아니라 알아야 할 사실이다.** 무엇을 어떤 범위로 적재할지는
상황이 정한다 — 여기서는 각 명령이 실제로 무엇을 하고 무엇을 건너뛰는지만 적는다.

```bash
# 공통 접두
./gradlew bootRun --args='--spring.profiles.active=bootstrap <아래>'

--job=universe
--job=initial-load --price-years=7 --financial-years=7 --reload-existing=true
--job=backfill --from=2025-11-11 --as-of=2026-08-05
```

## 각 잡이 하는 일

| `--job` | 대상 | 비고 |
|---|---|---|
| `universe` | 유니버스 편입 종목 갱신 | 시총 어댑터가 `@ConditionalOnApiKey`라 키가 없으면 시드만 남는다(ADR-0012) |
| `initial-load` | 시세·재무 원자료 | **대상 선정이 `NOT EXISTS (price_daily)`다** — 아래 참조 |
| `backfill` | 지표 계산 | 지정한 구간을 **날짜별로 반복**한다 |

## 조용히 아무 일도 하지 않는 조합

**`--reload-existing=true`가 없으면 기존에 봉이 하나라도 있는 종목은 건너뛴다.** 기간을
`--price-years=2`에서 `7`로 늘려도 마찬가지다 — 대상 선정이 "봉이 있는가"만 보기 때문에
늘어난 기간이 반영되지 않는다. 그리고 **잡은 성공으로 끝난다.** 로그에도 예외에도 흔적이
없어서, 적재됐다고 믿게 되는 것이 이 함정의 실체다.

`warn-bootstrap-args.sh`가 이 조합을 실행 직후 알리지만, 알림은 알림일 뿐이다 — 새로 적재한
것이 맞는지는 아래 확인 항목으로 직접 본다.

**백필 한 번은 워밍업이 긴 지표를 다 채우지 못한다.** 러너가 `maxEffectiveLookback` 봉만
읽으므로 `SMA_200`은 실행당 ~25거래일치만 나온다. 구간 전체를 돌아야 채워지고, **180일이
1시간 51분**이다. 이 소요가 작업 계획을 바꾸므로 착수 전에 알아야 한다.

**그 시간 동안 gradle을 쓸 수 없다는 것이 착수 전에 알아야 할 두 번째 사실이다.** 컴파일
산출물이 도는 JVM의 classpath 밑에 깔리고, `clean`은 그것을 지우며, `--stop`은 포크된 적재
JVM을 함께 끈다. 예외는 `./gradlew checkDuplicateFiles` 하나다(단독 실행 시 아무것도
컴파일하지 않는다) — 그래서 적재 창 안에서도 커밋 전 복제본 확인은 할 수 있다. 창을
피할 수 없으면 검증과 커밋을 적재 앞이나 뒤로 몰아서 계획한다.

실행 중에 어긴 것은 `warn-load-running.sh`가 알린다. 여기 적는 이유는 그 알림이 **명령이
실행된 뒤에** 오기 때문이다 — 계획을 바꿀 수 있는 시점은 착수 전 한 번뿐이다.

끊어야 하면 **PID를 특정해서 끈다.** MUST NOT: `pkill -f java`류를 쓰지 않는다 — 운영
앱이 함께 죽고, `guard-destructive.sh`에 `kill` 판정이 없어 아무것도 막지 않는다.

## 적재 후 확인

행 수만 보면 부분 적재와 정상 적재가 같아 보인다. 넷 다 본다.

- `ck_price_ohlc` 제약 위반 0
- 편입 종목 중 시세가 하나도 없는 종목 0
- `min(trade_date)`가 요청한 구간 시작과 일치 — 어긋나면 `--reload-existing`을 빠뜨린 것이다
- `listed_shares` 빈값 0 — PER·PBR이 여기 의존한다(ADR-0013)

## 외부 소스의 함정

원자료를 다시 긁는 잡이므로 어댑터의 방어가 걸려 있는지가 결과를 가른다. 정본은
[`docs/api-spec/`](../../../docs/api-spec/)이고, 파이프라인 **코드**를 고칠 때의 제약은
`.claude/rules/data-pipeline.md`가 자동으로 로드한다.

IMPORTANT — 공공데이터포털은 없는 종목·휴장일·파라미터 오타의 응답이 동일하다
(`resultCode "00"` + `totalCount 0` + HTTP 200). 날짜 형식 오타가 "그날 데이터 없음"으로
저장된다.

**적재 결과가 비어 있을 때 소스 탓으로 결론짓기 전에 요청 파라미터를 먼저 본다.** 위 문장은
`.claude/rules/data-pipeline.md`와 **일부러 같은 사본**이다 — 적재는 어떤 파일도 열지 않아
그 규칙이 발화하지 않으므로 없앨 수 없고, 어긋나면 `check-doc-staleness.sh`가 잡는다.

**소스가 내주지 않는 평일이 있다.** 2026-08-07(금)이 그랬다 — 이틀 뒤인 08-09 조회에도
없으므로 공개 지연이 아니라 **휴장**이다(실측은 `docs/api-spec/data-go-kr-stock-price.md`).
주말이 아닌데 비는 날이 있다는 뜻이라, **대상 일자를 달력으로 정하면 빈 날을 긁는다.**

그때 **일일 갱신은 `SUCCESS`로 끝나고 `success_count`도 350×2로 찬다.** 종목마다 "예외 없이
끝났다"를 성공으로 세기 때문이고, 저장된 행이 0이어도 같다. 시세 0건이면 `DailyUpdateJob`이
WARNING을 내지만 **그것은 실행 시점의 알림이고 이력 화면은 여전히 초록이다.** 나중에
"그날 적재됐나"를 묻는 것은 **날짜별 행 수뿐이다:**

```sql
SELECT trade_date, count(*) FROM price_daily WHERE trade_date >= '<대상일>' GROUP BY 1;
```

같은 공백을 **유니버스 어댑터는 알아채고 로그로 말한다**(`2026-08-07이 휴장일이라 2026-08-06의
시세로 유니버스를 만든다`). 그래서 **어느 날짜가 소스에 있는지 확인하는 가장 싼 방법이
유니버스 리밸런싱 1콜**이다 — 350콜을 태우기 전에 그것으로 묻는다.
