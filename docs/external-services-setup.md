# 외부 서비스 설정 — 재발급·재설치 절차

**여기 있는 것은 전부 이미 끝난 일이다.** 지금 손댈 것은 [`TODO.md`](../TODO.md)에 있다.

이 문서를 따로 두는 이유는 **다시 밟을 일이 실제로 있기 때문**이다 — 토큰 회전, 도메인 이전,
OAuth 클라이언트 재발급, 맥북 교체. 그때 처음부터 다시 헤매지 않으려고 <b>실측으로 확인한
함정</b>을 절차와 함께 남긴다. 완료 표시(`[x]`)는 "그때 이렇게 해서 됐다"는 뜻이다.

**MUST NOT: 여기 실제 키·토큰·URL을 적지 않는다.** 절차와 화면 경로만 둔다.

**상태를 이 문서에서 읽지 않는다.** 발급 여부의 정본은 `./gradlew checkEnv`이며, 문서 밖에서
일어나는 일이라 문서가 먼저 낡는다.

---

### ① Cloudflare 계정 + 도메인 등록 → (환경변수 없음)

- [x] **1-1. 계정 생성** — [dash.cloudflare.com](https://dash.cloudflare.com/) 회원가입. 이메일 인증까지 마친다.
- [x] **1-2. `na1011.net` 구매** — [domains.cloudflare.com](https://domains.cloudflare.com/) 또는 대시보드 좌측 `Domain Registration` → `Register Domains`. 2026-08-08 기준 연 $11.86.
> **1-3 ICANN 이메일 인증과 1-4 자동 갱신은 아직 안 끝났다 — [`TODO.md`](../TODO.md)에 있다.**
>   기한이 걸린 항목이라 완료 기록 사이에 묻어 두지 않는다.

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
  `signin/oauth/error`가 아니라 **실제 로그인 화면**으로 끝나고 `redirect_uri`가 https로 통과한다.

  **IMPORTANT — 이 레시피는 `https`로만 물어서 절반만 본다.** 평문 진입점이 열려 있어도 통과하고, 2026-08-10에 실제로 그 상태로 통과했다(엣지의 `Always Use HTTPS`가 꺼져 있었다). 나머지 절반은 이것이다:

  ```bash
  curl -sL -o /dev/null -w '%{url_effective}\n' http://screener.na1011.net/oauth2/authorization/google
  # http 로 시작해도 …/signin/identifier 로 끝나야 한다.
  # …/signin/oauth/error 면 엣지가 http를 올리지 않아 redirect_uri가 http://로 나간 것이다(ADR-0035).
  ```

  **IMPORTANT — 재발급할 일이 생기면 클라이언트 *유형*부터 본다.** 처음에 `데스크톱` 유형으로 발급해 한 번 갈아엎었다. 데스크톱 유형에는 **승인된 리디렉션 URI 입력란이 아예 없고, 유형은 생성 후 변경할 수 없다.** 그 유형에 Google이 허용하는 것은 loopback(`http://localhost:포트`)뿐인데 **loopback은 등록 없이 통과하므로 localhost 로그인은 멀쩡했고 도메인에서만 깨졌다.**

  **IMPORTANT — 그래서 확인은 리디렉션을 끝까지 따라가야 한다.** 처음 확인은 `302 → accounts.google.com`과 `redirect_uri` 값까지만 봤다. 그것은 **Spring이 만드는 요청**만 본 것이고 **Google이 그 요청을 받아주는지**는 보지 않은 것이라 위 결함을 그대로 통과시켰다. 위 `curl -sL`의 최종 URL이 `signin/oauth/error`인지 `signin/identifier`인지가 그 둘을 가른다.

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
**SDK는 붙었다 (2026-08-09).** 백엔드·프론트 모두 이벤트를 보내고, DSN이 번들에 실린 것까지 확인했다.

- [x] **4-7. 슬러그 둘 → `SENTRY_ORG` · `SENTRY_PROJECT_FRONTEND`** (2026-08-10 `checkEnv` 실측).

  **슬러그는 "발급"되는 것이 아니다.** 조직·프로젝트를 만들 때 이름에서 자동으로 만들어져
  **이미 존재하며**, 찾아서 적기만 하면 된다. 시크릿이 아니라 조회값이다.
  **이름과 슬러그는 다르다** — `My Org` → `my-org`처럼 바뀌고, 같은 이름이 있으면 뒤에 숫자가
  붙는다(`my-org-2`). **눈으로 옮겨 적지 말고 주소창을 복사한다.**

  **가장 빠른 길은 주소창이다.** 메뉴 이름은 Sentry가 자주 바꾸지만 주소 형태는 안 바뀐다:

  ```
  https://<조직슬러그>.sentry.io/projects/<프로젝트슬러그>/
  https://sentry.io/organizations/<조직슬러그>/projects/<프로젝트슬러그>/
  ```

  **MUST NOT: API·CLI로 조회하려 하지 않는다.** 4-6의 토큰은 **소스맵 업로드 전용 스코프**라
  읽기 엔드포인트가 전부 403이다(2026-08-10 실측 — `sentry-cli organizations list`,
  `/api/0/projects/`, `/api/0/organizations/<ID>/projects/`, `/api/0/organizations/<ID>/`).
  읽기 스코프(`org:read`·`project:read`) 토큰을 새로 발급하면 되지만 **주소창을 읽는 것보다
  오래 걸린다.**

  **어느 프로젝트가 프론트인지 헷갈릴 때**: DSN은 **숫자 ID만** 담아 슬러그를 유도할 수 없다
  (`https://<공개키>@o<조직ID>.ingest.<지역>.sentry.io/<프로젝트ID>`). `VITE_SENTRY_DSN` 끝의
  숫자와 프로젝트 목록의 `id`를 대조한다. **`SENTRY_PROJECT_FRONTEND`는 Vue 쪽이지 백엔드가
  아니다** — 백엔드는 소스맵이라는 개념이 없다.

> **남은 것(대시보드 도착 확인)은 [`TODO.md`](../TODO.md)에 있다.** 값을 넣는 일은 끝났고
> **눈으로 봐야 갈리는 것만** 남았다.

---

### ⑤ 메신저 웹훅 → `ALERT_WEBHOOK_URL`

**완료 (2026-08-10 `./gradlew checkEnv` 실측).** 아래는 채널을 바꾸거나 URL을 회전할 때
다시 밟을 기록이다. **이메일(SMTP) 쪽은 아직 발급 전이라 [`TODO.md`](../TODO.md)에 있다.**

- [x] **5-1. 웹훅 URL을 만든다.** 둘 중 하나면 된다.
  - **Discord**(가장 빠르다): 서버 생성 → 채널 설정 → `연동` → `웹후크` → `새 웹후크` → URL 복사.
  - **Slack**: [api.slack.com/apps](https://api.slack.com/apps) → `Create New App` →
    `Incoming Webhooks` 활성화 → `Add New Webhook to Workspace` → 채널 선택 → URL 복사.
- [x] **5-2. `.env`의 `ALERT_WEBHOOK_URL`에 넣는다.**

**IMPORTANT — 구현이 Slack 형식을 전제한다**(ADR-0025). Slack은 `{"text": …}`,
Discord는 `{"content": …}`인데 **Discord 웹훅 URL 끝에 `/slack`을 붙이면 Slack 형식을 그대로
받아준다.** Discord를 골랐다면 **`.env`에 넣는 URL 끝에 `/slack`을 붙인다** — 안 붙이면
Discord가 400을 내고, 그 실패는 **"채널에 아무것도 안 뜬다"로만 보인다.**

**MUST NOT: 웹훅 URL을 커밋하지 않는다.** URL 자체가 자격증명이다.

**넣은 뒤 반영은 자동이 아니다.** 도는 것은 `~/Library/Application Support/stock-screener/app.jar`
이므로 `ops/deploy.sh`를 돌려야 새 값이 실린다(ADR-0034). 기동 로그의
`알림 채널이 하나도 구성되지 않아…`가 사라지고 `알림 채널 N개를 구성했다`가 뜨면 실린 것이다.
