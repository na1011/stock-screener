# 사람이 해야 할 일

에이전트가 대신할 수 없는 것만 모았다. **브라우저 조작**, **외부 서비스 발급**, **계정 권한 부여** 셋이다.

진행 상태의 정본은 [`docs/progress.md`](docs/progress.md), 외부 자격증명의 정본은 [`docs/external-credentials.md`](docs/external-credentials.md)다. 여기는 그 둘에서 **지금 당신이 손을 대야 하는 것만** 뽑아 온 작업 목록이다. 끝낸 항목은 정본 문서를 갱신하고 여기서 지운다.

---

## 1. 수동 검증 — 하나만 남았다

**허용목록 밖 계정 로그인**뿐이다. 다른 Google 계정의 자격증명이 필요해 사람만 할 수 있다.

```bash
./gradlew build     # 백엔드 + 프론트 테스트가 함께 돈다
./gradlew bootRun   # http://localhost:8080
```

기준일은 **2026-08-05**, 유니버스는 350종목이다.

### 남은 것

- [ ] **① 로그인 (허용목록 밖 계정)** — **허용목록에 없는 다른 Google 계정**으로 로그인을 시도한다. 막히되 `로그인할 수 없습니다. 접근 권한이 있는 계정인지 확인해 주세요.`만 뜨고 **왜 막혔는지는 나오지 않아야** 한다(허용목록의 존재 자체가 운영 정보다 — ADR-0004).

  허용된 계정(`zerus3934@gmail.com`)으로 들어가지는 것은 확인했다.

- [ ] **⑯ 관리자 화면 관통** (2026-08-08 추가). 자동 테스트는 전부 통과했고 익명 접근이 401인 것도 실측했다. **브라우저 로그인이 필요한 것만 남았다.**

  **Chrome에 로그인해 두면 에이전트가 크롬을 원격 제어해 전부 대신 본다**(Phase 5 관통을 그렇게 했다). 당신이 할 일은 둘뿐이다 — **`./gradlew bootRun`을 띄워 두는 것**, 그리고 **4단계용으로 비관리자 계정에 한 번 로그인해 주는 것**. 5단계는 350콜을 태우므로 에이전트가 당신 확인을 받고 누른다.

  1. 관리자 계정으로 로그인 → 좌측 레일의 사용자 메뉴에 **관리자**가 보이고 `/admin`이 열린다.
  2. `/admin/accounts`에서 지인 계정을 넣는다(아래 §2가 SQL로 하라던 것이 이 화면으로 대체됐다).
  3. **자기 관리자 권한 회수를 시도하면 거부된다.** 거부 뒤 스위치가 원래대로 돌아와야 한다 — 409만 주고 값이 바뀌어 있으면 다음 로그인부터 아무도 못 들어온다.
  4. 그 지인 계정으로 로그인 → **메뉴에 관리자 항목이 아예 없고**, `/admin`을 주소창에 직접 쳐도 조용히 스크리너로 돌아온다(403 화면이 뜨면 안 된다 — 그 자체가 존재를 알린다).
  5. `/admin`에서 **일일 갱신**을 띄운다. 응답이 즉시 오고 목록에 `RUNNING`이 생기며, 도는 동안 두 번째 기동이 막힌다.
     **여기서 `WatchlistAlertRunner`가 실데이터로 처음 판정까지 간다** — `삼성전자`가 `저평가 가치주`를 조건으로 담겨 있다. 알림은 자격증명이 없어 로그로 떨어지고, 그 로그가 판정이 돌았다는 증거다.
     **쿼터를 태운다**: 종목당 1콜이라 350콜, data.go.kr 일일 한도 9,000의 4%다.
  6. 실패 종목이 생기면 상세를 열어 **실패분만 재실행**을 누른다. 새 실행이 `#N 재실행`으로 표시되고, 성공한 실패가 요약의 "미해결 실패"에서 빠진다.

### 통과한 것 — 여기서 지운다

**②~⑮는 2026-08-07에 전부 통과했다.** 항목별 관찰은 그날의 커밋 메시지와 `git log`에 있다 — 여기 옮겨 두면 반드시 낡고, 이 파일은 *지금 손대야 할 것*의 목록이다.

**Phase 6의 통지·워치리스트도 2026-08-08에 관통했다** — 담기, 알림 조건 지정(`판정 전` 상태), 목록 표시, 그리고 **워치리스트가 참조 중인 화면 삭제가 건수와 함께 막히는 것**까지. 마지막 항목은 워치리스트 행이 생기기 전에는 원리적으로 재현할 수 없던 경로다. 확인용으로 만든 화면은 지웠고, `삼성전자`가 `저평가 가치주`를 알림 조건으로 하나 담겨 있다.

### 아직 확인하지 못한 것

- **결과 잘림 표시(`truncated`)** — 상한이 2,000행인데 유니버스가 350종목이라 재현되지 않는다. 코드는 있고 `ResultSummary`가 경고 배너를 그린다. 미국 시장이 붙는 v1.1에서 실제로 걸린다.
- **`WatchlistAlertRunner`를 실데이터로 태우기** — 지금 실행 경로가 일일 배치뿐이라 관통에서 판정까지는 못 봤다. 테스트가 실제 PostgreSQL에서 상태 전이 넷을 모두 확인하지만 지표 값은 합성이다. **관리자 덩어리가 배치 HTTP 진입점을 가져올 때 함께 본다.**

---

## 2. 추가로 해줘야 할 세팅

- [ ] **지인 계정을 허용목록에 넣는다.** spec §9의 수용 기준이 "지인이 각자의 Google 계정으로 로그인"인데 지금 허용된 계정은 `zerus3934@gmail.com`(관리자) 하나뿐이다.

  **이제 `/admin/accounts` 화면에서 한다**(2026-08-08). SQL은 그 화면에 들어갈 수 없게 됐을 때의 복구 경로로만 남는다:

  ```sql
  -- psql -d stock_screener
  INSERT INTO allowed_email (email, is_admin, note)
  VALUES ('friend@example.com', false, '지인');
  ```

  이미 로그인한 적 있는 사람의 **권한을 회수·부여하면 다음 로그인부터** 적용된다. `app_user.is_admin`이 로그인 시점의 사본이기 때문이다. 화면도 그 사실을 문구로 말한다.

- [ ] **(선택) 프론트 개발 서버로 로그인하려면 리디렉션 URI를 하나 더 등록한다.**
  [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → 사용자 인증 정보 → 해당 OAuth 클라이언트 → 승인된 리디렉션 URI에 추가:

  ```
  http://localhost:5173/login/oauth2/code/google
  ```

  Vite 프록시가 Host를 바꾸지 않아 Spring이 요청 Host로 `redirect_uri`를 만든다(ADR-0018, 실측 확인). 등록하지 않으면 `npm run dev`에서만 `redirect_uri_mismatch`가 난다. **`./gradlew bootRun`으로 `:8080`에서 쓰는 경로는 지금 그대로 동작하므로**, 위 §1 검증에는 이 작업이 필요 없다.

- [ ] **(권장) macOS iCloud 동기화에서 이 폴더를 빼거나, 저장소를 `~/Desktop` 밖으로 옮긴다.** `build/`의 클래스 파일이 `X 2.class`로 복제되면 테스트가 `wrong name` 오류로 실패하고 Gradle이 파일 해싱에서 수 분간 멈춘다. 이번 세션에도 두 번 발생했다. `./gradlew clean`이 처방이고 `checkDuplicateFiles`가 먼저 잡아 주지만, 원인을 없애는 편이 낫다.

---

## 3. Phase 6 진입 전에 발급받을 것

Phase 6(워치리스트·관리자·운영)은 **네 가지 외부 자격증명이 전부 필요하다.** 항목별 배경은 [`docs/external-credentials.md`](docs/external-credentials.md) 5~8번에 있고, 여기는 **순서대로 따라 하는 절차**다.

**도메인은 `na1011.net`, 이 앱의 주소는 `screener.na1011.net`이다**(결정 2026-08-08). Cloudflare Registrar에서 산다 — 등록과 동시에 Cloudflare에 zone이 생기고 네임서버가 Cloudflare로 잡히므로, 다른 등록업체에서 살 때 필요한 **네임서버 변경과 전파 대기(수 시간~48시간)가 통째로 없어진다.** ①과 ②가 한 자리에서 이어진다는 것이 이 선택의 실익이다.

**프로젝트 이름이 아니라 우산 도메인을 사고 서브도메인에 얹는다.** 서브도메인은 개수 제한도 추가 비용도 없고 Tunnel이 호스트명별로 다른 로컬 포트에 붙일 수 있으므로, 다음 프로젝트는 `wcs.na1011.net`처럼 Public Hostname만 추가하면 된다. 갱신비가 프로젝트 수만큼 늘지 않고, **프로젝트 범위가 바뀌어도 주소가 죽지 않는다** — 이 스크리너만 해도 v1.1에 미국 시장이 붙어서 `jongmok`(종목) 같은 이름을 샀다면 이미 어긋났을 것이다. 루트(`na1011.net`)는 라우트를 걸지 않으면 아무것도 응답하지 않으므로 지금 만들 것이 없다.

**이름과 TLD를 이렇게 고른 이유**(후보 조회는 2026-08-08 RDAP):

- **`na1011`은 GitHub 사용자명과 철자가 같다.** 포트폴리오 주소와 저장소 주소가 어긋나지 않는다.
- **`.com`은 못 쓴다.** `na1011.com`이 2026-04-27에 이미 등록됐다(A 레코드 없이 보유만 하고 있다). 등록업체가 대안으로 제시하는 `na-1011.com`은 **하이픈이 부채다** — 구두로 전달할 때마다 하이픈을 설명해야 하고, 빠뜨린 오타는 남의 도메인으로 간다.
- **`.dev`를 버린 이유는 인상이다.** 용도 제한이 있는 TLD는 아니지만(Google Registry의 범용 gTLD다) 비개발자 지인에게 개발·테스트 사이트로 읽힌다. `.co`는 인상이 낫지만 연 $25 수준이라 그 차이를 지불할 만큼은 아니었다.
- **`.net`은 범용이고 하이픈이 없으며 연 $11.86이다.** 예스러운 인상은 있으나 "테스트 사이트"로 읽히지는 않는다.

**의존 순서**: ① 도메인 → ② Tunnel → ③ OAuth·앱 설정. 이 셋은 사슬이라 앞을 건너뛸 수 없다. **④ Sentry와 ⑤ 알림은 이 사슬과 무관하므로 아무 때나 먼저 해도 된다.**

**①②③은 끝났다. 남은 것은 ④와 ⑤뿐이고, ⑤가 지금 실제로 막고 있는 것이다** — 알림 채널 코드는 붙어 있으나(ADR-0025) 값이 없어 비활성이라 배치 실패와 워치리스트 알림이 **로그로만 떨어진다**. 값을 채우면 코드 변경 없이 살아난다.

**돈이 드는 것은 도메인 하나뿐이다.** Cloudflare Registrar는 도매 원가로 팔고 마진·갱신 인상이 없다(`.com` 기준 연 $10 안팎). Tunnel·Sentry 무료 티어·Slack/Discord 웹훅·Gmail SMTP는 이 규모에서 전부 0원이다.

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

- [ ] **4-1.** [sentry.io](https://sentry.io/) 가입 → 조직 생성.
- [ ] **4-2. 프로젝트를 둘 만든다** — 플랫폼 `Java / Spring Boot` 하나, `JavaScript / Vue` 하나.
  **하나로 합치지 않는 이유**: 이슈 그룹핑·릴리스·소스맵 처리가 플랫폼별로 갈려서, 섞으면 프론트 스택트레이스가 minify된 채로 남는다. 무료 티어의 제약은 프로젝트 수가 아니라 **월 이벤트 총량**이라 나눠도 비용이 늘지 않는다.
- [ ] **4-3. 생성 중 나오는 `Set your alert frequency`는 기본값(`Alert me on high priority issues` + `Notify via email`)으로 둔다.** ⑤에서 알림을 직접 구현하지만 **잡는 대상이 다르다** — 자체 알림은 배치 실패·워치리스트처럼 **코드가 예상한 사건**이고, Sentry는 예상 못 한 예외·프론트 JS 오류, 그리고 **알림 코드 자체가 죽은 경우**를 잡는다. 마지막 것 때문에 꺼서는 안 된다. 자체 알림만 두면 그게 고장 난 사실을 알려줄 경로가 없다.
  `Connect to messaging`은 **지금 누르지 않는다.** ⑤에서 채널을 정한 뒤 붙이는 편이 순서에 맞고, Sentry 설정에서 나중에 언제든 추가된다.
- [ ] **4-4.** 각 프로젝트의 DSN을 복사한다. 백엔드 것이 `SENTRY_DSN`이다.
- [ ] **4-5. DSN 두 개를 `.env`에 넣는다.** 키 이름은 `.env.example`에 이미 있다(2026-08-07 추가) — 백엔드가 `SENTRY_DSN`, 프론트가 `VITE_SENTRY_DSN`이다. 넣은 뒤 `./gradlew checkEnv`가 두 키를 `설정됨`으로 내면 된다. **안 넣으면 "`.env.example`에 있으나 `.env`에 없는 키" 목록에 뜬다.**
  프론트 DSN이 번들에 그대로 박혀 공개되는 것은 **정상이다** — Sentry DSN은 쓰기 전용 공개 식별자다. 시크릿으로 착각해 숨기려다 빌드를 꼬지 않는다.
- [ ] **4-6. 소스맵 업로드용 `SENTRY_AUTH_TOKEN`을 발급한다 — 조직 전체에 하나만 만든다.**

  `Settings` → **`Developer Settings`** → **`Organization Tokens`** → `Create New Token`
  메뉴를 못 찾으면 주소로 바로 간다: `https://sentry.io/orgredirect/organizations/:orgslug/settings/auth-tokens/`
  (`Settings` 바로 밑에는 없다. `Developer Settings` 안으로 한 단계 더 들어간다.)

  **MUST: DSN처럼 프로젝트별로 만들지 않는다.** 조직 토큰은 **그 조직의 모든 프로젝트에 접근**하므로 Spring·Vue 구분 없이 하나면 된다. 업로드 대상은 토큰이 아니라 빌드 설정의 `org`·`project`가 정한다. **DSN은 프로젝트마다 다른데 이 토큰은 조직 하나** — 이 비대칭이 헷갈리는 지점이다.
  스코프는 생성 시 소스맵 업로드·릴리스 생성에 맞춰 자동으로 붙는다. 개인 토큰(`계정 드롭다운` → `Personal Tokens`)을 쓴다면 `Project: Read & Write` + `Release: Admin`을 직접 골라야 한다.

  **DSN과 성격이 정반대다.** DSN은 이벤트를 보내는 **공개** 식별자고, 이 토큰은 Sentry API에 쓰는 **시크릿**이다. **MUST NOT: `VITE_` 접두어를 붙이지 않는다** — 붙이는 순간 번들에 실려 공개된다.
  **없으면 프론트 이슈가 `index-a3f9.js:1:28471`처럼만 남는다.** 프로젝트를 둘로 나눈 것(4-2)은 이슈가 섞이는 것을 막을 뿐, 소스맵을 올리지 않으면 스택트레이스는 여전히 못 읽는다. 백엔드는 이 토큰이 필요 없다 — 소스맵이라는 개념이 없고 바이트코드에 줄 번호가 들어 있다.
- [ ] **4-7. IMPORTANT — 값을 넣어도 프론트 번들에는 아직 안 들어간다.** Vite의 `envDir`은 `frontend/`라 **루트 `.env`를 읽지 않는다.** `bootRun`에만 걸려 있는 `applyDotEnv`를 `frontendBuild` 태스크에도 걸어야 하고(`build.gradle`), 그때 `inputs.property`로 DSN을 넣어야 값이 바뀔 때 번들이 다시 만들어진다 — 안 그러면 Gradle이 캐시된 옛 번들을 그대로 쓴다.
  **프론트 Sentry SDK를 붙일 때 함께 한다.** 지금 주입 코드만 넣으면 참조하는 곳이 없어 번들에 아무것도 안 남고, 그래서 **동작하는지 확인할 방법도 없다.**

**없으면 막히는 것**: 에러 수집·통지. 현재 프론트 오류는 `console.error`에만 남는다.

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

- [ ] `./gradlew bootRun`의 기동 로그에서 **`알림 채널이 하나도 구성되지 않아…`가 사라지고** `알림 채널 2개를 구성했다: [webhook, mail]`이 뜬다. 이 한 줄이 "값이 실렸는가"의 유일한 표시다 — 값이 비면 애플리케이션은 그대로 기동하고 알림만 로그로 떨어진다.
- [ ] 실제 채널에 한 통 도착하는지 본다. 페이로드 형태와 실패 격리는 `mockwebserver`로 이미 검증돼 있으므로(`./gradlew test --tests "com.stockscreener.notification.*"`), **남은 것은 "그 URL이 정말 그 채널인가"뿐이다.**

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
