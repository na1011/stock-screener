# 사람이 해야 할 일

**에이전트가 원리적으로 대신할 수 없는 것만 모았다** — 남의 계정 자격증명, 외부 서비스 발급·본인 인증, `.env`(훅이 막는다).

**브라우저 조작은 여기 없다.** 에이전트가 크롬을 원격 제어하고, `./gradlew bootRun`도 에이전트가 띄운다. Google 로그인만 당신이 해 주면 나머지는 이어서 돌아간다.

진행 상태의 정본은 [`docs/progress.md`](docs/progress.md), 외부 자격증명의 정본은 [`docs/external-credentials.md`](docs/external-credentials.md)다. 여기는 **지금 당신이 손을 대야 하는 것만** 뽑아 온 목록이다. 끝낸 항목은 정본 문서를 갱신하고 여기서 지운다.

---

## 1. 시스템·콘솔 설정

- [ ] **(선택) 프론트 개발 서버로 로그인하려면 리디렉션 URI를 하나 더 등록한다.**
  [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → 사용자 인증 정보 → 해당 OAuth 클라이언트 → 승인된 리디렉션 URI에 추가:

  ```
  http://localhost:5173/login/oauth2/code/google
  ```

  Vite 프록시가 Host를 바꾸지 않아 Spring이 요청 Host로 `redirect_uri`를 만든다(ADR-0018, 실측 확인). 등록하지 않으면 `npm run dev`에서만 `redirect_uri_mismatch`가 난다. **`./gradlew bootRun`으로 `:8080`에서 쓰는 경로는 실측으로 동작하므로**(2026-08-09 관통 검증), 이 작업은 `npm run dev`를 쓸 때만 필요하다.

- [ ] **(권장) 저장소를 `~/Desktop` 밖으로 옮긴다.** 이유가 둘로 늘었다.
  - `build/`의 클래스 파일이 iCloud 때문에 `X 2.class`로 복제되면 테스트가 `wrong name` 오류로 실패하고 Gradle이 파일 해싱에서 수 분간 멈춘다. `./gradlew clean`이 처방이고 `checkDuplicateFiles`가 먼저 잡아 주지만, 원인을 없애는 편이 낫다.
  - **`~/Desktop`은 macOS가 TCC로 보호해 launchd가 띄운 프로세스가 읽지 못한다.** 그래서 지금은 실행 산출물과 `.env` **사본**을 `~/Library/Application Support/stock-screener`로 내보내 돌린다(ADR-0033). 저장소를 옮기면 그 `.env` 사본을 없앨 수 있다.

- [ ] **(권장) 뚜껑을 닫아 둔 채 쓰려면 절전을 끈다.** `run-app.sh`가 `caffeinate -s`로 감싸지만 그것은 **전원 연결 시 유휴 절전**만 막는다. 뚜껑을 닫으면 그대로 잠들고 사이트도 함께 내려간다.
  ```bash
  sudo pmset -a disablesleep 1     # 되돌리기: sudo pmset -a disablesleep 0
  ```

- [ ] **재부팅 후 앱과 터널이 돌아오는지 한 번 본다.** FileVault가 켜져 있어 **콜드 부팅에는 암호가 한 번 필요하다**(에이전트가 대신할 수 없다). 암호를 넣은 뒤 다른 기기에서:
  ```bash
  curl -s https://screener.na1011.net/actuator/health     # {"status":"UP"}
  ```
  계획된 재부팅을 암호 없이 넘기려면 `sudo fdesetup authrestart`를 쓴다. 자세한 것은 [`ops/README.md`](ops/README.md)에 있다.

---

## 2. 외부 서비스 발급

**남은 것은 ①의 미완 두 줄, ④의 슬러그 두 개, ⑤ 알림 채널이다.** ①②③(도메인·Tunnel·OAuth)은 끝났고 ④ Sentry는 키와 SDK가 모두 붙었다. 아래 완료 표시된 절차는 **재발급 때 다시 밟을 기록**이다. 도메인·TLD 근거는 [`docs/external-credentials.md`](docs/external-credentials.md)에 있다 — 여기 옮겨 적지 않는다.

> **상태를 문서에서 읽지 말고 `./gradlew checkEnv`로 확인한다.** 이 파일이 ④를 "미진행"으로 달고 있는 동안 실제로는 값이 다 들어 있었다. 발급은 문서 밖에서 일어나므로 문서가 먼저 낡는다.

> **🔴 기한이 있는 것 하나 — ICANN 이메일 인증(아래 1-3).** 도메인 등록이 2026-08-07이므로 **2026-08-22까지**다. 놓치면 도메인이 정지되고 `screener.na1011.net`과 OAuth 리디렉션이 함께 죽는다. **터미널로는 확인할 수 없다** — 인증 전에도 `dig`는 정상으로 보이고 정지는 15일 뒤에 조용히 온다.

**⑤가 지금 유일하게 막고 있는 발급이다** — 알림 채널 코드는 붙어 있으나(ADR-0025) 값이 없어 비활성이라 배치 실패와 워치리스트 알림이 **로그로만 떨어진다**. 값을 채우면 코드 변경 없이 살아난다.

**`.env`에 값을 넣는 것도 당신 몫이다.** 에이전트는 그 파일을 읽지도 쓰지도 못한다(`guard-secrets.sh`가 막는다). 넣은 뒤 실렸는지는 `./gradlew checkEnv`가 답한다 — 값은 출력하지 않고 키 이름과 설정 여부만 낸다.

**돈이 드는 것은 도메인 하나뿐이다.** Tunnel·Sentry 무료 티어·웹훅·Gmail SMTP는 이 규모에서 전부 0원이다.

---

### ① Cloudflare 계정 + 도메인 등록 → (환경변수 없음)

- [x] **1-1. 계정 생성** — [dash.cloudflare.com](https://dash.cloudflare.com/) 회원가입. 이메일 인증까지 마친다.
- [x] **1-2. `na1011.net` 구매** — [domains.cloudflare.com](https://domains.cloudflare.com/) 또는 대시보드 좌측 `Domain Registration` → `Register Domains`. 2026-08-08 기준 연 $11.86.
- [ ] **1-3. ICANN 이메일 인증 메일을 반드시 연다.** 등록 직후 오는 확인 메일을 **15일 안에** 클릭하지 않으면 도메인이 정지되고 사이트 전체가 죽는다. 스팸함까지 확인한다. **터미널로는 확인할 수 없다** — 인증 전에도 `dig`는 정상으로 보이고, 정지는 15일 뒤에 조용히 온다.
- [ ] **1-4. 자동 갱신이 켜져 있는지 확인한다.** 만료되면 OAuth 리디렉션까지 한꺼번에 끊긴다.
- [x] **1-5. 네임서버 확인** — 2026-08-07 확인됨(`khalid`·`jillian.ns.cloudflare.com`). 재확인은:
  ```bash
  dig NS na1011.net +short     # *.ns.cloudflare.com 두 개가 나오면 완료
  ```

WHOIS 개인정보 대행은 Cloudflare가 기본 제공한다. 별도 결제 항목이 아니다.

**없으면 막히는 것**: 고정 HTTPS 주소 — 외부 접속과 OAuth 리디렉션이 모두 여기 의존한다.

---

### ② Cloudflare Tunnel → `CLOUDFLARE_TUNNEL_TOKEN`

포트포워딩 없이 맥북의 `:8080`을 외부 HTTPS 주소에 붙이는 경로다(spec §6).

- [x] **2-1. 커넥터 설치**
  ```bash
  brew install cloudflared
  ```
- [x] **2-2. 대시보드에서 터널 생성** — Cloudflare 대시보드 → **Zero Trust**(현재 이름은 Cloudflare One) → **Networks**(구 `Networking`) **→ Tunnels** → `Create a tunnel` → 커넥터로 **Cloudflared** 선택 → 이름 입력(예: `stock-screener-macbook`) → `Save`.
  더 구버전 UI에서는 `Access → Tunnels`에 있다. 셋 다 같은 화면이다.
- [x] **2-3. 토큰으로 서비스 설치** — 생성 직후 화면에 OS별 설치 명령이 나온다. macOS 탭의 명령을 그대로 붙여넣으면 되고, 형태는 이렇다:
  ```bash
  sudo cloudflared service install <TOKEN>
  ```
  `launchd` 데몬(`/Library/LaunchDaemons/com.cloudflare.cloudflared.plist`)으로 등록되어 **재부팅 후에도 자동 기동**한다.

  **지우는 대상은 데몬이 아니라 셸 히스토리의 그 한 줄이다.** 데몬은 남겨야 터널이 돈다. 위 명령은 **토큰 원문을 명령줄에 담고**, `~/.zsh_history`는 권한만 `600`일 뿐 평문이라 나중에 아무 도구나 그 줄을 읽는다. 남은 위험은 히스토리 파일 하나뿐이므로 그것만 지운다. **처리 완료(2026-08-07)** — 아래는 재설치·재발급 때 다시 밟을 절차다.

  ```bash
  grep -an "cloudflared service install" ~/.zsh_history        # ① 남았는지
  cp ~/.zsh_history ~/.zsh_history.bak                         # ② 백업
  LC_ALL=C sed -i '' '/cloudflared service install/d' ~/.zsh_history   # ③ 그 줄만 제거
  grep -ac "cloudflared service install" ~/.zsh_history        # ④ 0 이어야 한다
  ```
  확인이 끝나면 `~/.zsh_history.bak`도 지운다 — **거기에 토큰이 그대로 남아 있다.**

  세 군데서 걸린다. 전부 실측으로 확인했다(2026-08-07):

  - **MUST: `grep`에 `-a`를 붙인다.** `~/.zsh_history`에는 비ASCII 바이트가 섞여 있어 `grep`이 파일을 **바이너리로 판단하고 매치를 출력하지 않는다.** 실패가 아니라 침묵이라 **"안 남았다"로 잘못 읽힌다** — 실제로 이 세션에서 `-a` 없이 돌려 "없음"으로 오판했다.
  - **`history -d <번호>`는 듣지 않는다.** `grep -n`이 주는 것은 **파일의 줄 번호**이고 `history -d`가 받는 것은 **히스토리 이벤트 번호**라 서로 다른 체계다. `fc: no such event` 가 그래서 난다. 게다가 다른 터미널에서 실행한 명령은 지금 셸의 메모리 히스토리에 아예 없다. 그래서 파일을 직접 고친다.
  - **macOS `sed`에는 `LC_ALL=C`가 필요하다.** 없으면 위의 비ASCII 바이트에서 `RE error: illegal byte sequence`로 죽는다.
  - **MUST NOT: 파일을 통째로 비우지 않는다.** 다른 히스토리까지 사라지고 되돌릴 수 없다.

  **지금 안 나와도 끝난 게 아니다.** zsh는 기본 설정에서 히스토리를 **셸이 끝날 때** 파일에 쓴다. 명령을 실행한 터미널이 살아 있으면 파일에는 없고 메모리에만 있다. 그 창을 닫은 **뒤에** ①을 한 번 더 돌린다.

  **`ps`에는 토큰이 안 보인다.** cloudflared 2026.7 기준 설치 시 토큰이 `/Library/Application Support/com.cloudflare.cloudflared/token`(root 전용)에 저장되고 데몬은 `--token-file`로 읽는다. 예전 버전은 `--token <원문>`이 프로세스 목록에 그대로 떴다.
- [x] **2-4. 대시보드에서 `Connected` 상태를 확인한다.** 몇 초 내에 커넥터가 초록으로 바뀐다. 새 UI는 터널 목록의 상태를 `Healthy`로 표시한다.
- [x] **2-5. 공개 호스트명 연결** (2026-08-07 완료)

  **IMPORTANT — 좌측 메뉴의 `Networks`(또는 `Networking`) → `Routes`로 들어가면 안 된다.** 그 화면의 `Create route` / `Add a route`는 **CIDR 전용**이고, 입력란이 `10.0.0.0/8` 같은 사설 IP 대역을 요구한다. 화면 상단에 *"requires traffic to pass via Cloudflare Gateway. Users need the Cloudflare One Client"* 라고 쓰여 있는 것이 그 증거다 — **접속하는 쪽에 WARP 클라이언트를 깔아야 열리는 사내망 경로**이고, 우리가 원하는 "브라우저 주소창에 치면 열리는 공개 HTTPS 주소"와는 다른 물건이다. 여기서 만들면 지인은 아무도 못 들어온다.

  들어갈 곳은 **터널 하나의 상세 화면**이다:

  `Networks` → `Tunnels` → **터널 이름(`stock-screener-macbook`) 클릭** → `Routes` 탭 → `Add route` → 종류로 **`Published application`** 선택

  종류를 고르는 단계가 핵심이다. 같은 `Add route` 버튼이 `Published application`과 `Private network(CIDR)` 둘 다로 갈라진다. `Published application`을 고르면 입력란이 아래처럼 바뀐다 — **CIDR 칸이 보이면 잘못 들어온 것이다.**

  | 항목 | 값 |
  |---|---|
  | Subdomain | `screener` |
  | Domain | `na1011.net` (드롭다운에서 선택) |
  | Path | 비워 둔다 |
  | Type / Service | `HTTP` |
  | URL | `localhost:8080` |

  **Type은 `HTTPS`가 아니라 `HTTP`다.** 헷갈리는 지점이라 구간을 그려 둔다:
  ```
  브라우저 ──HTTPS──▶ Cloudflare 엣지 ──암호화 터널──▶ cloudflared ──HTTP──▶ Spring :8080
                      ↑ 인증서는 여기서 끝남                          ↑ 이 칸이 가리키는 구간
  ```
  여기 적는 값은 **맥북 안에서 cloudflared가 Spring에 붙는 방법**이다. 브라우저가 보는 주소가 `https://`인 것과 모순이 아니다 — 다른 구간이다. `HTTPS`로 잡으면 cloudflared가 `https://localhost:8080`에 TLS 핸드셰이크를 시도하는데 Spring은 평문으로만 듣고 인증서도 없어 502가 난다.
  같은 이유로 Spring은 자기가 평문 HTTP를 받았다고 보고, 원래 스킴은 `X-Forwarded-Proto`에만 남는다. ③ 3-2가 그것을 믿게 하는 설정이다.

  **DNS는 따로 만들 것이 없다.** 저장하면 Cloudflare가 `screener` CNAME(`<터널UUID>.cfargotunnel.com`)을 자동으로 넣는다. DNS 화면에서 손으로 만들면 오히려 충돌한다.
- [x] **2-6. 확인** — `./gradlew bootRun`을 띄운 상태에서 **2026-08-07 `200` 실측 완료**:
  ```bash
  curl -I https://screener.na1011.net/             # 200 + 로그인 셸 HTML
  curl -I https://screener.na1011.net/api/screens  # 401 (익명이므로 정상)
  ```
  **502가 나면 앱이 안 떠 있거나 Type을 `HTTPS`로 잡은 것이다.** 502는 "터널은 붙었는데 로컬 서비스에 못 닿았다"는 뜻이라, 터널 상태가 `Healthy`인 것과 모순되지 않는다.

  **MUST NOT: `dig`로 라우트 성공을 판정하지 않는다.** 프록시(주황 구름)가 켜져 있으면 CNAME이 flatten되어 **Cloudflare 대역 A 레코드**(`104.21.x` / `172.67.x`)가 나온다 — `cfargotunnel.com`은 보이지 않는다. 실측 결과가 그랬다. 판정은 위 `curl`로 한다.

**알아둘 것**

- **`CLOUDFLARE_TUNNEL_TOKEN`은 Spring 애플리케이션이 읽지 않는다.** 토큰을 쓰는 주체는 `launchd`에 등록된 `cloudflared` 프로세스이고, 2-3에서 이미 주입이 끝난다. `.env`에 적는 것은 **재설치·재발급 때 다시 찾지 않기 위한 기록**이다. `./gradlew checkEnv`도 키가 채워졌는지만 본다.
- **맥북이 잠자면 터널도 끊긴다.** 상시 접속을 원하면 절전 설정을 바꾸거나 `caffeinate`가 필요하다. Phase 6의 "자동 기동" 과제에 포함된다.
- 터널을 지우고 다시 만들면 토큰이 바뀐다. 그때는 `sudo cloudflared service uninstall` 후 2-3을 다시 한다.

**없으면 막히는 것**: 홈 네트워크 밖에서의 접속 전부.

---

### ③ 도메인이 붙은 뒤 OAuth·애플리케이션 설정 → (환경변수 없음)

발급이 아니라 **설정 작업 두 가지**다. 둘 중 하나만 하면 로그인이 안 된다.

- [x] **3-1. OAuth 클라이언트 `웹 애플리케이션` 재발급 — 완료.** 2026-08-08 실측으로 확인했다:
  ```bash
  LOC=$(curl -s -D- -o /dev/null https://screener.na1011.net/oauth2/authorization/google | grep -i '^location:' | sed 's/^[Ll]ocation: //' | tr -d '\r')
  curl -sL -o /dev/null -w '%{url_effective}\n' "$LOC"
  # → accounts.google.com/v3/signin/identifier … redirect_uri=https://screener.na1011.net/...
  ```
  `signin/oauth/error`가 아니라 **실제 로그인 화면**으로 끝나고 `redirect_uri`가 https로 통과한다. 경위와 함정(데스크톱 유형에는 리디렉션 URI 칸이 없고 유형은 변경 불가, loopback은 등록 없이 통과해 localhost에서만 멀쩡했던 것)은 [`external-credentials.md`](docs/external-credentials.md) 1번에 있다.

- [x] **3-2. 프록시 헤더 신뢰 설정 — 적용됨(2026-08-07).**
  `application.yaml`에 `server.forward-headers-strategy: framework`가 들어갔다. 없었다면 Spring이 `X-Forwarded-Proto`를 무시해(기본값 `NONE`) `redirect_uri`가 이렇게 나갔을 것이다:
  ```
  http://screener.na1011.net/login/oauth2/code/google     ← 스킴이 http
  ```
  등록한 것은 `https://…`이므로 Google은 `redirect_uri_mismatch`를 낸다. **호스트는 맞고 스킴만 다르기 때문에 화면만 봐서는 원인을 못 찾는다**(ADR-0018).

  **남은 검증은 2-6 이후다** — 헤더는 실제 터널을 타야 붙으므로 localhost 테스트로는 판정되지 않는다(`SecurityConfigTest`·`SpaFallbackTest`는 통과한다. 헤더가 없으면 분기 자체를 안 탄다). 도메인으로 로그인이 되면 그때 함께 볼 것:
  ```bash
  curl -sI https://screener.na1011.net/api/screens | grep -i set-cookie   # Secure 플래그 확인
  ```

**없으면 막히는 것**: 외부 주소에서의 로그인 전부. 화면은 뜨는데 로그인만 실패한다.

---

### ④ Sentry 프로젝트 → `SENTRY_DSN` · `VITE_SENTRY_DSN` · `SENTRY_AUTH_TOKEN`

**완료 (2026-08-08 `./gradlew checkEnv` 실측).** 세 키가 전부 `설정됨`이고 자리표시자가 아니다.
아래는 재발급 때 다시 밟을 기록이다. **남은 것은 코드 쪽뿐이다** — SDK 도입과 소스맵 업로드
배선은 에이전트 작업이라 여기 있지 않다.

- [x] **4-1.** [sentry.io](https://sentry.io/) 가입 → 조직 생성.
- [x] **4-2. 프로젝트를 둘 만든다** — 플랫폼 `Java / Spring Boot` 하나, `JavaScript / Vue` 하나.
  **하나로 합치지 않는 이유**: 이슈 그룹핑·릴리스·소스맵 처리가 플랫폼별로 갈려서, 섞으면 프론트 스택트레이스가 minify된 채로 남는다. 무료 티어의 제약은 프로젝트 수가 아니라 **월 이벤트 총량**이라 나눠도 비용이 늘지 않는다.
- [x] **4-3. 생성 중 나오는 `Set your alert frequency`는 기본값(`Alert me on high priority issues` + `Notify via email`)으로 둔다.** ⑤에서 알림을 직접 구현하지만 **잡는 대상이 다르다** — 자체 알림은 배치 실패·워치리스트처럼 **코드가 예상한 사건**이고, Sentry는 예상 못 한 예외·프론트 JS 오류, 그리고 **알림 코드 자체가 죽은 경우**를 잡는다. 마지막 것 때문에 꺼서는 안 된다. 자체 알림만 두면 그게 고장 난 사실을 알려줄 경로가 없다.
  `Connect to messaging`은 **지금 누르지 않는다.** ⑤에서 채널을 정한 뒤 붙이는 편이 순서에 맞고, Sentry 설정에서 나중에 언제든 추가된다.
- [x] **4-4.** 각 프로젝트의 DSN을 복사한다. 백엔드 것이 `SENTRY_DSN`이다.
- [x] **4-5. DSN 두 개를 `.env`에 넣는다.** 키 이름은 `.env.example`에 이미 있다(2026-08-07 추가) — 백엔드가 `SENTRY_DSN`, 프론트가 `VITE_SENTRY_DSN`이다. 넣은 뒤 `./gradlew checkEnv`가 두 키를 `설정됨`으로 내면 된다. **안 넣으면 "`.env.example`에 있으나 `.env`에 없는 키" 목록에 뜬다.**
  프론트 DSN이 번들에 그대로 박혀 공개되는 것은 **정상이다** — Sentry DSN은 쓰기 전용 공개 식별자다. 시크릿으로 착각해 숨기려다 빌드를 꼬지 않는다.
- [x] **4-6. 소스맵 업로드용 `SENTRY_AUTH_TOKEN`을 발급한다 — 조직 전체에 하나만 만든다.**

  `Settings` → **`Developer Settings`** → **`Organization Tokens`** → `Create New Token`
  메뉴를 못 찾으면 주소로 바로 간다: `https://sentry.io/orgredirect/organizations/:orgslug/settings/auth-tokens/`
  (`Settings` 바로 밑에는 없다. `Developer Settings` 안으로 한 단계 더 들어간다.)

  **MUST: DSN처럼 프로젝트별로 만들지 않는다.** 조직 토큰은 **그 조직의 모든 프로젝트에 접근**하므로 Spring·Vue 구분 없이 하나면 된다. 업로드 대상은 토큰이 아니라 빌드 설정의 `org`·`project`가 정한다. **DSN은 프로젝트마다 다른데 이 토큰은 조직 하나** — 이 비대칭이 헷갈리는 지점이다.
  스코프는 생성 시 소스맵 업로드·릴리스 생성에 맞춰 자동으로 붙는다. 개인 토큰(`계정 드롭다운` → `Personal Tokens`)을 쓴다면 `Project: Read & Write` + `Release: Admin`을 직접 골라야 한다.

  **DSN과 성격이 정반대다.** DSN은 이벤트를 보내는 **공개** 식별자고, 이 토큰은 Sentry API에 쓰는 **시크릿**이다. **MUST NOT: `VITE_` 접두어를 붙이지 않는다** — 붙이는 순간 번들에 실려 공개된다.
  **없으면 프론트 이슈가 `index-a3f9.js:1:28471`처럼만 남는다.** 프로젝트를 둘로 나눈 것(4-2)은 이슈가 섞이는 것을 막을 뿐, 소스맵을 올리지 않으면 스택트레이스는 여전히 못 읽는다. 백엔드는 이 토큰이 필요 없다 — 소스맵이라는 개념이 없고 바이트코드에 줄 번호가 들어 있다.
**SDK는 붙었다 (2026-08-09).** 백엔드·프론트 모두 이벤트를 보내고, DSN이 번들에 실린 것까지 확인했다. 남은 것은 아래 둘뿐이다.

- [ ] **백엔드 이벤트가 실제로 대시보드에 뜨는지 본다.** `./gradlew liveTest --tests "*SentryLiveTest*"`가 이벤트 1건을 보내고 "거부되지 않았다"까지 확인한다. **그 이벤트가 정말 당신 프로젝트에 도착했는가는 대시보드를 봐야만 갈린다.** 메시지는 `[live] stock-screener 관통테스트…`이고 환경은 `live-test`다.

- [ ] **프론트 소스맵을 올리려면 Sentry의 org·project **슬러그**가 필요하다.** DSN에는 숫자 ID만 들어 있어 유도할 수 없다. Sentry 대시보드 주소에 보이는 문자열이며 **시크릿이 아니라 조회값**이다. `.env`에 넣는 것은 당신만 할 수 있다:
  ```
  SENTRY_ORG=<대시보드 주소의 조직 슬러그>
  SENTRY_PROJECT_FRONTEND=<Vue 프로젝트 슬러그>
  ```
  넣으면 다음 `./gradlew build`부터 자동으로 올라간다. 그 전까지는 빌드 로그에 `[sentry] … 소스맵을 올리지 않는다`가 뜨고 스택트레이스가 `index-a3f9.js:1:28471`처럼 남는다. **틀린 값을 넣으면 빌드가 실패한다** — 조용히 안 올라가는 것보다 낫다고 판단했다(ADR-0032).

---

### ⑤ 알림 채널 → `ALERT_WEBHOOK_URL` + `MAIL_USERNAME`·`MAIL_PASSWORD`

spec §8이 **"이메일과 메신저 두 채널 모두"**를 요구한다. 하나만 만들면 수용 기준을 못 채운다.

**5-A. 메신저 웹훅**

- [ ] **Discord로 한다면**(가장 빠르다): 서버 생성 → 채널 설정 → `연동` → `웹후크` → `새 웹후크` → URL 복사.
- [ ] **Slack으로 한다면**: [api.slack.com/apps](https://api.slack.com/apps) → `Create New App` → `Incoming Webhooks` 활성화 → `Add New Webhook to Workspace` → 채널 선택 → URL 복사.
- **IMPORTANT — 구현이 이미 Slack 형식을 전제한다**(ADR-0025). Slack은 `{"text": …}`, Discord는 `{"content": …}`인데, **Discord 웹훅 URL 끝에 `/slack`을 붙이면 Slack 형식을 그대로 받아준다.** Discord를 고른다면 **`.env`에 넣는 URL 끝에 `/slack`을 붙여야 한다** — 안 붙이면 Discord가 400을 내고, 그 실패는 "채널에 아무것도 안 뜬다"로만 보인다.
- **MUST NOT — 웹훅 URL을 커밋하지 않는다.** URL 자체가 자격증명이라 아는 사람은 누구나 그 채널에 글을 쓸 수 있다.

**5-B. 이메일(SMTP)**

- [ ] Google 계정에 **2단계 인증을 먼저 켠다.** 켜지 않으면 앱 비밀번호 메뉴 자체가 나타나지 않는다.
- [ ] [myaccount.google.com](https://myaccount.google.com/) → 보안 → 2단계 인증 → **앱 비밀번호** → 이름 입력 → 생성.
- [ ] 나오는 16자를 **공백 없이** `MAIL_PASSWORD`에, 계정 주소를 `MAIL_USERNAME`에 넣는다. 이 값은 그 화면을 닫으면 다시 볼 수 없다.
- 서버 설정은 `smtp.gmail.com:587` STARTTLS다(`application.yaml`에 이미 있다). 일반 Gmail 계정의 발신 한도는 하루 500통 수준이라 이 프로젝트 규모에는 충분하다.
- **(선택) `ALERT_OPERATOR_EMAIL`** — 비우면 `MAIL_USERNAME`으로 간다. 발신과 수신 계정이 다를 때만 채운다.

**5-C. 값을 넣은 뒤 확인할 것**

**기동 로그 확인은 에이전트가 한다** — `알림 채널이 하나도 구성되지 않아…`가 사라지고 `알림 채널 2개를 구성했다: [webhook, mail]`이 뜨는지 본다. 이 한 줄이 "값이 실렸는가"의 유일한 표시다.

- [ ] **실제 채널에 한 통 도착하는지 당신이 본다.** 페이로드 형태와 실패 격리는 `mockwebserver`로 이미 검증돼 있으므로(`./gradlew test --tests "com.stockscreener.notification.*"`), **남은 것은 "그 URL이 정말 그 채널인가"뿐이고 그건 당신 채널을 봐야 안다.**

**없으면 막히는 것**: 배치 실패 알림과 워치리스트 알림 — spec §9 수용 기준 두 개가 여기 걸린다. 코드는 붙어 있으므로 **값만 채우면 살아난다.**

---

### 발급한 뒤 (공통 마무리)

1. 값을 `.env`에 넣는다. **`.env.example`에는 절대 넣지 않는다** — 그 파일은 커밋된다.
2. 실렸는지 확인한다:
   ```bash
   ./gradlew checkEnv   # 값은 출력되지 않고 키 이름과 설정 여부만 나온다
   ```
   **`자리표시자 그대로 — 채워지지 않음`이 뜨면 복사만 하고 안 채운 것이다.** 애플리케이션은 그 상태로도 조용히 기동하므로 이 명령이 유일한 구분 수단이다.
   IMPORTANT — `~/Desktop`이 iCloud 동기화 대상이라 **다른 기기에서 편집한 `.env`가 즉시 안 보인다.** 값을 넣었는데 미설정으로 나오면 몇 분 뒤 다시 돌려본다(실제로 약 10분 걸린 적이 있다).
3. [`docs/external-credentials.md`](docs/external-credentials.md)의 현황 표를 `완료 (YYYY-MM-DD)`로 바꾼다.
4. [`docs/progress.md`](docs/progress.md)의 "미룬 것" 절에서 해당 항목을 지운다.
5. 끝난 항목은 이 파일에서 지운다.

**MUST NOT** — 실제 키를 `.env.example`이나 커밋·로그에 넣지 않는다. `.env`만 쓴다(gitignore 대상이다).
