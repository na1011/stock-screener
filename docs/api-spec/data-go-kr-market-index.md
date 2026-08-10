# 공공데이터포털 — 금융위원회_지수시세정보

코스피·코스닥 등 **주가지수 일별 시계열**. 2년 주간 베타(T-08)의 원자료다 —
ADR-0039 §남은 결손의 **단계 D**(DART 밖 소스가 필요한 것)에 걸려 있던 항목.

- 출처: 공공데이터포털 오픈API 활용가이드 `금융위원회_지수시세정보`(사용자 제공, 2026-08-10)
- **원본 `.docx`는 저장소에 두지 않는다**(저작물, 그리고 grep·diff가 되지 않는다).
  필요한 내용을 여기 옮겨 적었다 — `data-go-kr-stock-price.md`와 같은 취급이다.
- 서비스 시작 2021-11-16, 버전 1.0

---

## 🔴 아직 부를 수 없다 — 활용신청이 남았다 (2026-08-10 실측)

**`DATA_GO_KR_SERVICE_KEY`가 이 서비스에는 등록되어 있지 않다.**

```
HTTP 403
{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
  "errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
  "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}
```

**공공데이터포털은 서비스마다 따로 활용신청을 받는다.** 키 문자열은 같아도 승인된 서비스가
아니면 403이다. 시세정보(`GetStockSecuritiesInfoService`)가 되는 것은 그것만 신청했기
때문이고, **이 서비스는 사람이 포털에서 신청해야 열린다**(`TODO.md`).

**MUST NOT: 이 403을 키가 틀린 것으로 읽지 않는다.** 같은 키로 시세는 정상 응답한다.
그리고 **봉투가 `response`가 아니라 `OpenAPI_ServiceResponse`다** — 정상 경로의 파서로는
`resultCode`가 `None`으로 나와 "빈 응답"처럼 보인다(`data-go-kr-stock-price.md` §미등록 키).

**아래 명세는 문서에서 옮긴 것이고 실측이 아니다.** 승인 뒤 첫 호출로 대조한다.

---

## 엔드포인트

```
https://apis.data.go.kr/1160100/service/GetMarketIndexInfoService/getStockMarketIndex
```

- 서비스 prefix `1160100`이 **시세정보와 같다**(같은 제공기관). 그래도 활용신청은 별개다.
- 오퍼레이션 셋 중 **주가지수시세**만 쓴다. 채권지수·파생상품지수는 이 프로젝트와 무관하다.
- 최대 메시지 4000 byte, 평균 응답 500ms, **초당 최대 30 TPS** — 시세정보와 같은 수치다.

## 요청 파라미터

| 이름 | 필수 | 설명 |
|---|---|---|
| `serviceKey` | Y | 발급 인증키. **이중 인코딩 함정은 시세와 같다**(`data-go-kr-stock-price.md`) |
| `numOfRows` · `pageNo` | — | 페이징 |
| `resultType` | — | `xml`\|`json`. 기본 `xml` → **`json`을 쓴다** |
| `basDt` | — | 기준일자 **일치**(`YYYYMMDD`) |
| **`beginBasDt`** · **`endBasDt`** | — | 기준일자 **범위**. `begin` 이상, `end` **미만** |
| `likeBasDt` | — | 기준일자 부분일치 |
| **`idxNm`** | — | 지수명 **일치**. 예: `코스피` |
| `likeIdxNm` | — | 지수명 부분일치 |
| `beginEpyItmsCnt` · `endEpyItmsCnt` | — | 채용종목 수 범위 |
| `beginFltRt` · `endFltRt` | — | 등락률 범위 |

**IMPORTANT — `endBasDt`는 미만이다.** 문서 원문이 *"기준일자가 검색값보다 **작은** 데이터"*다
(`beginBasDt`만 "크거나 같은"이다). 종료일을 포함시키려면 하루를 더해야 하고, **안 그러면
구간 끝 하루가 조용히 빠진다.**

**`idxNm` + `beginBasDt` + `endBasDt`로 한 지수의 기간 시계열이 1콜에 온다** — 시세의
종목별 기간조회와 같은 형태다. T-08이 요구하는 104주(2년)도 페이징만 맞추면 된다.

## 응답 필드

| 필드 | 뜻 | 비고 |
|---|---|---|
| `resultCode` · `resultMsg` | 결과코드·메시지 | `00` / `NORMAL SERVICE.` |
| `numOfRows` · `pageNo` · `totalCount` | 페이징 | **`totalCount`를 보지 않으면 조용히 잘린다** |
| `basDt` | 기준일자 | `YYYYMMDD` |
| **`idxNm`** | 지수명 | 예: `코스피` |
| `idxCsf` | 지수분류명 | 예: `KOSPI시리즈` |
| **`clpr`** | 종가 | **베타 계산의 입력.** 예: `2770.69` |
| `mkp` · `hipr` · `lopr` | 시가·고가·저가 | — |
| `vs` · `fltRt` | 전일 대비 · 등락률 | **파생값이다. 우리는 `clpr`에서 자체 계산한다**(spec §7) |
| `trqu` | 거래량 | — |
| `epyItmsCnt` | 채용종목 수 | 예: 839 |
| `basPntm` · `basIdx` | 기준시점 · 기준지수 | 예: `19800104` / `100` |
| `lsYrEdVsFltRt` | 전년말 대비 등락률 | 파생값 |

**MUST NOT: `fltRt`를 수익률로 그대로 쓰지 않는다.** 지표는 언제나 원자료에서 자체 계산한다
(`.claude/rules/data-pipeline.md`). 주간 수익률은 `clpr`을 주 단위로 리샘플해서 만든다.

## 미검증 항목

**전부다 — 아직 한 번도 부르지 못했다.** 승인 뒤 먼저 확인할 것:

- `idxNm`에 넣을 **정확한 문자열**. `코스피`·`코스닥`이 맞는지, `KOSPI`인지. **틀리면 0건이
  오고 그것은 "그날 데이터 없음"과 구분되지 않는다**(시세와 같은 함정).
- 코스닥 지수의 `idxCsf` 값과 지수명.
- **이력 범위** — 2년(104주)이 실제로 오는지. T-08의 성립이 여기 걸린다.
- 공개 지연이 시세와 같은지(D+1로 보이는지). **시세와 같다고 가정하지 않는다.**
- 휴장일 표현. 0건인지, 직전 값을 채운 행인지(시세는 거래정지에 0으로 채운 행을 준다).
