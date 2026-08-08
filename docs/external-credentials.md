# 외부 서비스 발급 목록

사용자가 직접 발급·인증해야 하는 것들. **1~6번이 완료됐고, 7번은 슬러그 둘, 8번은 통째로 남았다.** 현황은 아래 표가 정본이다.

**MUST: 발급 여부를 이 표에서 읽지 말고 `./gradlew checkEnv`로 확인한다.** 발급은 문서 밖에서 일어나므로 표가 먼저 낡는다 — 실제로 7번이 "미진행"으로 남아 있는 동안 값은 이미 `.env`에 들어 있었고, 그 때문에 한 세션이 "키가 없어 못 한다"고 잘못 판단했다.

에이전트가 대신 할 수 없는 작업만 모았다(계정 생성, 본인 인증, 콘솔에서 키 발급 등). 나중에 한 번에 진행하기 위한 목록이며, 각 항목에 **언제까지 필요한지**와 **없으면 무엇이 막히는지**를 적었다.

- MUST NOT: 발급받은 실제 키·시크릿을 이 문서나 코드·커밋에 적지 않는다. `.env`(gitignore 대상)나 셸 환경변수로만 다룬다. 이 문서에는 환경변수 **이름**만 있다.
- 발급을 마치면 해당 항목의 상태를 `완료`로 바꾸고 발급일을 적는다.

---

## 현황

| # | 항목 | 상태 | 필요 시점 | 없으면 막히는 것 |
|---|---|---|---|---|
| 1 | Google OAuth 클라이언트 | **완료 (2026-08-07 재발급, 도메인 실측)** | Phase 5~6 | 브라우저 로그인. 인가 로직은 이미 동작 |
| 2 | 공공데이터포털 API 키 | **완료 (2026-08-06)** | Phase 2 | 코스피·코스닥 시세 수집 |
| 3 | DART Open API 키 | **완료 (2026-08-06)** | Phase 2 | 국내 재무제표 수집 |
| 4 | Finnhub API 키 | **완료 (2026-08-06)** | ~~Phase 2~~ → v1.1 | 미국 재무. **시세는 무료 불가(403 실측)** |
| 5 | 개인 도메인 (`na1011.net`) | **완료 (2026-08-07 확인)** | Phase 6 | 고정 HTTPS 주소. OAuth 리다이렉트에도 필요 |
| 6 | Cloudflare 계정 + Tunnel | **완료 (2026-08-07, `200` 실측)** | Phase 6 | 외부 접속 |
| 7 | Sentry 프로젝트 | **완료 (2026-08-09 SDK 연결됨) — 슬러그 둘 미입력** | Phase 6 | 이벤트는 나간다. `SENTRY_ORG`·`SENTRY_PROJECT_FRONTEND`가 없어 프론트 소스맵만 안 올라간다 |
| 8 | 알림 채널(메신저·메일) | **미진행 — 코드는 붙어 있다** | Phase 6 | 알림이 로그로만 나간다. 값만 채우면 살아난다(ADR-0025) |

**2·3·4번은 발급 완료됐다** (2026-08-06). 국내 두 소스는 실제 호출로 검증했고 어댑터가 구현되어 있다(`./gradlew liveTest`). 나머지는 Phase 5~6까지 여유가 있다.

IMPORTANT — **Finnhub 무료 티어는 과거 시세를 주지 않는다**(`/stock/candle` → HTTP 403, 실측). 재무(`/stock/financials-reported`)는 정상이다. 이 때문에 미국 시장은 v1.1로 미뤘고 spec에 반영됐다([ADR-0010](./adr/ADR-0010-defer-us-market.md), [경위](./spec-change-proposal.md)). Finnhub 키는 발급되어 있으므로 v1.1에서 재무에 바로 쓸 수 있다.

---

## 1. Google OAuth 클라이언트

- **어디서**: [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → 사용자 인증 정보
- **무엇을**: OAuth 2.0 클라이언트 ID(웹 애플리케이션 유형)
- **환경변수**: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- **소요**: 약 10분
- **주의**: 승인된 리디렉션 URI에 `https://screener.na1011.net/login/oauth2/code/google`을 등록해야 한다(5번에서 확정된 주소다). 로컬 테스트용 `http://localhost:8080/login/oauth2/code/google`은 지우지 않고 함께 둔다.
- **현재 상태 (2026-08-07)**: `웹 애플리케이션` 유형으로 재발급 완료. **도메인에서 실측 확인했다** —
  `https://screener.na1011.net/oauth2/authorization/google`이 리디렉션 끝에
  `accounts.google.com/v3/signin/identifier`(실제 로그인 화면)로 도착하고
  `redirect_uri=https://screener.na1011.net/...`이 그대로 통과한다.
- **IMPORTANT — 처음에는 `데스크톱` 유형으로 발급해 한 번 갈아엎었다. 재발급할 일이 생기면 유형부터 본다.**
  데스크톱 유형에는 **승인된 리디렉션 URI 입력란이 아예 없고, 유형은 생성 후 변경할 수 없다.**
  Google이 그 유형에 허용하는 것은 loopback(`http://localhost:포트`)뿐이다.
  **loopback은 등록 없이 통과하므로 localhost 로그인은 멀쩡했고, 도메인에서만 깨졌다:**
  ```
  http://localhost:8080/login/oauth2/code/google       → Google 로그인 화면 (200)
  https://screener.na1011.net/login/oauth2/code/google → Error 400: redirect_uri_mismatch
  ```
- **IMPORTANT — 그래서 OAuth 확인은 리디렉션을 끝까지 따라가야 한다.** 처음 발급 때의 확인은
  `302 → accounts.google.com`과 `redirect_uri` 값까지만 봤다. 그것은 **Spring이 만드는 요청**만
  본 것이고 **Google이 그 요청을 받아주는지**는 보지 않은 것이라, 위 결함을 그대로 통과시켰다.
  `curl -sL`로 최종 URL이 `signin/oauth/error`인지 `signin/identifier`인지 보면 갈린다.
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

- **무엇을**: **`na1011.net`** — 이 앱의 주소는 서브도메인 **`screener.na1011.net`**이다(2026-08-08 결정)
- **어디서**: **Cloudflare Registrar**([domains.cloudflare.com](https://domains.cloudflare.com/)), 연 $11.86
- **소요**: 구매 즉시
- **등록처를 Cloudflare로 정한 근거**: 6번(Cloudflare Tunnel)이 spec §6에서 이미 확정된 외부 접속 경로다. 같은 곳에서 등록하면 zone이 자동 생성되고 네임서버가 처음부터 Cloudflare라 **네임서버 변경과 전파 대기(수 시간~48시간)가 사라진다.** 등록 대행 마진도 없다(도매 원가 판매).
- **프로젝트 도메인이 아니라 우산 도메인을 산 근거**: 서브도메인은 개수 제한도 추가 비용도 없고 Tunnel이 호스트명별로 다른 로컬 포트에 붙는다. 다음 프로젝트는 Public Hostname만 추가하면 되므로 갱신비가 프로젝트 수만큼 늘지 않는다. 무엇보다 **프로젝트 범위가 바뀌어도 주소가 죽지 않는다** — 이 스크리너는 v1.1에 미국 시장이 붙으므로 `jongmok`(종목) 같은 이름을 샀다면 이미 어긋났다.
- **이름·TLD 근거** (후보 조회는 2026-08-08 RDAP):
  - `na1011`은 **GitHub 사용자명과 철자가 같다.** 포트폴리오 주소와 저장소 주소가 어긋나지 않는다.
  - **`.com`은 쓸 수 없다.** `na1011.com`이 2026-04-27에 등록됐다(A 레코드 없이 보유만 하고 있다). 등록업체가 제시하는 `na-1011.com`은 **하이픈이 부채다** — 구두 전달 때마다 설명이 필요하고 빠뜨린 오타가 남의 도메인으로 간다.
  - **`.dev`를 버린 이유는 인상이다.** 용도 제한이 있는 TLD는 아니지만(Google Registry의 범용 gTLD) 비개발자에게 개발·테스트 사이트로 읽힌다. `.co`는 인상이 낫지만 연 $25 수준이라 차액만큼의 이득이 없었다.
- **주의**: 등록 직후 오는 **ICANN 이메일 인증을 15일 안에** 처리해야 한다. 방치하면 도메인이 정지되고 사이트 전체가 죽는다.
- **주의**: `.kr`·`.co.kr`은 Cloudflare Registrar가 지원하지 않는다. 나중에 한국 ccTLD를 원하게 되면 등록처를 나눠야 한다.
- 1번(OAuth 리디렉션 URI)이 이 도메인에 의존하므로 도메인이 먼저다.

## 6. Cloudflare 계정 + Tunnel

- **어디서**: [Cloudflare](https://dash.cloudflare.com/) → Zero Trust(Cloudflare One) → **Networks → Tunnels** (구 UI는 `Access → Tunnels`)
- **무엇을**: 계정, Tunnel 생성 후 커넥터 토큰. 5번을 Cloudflare Registrar에서 사면 **네임서버 변경 단계는 없다.**
- **환경변수**: `CLOUDFLARE_TUNNEL_TOKEN`
- **완료 (2026-08-07)**: `curl -I https://screener.na1011.net/`가 `200`을 낸다. 절차와 함정은 [`TODO.md`](../TODO.md) §3 ②에 있다.
- **IMPORTANT — 라우트를 좌측 메뉴 `Networks → Routes`에서 만들면 안 된다.** 그 화면은 **CIDR 전용**(사설망, 접속 측에 WARP 클라이언트 필요)이라 공개 주소가 생기지 않는데, 만들어도 오류 없이 저장된다. 공개 주소는 **터널 상세 → `Routes` 탭 → `Add route` → `Published application`**이다. 구분 신호는 입력란이다 — CIDR 칸이 보이면 잘못 들어온 것이고, `Subdomain`/`Domain`/`Service URL`이 보이면 맞다.
- **IMPORTANT — `CLOUDFLARE_TUNNEL_TOKEN`을 Spring 애플리케이션은 읽지 않는다.** `sudo cloudflared service install <TOKEN>`이 `launchd` 데몬에 직접 주입하며, 토큰을 쓰는 주체는 그 프로세스다. `.env`에 적는 것은 재설치·재발급 때 다시 찾지 않기 위한 기록이다.
- **`server.forward-headers-strategy: framework`는 이 터널 때문에 들어갔다**(2026-08-07 적용). cloudflared가 로컬에 평문 HTTP로 붙어 원래 스킴이 `X-Forwarded-Proto`에만 남는다. 기본값 `NONE`이면 Spring이 `redirect_uri`를 `http://screener.na1011.net/...`으로 만들어 Google이 `redirect_uri_mismatch`를 낸다 — 호스트는 맞고 스킴만 달라 화면만 봐서는 원인을 못 찾는다(ADR-0018). **터널을 걷어내지 않는 한 되돌리지 않는다.**

## 7. Sentry 프로젝트

- **어디서**: [Sentry](https://sentry.io/) → 회원가입 → **프로젝트 둘**(Java/Spring Boot, JavaScript/Vue)
- **무엇을**: DSN 두 개 + 소스맵 업로드용 org auth token
- **환경변수**: `SENTRY_DSN`(백엔드) · `VITE_SENTRY_DSN`(프론트) · `SENTRY_AUTH_TOKEN`(조직 전체에 하나)
- **DSN은 프로젝트마다 다르지만 auth token은 조직 하나다.** 조직 토큰은 그 조직의 모든 프로젝트에 접근하므로 플랫폼별로 나눌 필요가 없다. 발급 경로는 `Settings` → **`Developer Settings`** → `Organization Tokens`이며, `Settings` 바로 밑에는 없다.
- **프로젝트를 나눈 근거**: 이슈 그룹핑·릴리스·소스맵 처리가 플랫폼별로 갈린다. 무료 티어의 제약은 프로젝트 수가 아니라 **월 이벤트 총량**이라 나눠도 비용이 같다.
- **IMPORTANT — DSN과 auth token은 성격이 정반대다.** DSN은 이벤트를 보내는 **공개** 식별자라 번들에 박혀도 정상이고, auth token은 Sentry API에 쓰는 **시크릿**이다. **`VITE_` 접두어를 붙이면 번들에 실려 공개된다.**
- **주의**: 무료 티어 이벤트 한도가 있다. spec §6이 자체 호스팅 대신 클라우드 무료 티어를 고른 이유는 맥북에 인프라 부담을 더하지 않기 위해서였다.
- **완료 (2026-08-08 실측)**: `./gradlew checkEnv`가 세 키를 모두 `설정됨`으로 낸다(자리표시자가 아니다).
- **SDK가 붙었다 (2026-08-09).** 백엔드는 `sentry-spring-boot-4-starter`, 프론트는 `@sentry/vue`다. **아티팩트 이름이 넷이고 Boot 4용은 `-4-starter` 하나뿐이다** — Boot 3용을 고르면 앱이 기동하지 못한다(ADR-0030).
- **남은 것은 org·project 슬러그 둘뿐이다.** DSN이 담고 있는 것은 숫자 ID뿐이라 슬러그를 유도할 수 없다. `SENTRY_ORG`·`SENTRY_PROJECT_FRONTEND`를 `.env`에 채우면 다음 빌드부터 소스맵이 올라간다. 백엔드는 이것이 필요 없다 — 소스맵이라는 개념이 없고 바이트코드에 줄 번호가 들어 있다.
- **DSN이 살아 있는 프로젝트를 가리키는가는 대시보드로만 갈린다.** `./gradlew liveTest --tests "*SentryLiveTest*"`가 이벤트 1건을 보내지만, 확인되는 것은 "거부되지 않았다"까지다.

## 8. 알림 채널 (Slack 또는 Discord)

- **어디서**: Slack 워크스페이스 → Incoming Webhook, 또는 Discord 서버 → 채널 웹훅
- **무엇을**: 웹훅 URL
- **환경변수**: `ALERT_WEBHOOK_URL`
- **주의**: spec §8이 "이메일과 메신저 두 채널 모두"를 요구한다. 이메일은 SMTP 설정(Gmail 앱 비밀번호)이 별도로 필요하며 환경변수는 `MAIL_USERNAME`·`MAIL_PASSWORD`다. 선택으로 `ALERT_OPERATOR_EMAIL`(비우면 `MAIL_USERNAME`으로 간다).
- **IMPORTANT — 7번과 달리 코드는 이미 있다**(ADR-0025). 값을 채우면 코드 변경 없이 살아나고, 비어 있으면 채널이 비활성이라 알림이 로그로만 떨어진다. 기동 로그의 `알림 채널이 하나도 구성되지 않아…` / `알림 채널 2개를 구성했다: [webhook, mail]`이 그 상태의 유일한 표시다.
- **IMPORTANT — Discord를 고르면 URL 끝에 `/slack`을 붙여 넣는다.** 구현이 Slack 형식(`{"text": …}`) 하나만 보내므로, 붙이지 않으면 Discord가 400을 내고 그 실패는 "채널에 아무것도 안 뜬다"로만 보인다.

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
