# 미국 증시 데이터 API 발급 가이드

**작성일** 2026-08-19
**대상** 프로젝트 소유자 본인
**목적** SEC EDGAR / Alpaca 접근 자격 확보 및 즉시 검증

관련 문서: `us-market-screener-planning-v2.md` (설계자용 기획서)

---

## 요약

| 소스 | 발급 절차 | 소요 시간 |
|---|---|---|
| **SEC EDGAR** | 없음 — 헤더 규칙만 지키면 즉시 사용 | 0분 |
| **Alpaca** | 회원가입 → MFA → 키 생성 | 약 10분 |

발급 후 **§3의 검증 2건을 반드시 수행**한다. 결과에 따라 기획 문서가 바뀔 수 있다.

---

# 1. SEC EDGAR

## 1.1 발급 절차가 없다

가입도, 신청도, API 키도 없다. **바로 호출하면 된다.**

대신 SEC가 요구하는 **접근 규칙**이 있고, 이걸 안 지키면 차단된다.

## 1.2 필수 — User-Agent 헤더

SEC 공식 문서가 요구하는 형식:

```
User-Agent: <이름> <연락 가능한 이메일>
Accept-Encoding: gzip, deflate
Host: data.sec.gov
```

공식 예시는 회사명과 관리자 이메일 형태이지만, **개인은 본인 이름과 개인 이메일이면 충분하다.** 회사 도메인이 없어도 무방하다.

예시:
```
User-Agent: Bapsang Stock Screener bapsang@example.com
```

> 🔴 **가장 흔한 실패 원인**
> SEC는 신원을 밝히지 않는 자동화 도구를 "미분류 봇"으로 판정하고 차단한다. Java의 HTTP 클라이언트도 기본 User-Agent를 자동으로 붙이므로, **반드시 명시적으로 덮어써야 한다.** 라이브러리를 쓰더라도 이 설정을 확인할 것.

## 1.3 호출 제한

- **초당 10회.** 사용하는 머신 수와 무관하게 사용자 단위로 적용된다
- 초과 시 해당 IP의 요청이 일시적으로 제한된다
- 속도가 임계값 아래로 떨어지면 자동으로 다시 접근 가능하다 (영구 차단 아님)

## 1.4 동작 확인

브라우저 주소창에 붙여 넣어도 JSON이 나온다 (Apple 예시):

```
https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json
```

터미널에서는:

```bash
curl -H "User-Agent: YourName your@email.com" \
     -H "Accept-Encoding: gzip, deflate" \
     "https://data.sec.gov/submissions/CIK0000320193.json" \
     --compressed | head -c 500
```

회사명과 티커가 보이면 정상이다.

## 1.5 알아둘 것

- **CIK는 10자리 zero-padding 필수.** Apple의 CIK는 320193이지만 URL에는 `CIK0000320193`으로 넣는다. 이걸 빠뜨리면 404가 난다
- **티커로 직접 조회할 수 없다.** 매핑 파일이 별도로 있다:
  `https://www.sec.gov/files/company_tickers_exchange.json`
- **프론트엔드에서 직접 호출할 수 없다.** CORS 미지원이므로 반드시 서버를 경유해야 한다
- **기술 지원이 없다.** SEC는 스크립트 개발·디버깅에 대한 지원을 제공하지 않는다

## 1.6 참고 링크

| 용도 | URL |
|---|---|
| 엔드포인트 명세 | `sec.gov/search-filings/edgar-application-programming-interfaces` |
| 접근 규칙 | `sec.gov/edgar/searchedgar/accessing-edgar-data.htm` |
| 개발자 허브 | `sec.gov/developer` |

> ⚠️ `api.edgarfiling.sec.gov`는 **공시 제출자용** API다. 토큰 발급 절차가 있어 혼동하기 쉬우나 우리와 무관하다.

---

# 2. Alpaca

공식 가이드가 2026년 3월 25일에 갱신되었다. 아래는 그 기준이다.

## Step 1. 회원가입

```
https://app.alpaca.markets/signup
```

가입 폼에 **Country of Tax Residence(세금 거주국)** 항목이 있다.

> 목록에 해당 국가가 없으면 페이퍼 트레이딩만 이용 가능하다.

**한국이 목록에 있든 없든 상관없다.** 우리는 시세 데이터만 쓰고 실거래는 하지 않으므로 페이퍼 계정으로 충분하다. 목록에 없어도 그대로 진행한다.

## Step 2. 이메일 인증 + MFA 설정

1. 가입 후 발송된 메일에서 인증
2. 로그인
3. **인증 앱(Google Authenticator 등) 설정** — 이 단계를 마쳐야 페이퍼 계정이 생성된다

## Step 3. Paper Trading 계정으로 전환 ⚠️

**대시보드 왼쪽 상단에서 "Paper Trading" 선택.**

키를 발급받기 **전에** 반드시 해야 한다. 페이퍼와 라이브는 키가 별도로 발급되고 서로 호환되지 않는다. 라이브 상태에서 키를 만들면 나중에 401 오류로 헤매게 된다.

## Step 4. API 키 발급

**Home 화면 → 우측 사이드바 API Keys 섹션 → Generate New Keys**

> 🔴 **Secret Key는 최초 생성 시에만 표시된다.**
> 분실하면 복구가 아니라 재발급이며, 기존 키는 무효화된다. 생성 즉시 안전한 곳에 저장할 것.

두 값을 받는다:
- **Key ID** (공개 식별자)
- **Secret Key** (비밀값, 1회만 표시)

## Step 5. 보관

환경변수 이름은 SDK 표준을 따른다:

```
APCA_API_KEY_ID     = <Key ID>
APCA_API_SECRET_KEY = <Secret Key>
```

> 🔴 **저장소에 절대 커밋하지 말 것.** `.env` 파일을 쓰고 `.gitignore`에 반드시 등록한다. 개인 GitHub 저장소라도 공개 여부와 무관하게 원칙을 지킨다.

## 2.1 엔드포인트 구분

| 용도 | Base URL |
|---|---|
| **시세 데이터 (주 사용처)** | `https://data.alpaca.markets` |
| 유니버스 조회 | `https://paper-api.alpaca.markets` |
| 라이브 트레이딩 | `https://api.alpaca.markets` |

시세 데이터는 paper/live 구분 없이 `data.alpaca.markets` 하나다. 다만 인증에는 **페이퍼 계정에서 발급한 키**를 쓴다.

## 2.2 인증 헤더

```
APCA-API-KEY-ID: <Key ID>
APCA-API-SECRET-KEY: <Secret Key>
```

## 2.3 오류 구분

| 코드 | 원인 |
|---|---|
| **403** | 인증 헤더가 아예 안 붙었다 |
| **401** | 키 쌍이 틀렸다 (대부분 paper/live 혼동) |

401이 나면 (1) 호출 환경에 맞는 키/시크릿 쌍인지, (2) 헤더 이름이 정확한지 확인한다.

---

# 3. 발급 직후 검증 🔴

**키가 나오면 가장 먼저 이 두 가지를 확인한다.** 기획 문서 §6의 G-1, G-2에 해당하며, 결과에 따라 시세 소스 자체가 바뀔 수 있다.

각각 호출 한 번이면 판별된다.

## 검증 1 — 통합 피드(SIP) 과거 데이터 조회 가능 여부

**이게 가장 중요하다.** 실패하면 Alpaca 채택 근거가 무너진다.

### 배경

Alpaca 무료 플랜의 실시간 커버리지는 IEX(미국 거래량의 약 2.5%)로 표기된다. 이것만으로는 스크리너를 만들 수 없다.

그런데 공식 문서상 무료 플랜의 **과거 데이터** 제약은 "최근 15분"이 유일하다. 이 서술대로라면 15분 이전 데이터는 SIP(전 거래소 통합, 100%)로 열려 있다는 뜻이다.

다만 페이퍼 전용 계정 약관은 별도로 "IEX 데이터 수신 자격"이라고 명시하고 있어 해석이 엇갈린다. **문서만으로는 판단할 수 없으므로 직접 찍어봐야 한다.**

### 방법

```bash
curl -s \
  -H "APCA-API-KEY-ID: $APCA_API_KEY_ID" \
  -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=AAPL&timeframe=1Day&start=2024-01-02&end=2024-01-31&feed=sip"
```

### 판정

| 결과 | 의미 |
|---|---|
| ✅ 200 + `bars` 배열에 데이터 | **합격.** 계획대로 진행 |
| ❌ 403 / 구독 필요 메시지 | **불합격.** 시세 소스 재선정 필요 |
| ❌ 200이지만 `bars`가 비어 있음 | 날짜 범위나 파라미터 문제일 수 있음. 다른 기간으로 재시도 |

`feed=iex`로 바꿔서도 호출해 **두 결과를 비교**하면 확실하다. 값이 다르면 SIP가 실제로 열린 것이다.

### 불합격 시

IEX 단독 피드로는 스크리너 구성이 불가능하다. 기획 문서 부록 C.2의 유료 대안($9~$30 구간)으로 넘어간다. **기획 실패가 아니라 정상적인 분기다.**

## 검증 2 — 수정주가 파라미터 동작 여부

기술적 지표 계산 전체의 정합성이 걸린 항목이다.

### 배경

과거(2023년경) `adjustment` 값을 바꿔도 결과가 동일하고 액면분할이 반영되지 않는다는 사용자 보고가 있었다. 지금은 수정되었을 가능성이 높지만 확인이 필요하다.

### 방법

액면분할 이력이 있는 종목을 쓴다. NVDA는 2024년 6월에 10:1 분할을 했으므로 그 앞뒤 기간이 적합하다.

```bash
# 미조정
curl -s -H "APCA-API-KEY-ID: $APCA_API_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=NVDA&timeframe=1Day&start=2024-05-01&end=2024-05-31&adjustment=raw"

# 전체 조정
curl -s -H "APCA-API-KEY-ID: $APCA_API_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=NVDA&timeframe=1Day&start=2024-05-01&end=2024-05-31&adjustment=all"
```

### 판정

| 결과 | 의미 |
|---|---|
| ✅ 두 응답의 종가가 약 10배 차이 | **합격.** 분할이 정상 반영됨 |
| ❌ 두 응답이 동일 | **불합격.** 자체 조정 계산 필요 |

### 불합격 시

기업행위(분할·배당) 데이터를 별도로 받아 직접 조정해야 한다. Alpaca에 `/v1/corporate-actions` 엔드포인트가 있으니 그것부터 확인한다.

## 추가 확인 (선택)

여유가 있으면 함께 봐두면 좋다:

- **과거 데이터 실제 시작 시점** — 문서상 2016년부터. 상장 시기가 다른 몇 종목으로 최초 봉 날짜 확인
- **다종목 일괄 조회** — `symbols`에 여러 개 넣고 `next_page_token` 순회가 정상 동작하는지
- **유니버스 조회** — `GET https://paper-api.alpaca.markets/v2/assets` 호출 후 `exchange`가 NYSE/NASDAQ인 항목 개수 확인 (5,000~6,000 내외 예상)

---

# 4. 검증 결과 전달

두 검증이 끝나면 결과를 기획 세션에 알려준다. 다음이 결정된다:

| 결과 | 후속 조치 |
|---|---|
| 검증 1·2 모두 합격 | 기획 문서 확정, 설계자 세션 착수 |
| 검증 1 불합격 | 시세 소스 재선정 — 기획 문서 §2.2, 부록 C.2 개정 |
| 검증 2만 불합격 | 기업행위 데이터 조달 방안 추가 — 범위 확대 |

**어느 쪽이든 설계 착수 전에 확정되어야 한다.** 설계자가 잘못된 전제 위에서 파이프라인을 짜면 되돌리는 비용이 크다.
