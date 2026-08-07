# 외부 서비스 발급 목록

사용자가 직접 발급·인증해야 하는 것들. **4건(1·2·3·4)은 완료됐고 나머지 4건은 미진행이다.** 현황은 아래 표가 정본이다.

에이전트가 대신 할 수 없는 작업만 모았다(계정 생성, 본인 인증, 콘솔에서 키 발급 등). 나중에 한 번에 진행하기 위한 목록이며, 각 항목에 **언제까지 필요한지**와 **없으면 무엇이 막히는지**를 적었다.

- MUST NOT: 발급받은 실제 키·시크릿을 이 문서나 코드·커밋에 적지 않는다. `.env`(gitignore 대상)나 셸 환경변수로만 다룬다. 이 문서에는 환경변수 **이름**만 있다.
- 발급을 마치면 해당 항목의 상태를 `완료`로 바꾸고 발급일을 적는다.

---

## 현황

| # | 항목 | 상태 | 필요 시점 | 없으면 막히는 것 |
|---|---|---|---|---|
| 1 | Google OAuth 클라이언트 | **완료 (2026-08-07)** | Phase 5~6 | 브라우저 로그인. 인가 로직은 이미 동작 |
| 2 | 공공데이터포털 API 키 | **완료 (2026-08-06)** | Phase 2 | 코스피·코스닥 시세 수집 |
| 3 | DART Open API 키 | **완료 (2026-08-06)** | Phase 2 | 국내 재무제표 수집 |
| 4 | Finnhub API 키 | **완료 (2026-08-06)** | ~~Phase 2~~ → v1.1 | 미국 재무. **시세는 무료 불가(403 실측)** |
| 5 | 개인 도메인 | **미진행** | Phase 6 | 고정 HTTPS 주소. OAuth 리다이렉트에도 필요 |
| 6 | Cloudflare 계정 + Tunnel | **미진행** | Phase 6 | 외부 접속 |
| 7 | Sentry 프로젝트 | **미진행** | Phase 6 | 에러 수집·통지 |
| 8 | 알림 채널(메신저) | **미진행** | Phase 6 | 배치 실패·워치리스트 알림의 메신저 채널 |

**2·3·4번은 발급 완료됐다** (2026-08-06). 국내 두 소스는 실제 호출로 검증했고 어댑터가 구현되어 있다(`./gradlew liveTest`). 나머지는 Phase 5~6까지 여유가 있다.

IMPORTANT — **Finnhub 무료 티어는 과거 시세를 주지 않는다**(`/stock/candle` → HTTP 403, 실측). 재무(`/stock/financials-reported`)는 정상이다. 이 때문에 미국 시장은 v1.1로 미뤘고 spec에 반영됐다([ADR-0010](./adr/ADR-0010-defer-us-market.md), [경위](./spec-change-proposal.md)). Finnhub 키는 발급되어 있으므로 v1.1에서 재무에 바로 쓸 수 있다.

---

## 1. Google OAuth 클라이언트

- **어디서**: [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → 사용자 인증 정보
- **무엇을**: OAuth 2.0 클라이언트 ID(웹 애플리케이션 유형)
- **환경변수**: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- **소요**: 약 10분
- **주의**: 승인된 리디렉션 URI에 `{도메인}/login/oauth2/code/google`을 등록해야 한다. 로컬 테스트용으로 `http://localhost:8080/login/oauth2/code/google`도 함께 넣어둔다. 도메인(5번)이 정해진 뒤에 하면 한 번에 끝난다.
- **현재 상태 (2026-08-07)**: 발급 완료. `.env`에 실려 있고 실제 기동으로 확인했다 —
  `GET /oauth2/authorization/google`이 `302 → accounts.google.com`을 내리고 `client_id`가
  자리표시자가 아닌 실제 값, `scope=openid email profile`,
  `redirect_uri=http://localhost:8080/login/oauth2/code/google`였다.
- **도메인(5번)이 정해지면 리디렉션 URI를 추가해야 한다.** 지금은 localhost만 등록되어 있어
  외부 주소로는 로그인이 `redirect_uri_mismatch`로 실패한다.
- **IMPORTANT — 프론트 개발 서버(:5173)로 로그인하려면 URI를 하나 더 등록해야 한다**(2026-08-07).
  `http://localhost:5173/login/oauth2/code/google`이다. Vite 프록시가 Host를 바꾸지 않으므로
  (`changeOrigin: false`, ADR-0018) Spring이 요청 Host로 `redirect_uri`를 만든다 — 실측으로
  :5173에서는 `redirect_uri=http://localhost:5173/...`, :8080에서는 `.../8080/...`이 나갔다.
  등록하지 않으면 개발 서버에서만 `redirect_uri_mismatch`가 난다.
  **빌드된 번들을 `./gradlew bootRun`으로 띄워 `http://localhost:8080`에서 쓰는 경로는
  지금 그대로 동작한다** — Console 작업 없이 로그인을 확인하려면 이쪽을 쓴다.

## 2. 공공데이터포털 API 키

- **어디서**: [공공데이터포털](https://www.data.go.kr/) → 회원가입 → 활용신청
- **무엇을**: 한국거래소 주식시세정보 등 관련 오픈API 활용신청 후 인증키
- **환경변수**: `DATA_GO_KR_SERVICE_KEY`
- **주의**: 활용신청 후 **승인까지 시간이 걸릴 수 있다**(자동승인 API도 있지만 즉시가 아닌 경우가 있다).
- **호출량 (2026-08-06 실측)**: 종목별 기간조회가 되므로 전 기간이어도 **종목당 1콜** — 초기 적재 350종목 = **350콜**, 유니버스는 1콜이다. 분할 실행은 필요 없었다. 당초 "2년치 × 350종목이라 나눠 돌려야 할 수 있다"고 적었으나 일자별 전종목 조회만 된다는 전제가 틀렸다.

## 3. DART Open API 키

- **어디서**: [DART OpenAPI](https://opendart.fss.or.kr/) → 회원가입 → 인증키 신청
- **무엇을**: OpenAPI 인증키
- **환경변수**: `DART_API_KEY`
- **호출량 (2026-08-06 실측)**: 일일 한도 **18,000콜**. 재무 7년 초기 적재가 **9,800콜**로 한도의 절반 남짓이라 하루에 끝났다. 분할은 필요 없었다. 다만 여유가 두 배뿐이라 재적재를 반복하면 걸린다.

## 4. Finnhub API 키

- **어디서**: [Finnhub](https://finnhub.io/) → 회원가입
- **무엇을**: 무료 티어 API 키
- **환경변수**: `FINNHUB_API_KEY`
- **주의**: 무료 티어는 분당 호출 제한이 빡빡하다(60회/분 수준). S&P500 500종목 초기 적재 시 rate limit을 전제로 한 배치 설계가 필요하다.
- **IMPORTANT — 무료 티어가 과거 OHLC(`/stock/candle`)를 더 이상 제공하지 않는다.** Phase 2 조사에서 확인했다. spec §7의 "미국 시세 및 재무는 Finnhub" 전제가 **시세 부분에서 깨졌다.** 결정은 "US 시세만 대체 무료 소스로 교체하고 재무는 Finnhub 유지"이며, 어댑터 구현 시 ADR로 기록하고 spec 수정을 제안한다.
- **키 발급 후 가장 먼저 실측할 것**: 재무 엔드포인트(`/stock/financials-reported`)가 무료 티어에서 여전히 동작하는가. 이것도 막혔다면 미국 종목의 PER·PBR을 계산할 수 없어 범위 재조정이 필요하다.

## 5. 개인 도메인

- **어디서**: 도메인 등록업체(가비아, Cloudflare Registrar 등)
- **무엇을**: 도메인 하나
- **소요**: 구매 즉시~수 시간(DNS 전파)
- **주의**: spec §6이 "개인 도메인을 보유·구매할 의향이 있다"고 기록했다. 6번(Cloudflare)과 같은 곳에서 사면 연동이 단순해진다. 1번(OAuth 리디렉션 URI)이 이 도메인에 의존하므로 **1번보다 먼저** 정하는 것이 낫다.

## 6. Cloudflare 계정 + Tunnel

- **어디서**: [Cloudflare](https://dash.cloudflare.com/) → 회원가입 → Zero Trust → Tunnels
- **무엇을**: 계정, 도메인 등록(네임서버 변경), Tunnel 생성 후 토큰
- **환경변수**: `CLOUDFLARE_TUNNEL_TOKEN`
- **주의**: 도메인(5번)의 네임서버를 Cloudflare로 바꿔야 한다. spec §6이 포트포워딩 없이 외부 접속하기 위해 채택한 방식이다.

## 7. Sentry 프로젝트

- **어디서**: [Sentry](https://sentry.io/) → 회원가입 → 프로젝트 생성(Java/Spring Boot)
- **무엇을**: DSN
- **환경변수**: `SENTRY_DSN`
- **주의**: 무료 티어 이벤트 한도가 있다. spec §6이 자체 호스팅 대신 클라우드 무료 티어를 고른 이유는 맥북에 인프라 부담을 더하지 않기 위해서였다. 프론트엔드 에러도 수집해야 하므로(spec §8) 프로젝트를 백엔드·프론트로 나눌지 하나로 할지는 Phase 6에서 판단한다.

## 8. 알림 채널 (Slack 또는 Discord)

- **어디서**: Slack 워크스페이스 → Incoming Webhook, 또는 Discord 서버 → 채널 웹훅
- **무엇을**: 웹훅 URL
- **환경변수**: `ALERT_WEBHOOK_URL`
- **주의**: spec §8이 "이메일과 메신저 두 채널 모두"를 요구한다. 이메일은 SMTP 설정(Gmail 앱 비밀번호 등)이 별도로 필요하며, 그것도 이 목록에 준하는 수동 작업이다 — 환경변수는 `MAIL_USERNAME`·`MAIL_PASSWORD`를 쓸 예정이다.

---

## 발급 후 할 일

1. `.env.example`에 새 환경변수 이름이 있는지 확인하고, 없으면 플레이스홀더로 추가한다.
2. 실제 값은 `.env`에 넣는다. **이 파일은 gitignore 대상이며 커밋되지 않는다.**
3. `./gradlew checkEnv`로 실렸는지 확인한다. 값은 출력되지 않고 키 이름과 설정 여부만 나온다.
4. 이 문서의 현황 표에서 해당 항목을 `완료 (YYYY-MM-DD)`로 바꾼다.
5. `docs/progress.md`의 "미룬 것" 절에서 해당 항목을 지운다.

## `.env`는 어떻게 애플리케이션에 실리는가

**Spring Boot는 `.env`를 읽지 않는다.** 이 프로젝트에도 dotenv 의존성이 없다. `build.gradle`이
직접 읽어 `bootRun`과 `liveTest`에 주입한다(`loadDotEnv`/`applyDotEnv`).

- **실제 셸 환경변수가 `.env`를 이긴다.** 일회성 오버라이드를 파일 수정 없이 하기 위해서다.
- **MUST NOT: `test` 태스크에 주입하지 않는다.** 실제 키가 있으면 `@ConditionalOnApiKey` 어댑터가
  등록되어 단위 테스트의 빈 구성 자체가 바뀐다. 단위 테스트는 외부 호출 0건이 전제다.
- IMPORTANT — **주입 실패는 조용하다.** `application.yaml`의 자리표시자 기본값(`not-configured`)
  때문에 값이 없어도 애플리케이션은 정상 기동하고 브라우저 로그인만 안 된다. 그래서 "값을 안 적었나"와
  "값이 안 실렸나"가 증상으로 구분되지 않는다. `./gradlew checkEnv`가 그 구분을 담당한다 —
  `.env.example`의 자리표시자를 그대로 둔 경우도 "채워지지 않음"으로 잡는다.

IDE에서 직접 실행하면 이 주입이 걸리지 않는다. `./gradlew bootRun`을 쓰거나 IDE 실행 구성에
환경변수를 따로 넣어야 한다.

IMPORTANT — **`~/Desktop`이 iCloud 동기화 대상이라 원격에서 편집한 `.env`가 즉시 보이지 않는다.**
2026-08-07에 이것으로 한참 헤맸다. 파일은 저장됐고 mtime도 갱신됐는데 내용은 아직 안 내려와,
"편집이 실패했다"는 잘못된 결론에 도달했다(실제로는 약 10분 뒤 반영됐다). **증상 구분법**:
mtime은 바뀌었는데 크기가 예상만큼 안 늘었으면 동기화 대기 중이다. `./gradlew checkEnv`를
몇 분 뒤 다시 돌려보고 판단한다 — 파일이 없다고 단정하기 전에 반드시 재확인한다.
