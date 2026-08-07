# 사람이 해야 할 일

에이전트가 대신할 수 없는 것만 모았다. **브라우저 조작**, **외부 서비스 발급**, **계정 권한 부여** 셋이다.

진행 상태의 정본은 [`docs/progress.md`](docs/progress.md), 외부 자격증명의 정본은 [`docs/external-credentials.md`](docs/external-credentials.md)다. 여기는 그 둘에서 **지금 당신이 손을 대야 하는 것만** 뽑아 온 작업 목록이다. 끝낸 항목은 정본 문서를 갱신하고 여기서 지운다.

---

## 1. Phase 5 수동 검증

Phase 5는 구현과 자동 검증이 끝났고 **수동 관통만 남았다.** [`design/v1-architecture.md`](docs/design/v1-architecture.md) §5의 판정이 대부분 "화면에서 실제로 그렇게 보이는가"라서 브라우저와 실제 Google 로그인이 있어야 판정된다.

```bash
./gradlew build     # 백엔드 348개 + 프론트 144개 테스트가 함께 돈다
./gradlew bootRun   # http://localhost:8080
```

기준일은 **2026-08-05**, 유니버스는 350종목이다.

### 체크리스트

- [ ] **① 로그인** — Google 계정으로 들어가진다. **허용목록에 없는 다른 Google 계정**으로도 시도해 본다. 막히되 `로그인할 수 없습니다. 접근 권한이 있는 계정인지 확인해 주세요.`만 뜨고 **왜 막혔는지는 나오지 않아야** 한다(허용목록의 존재 자체가 운영 정보다 — ADR-0004).
- [ ] **② 프리셋 실행** — 상단 셀렉터에서 `저평가 가치주`를 고르고 `스크리닝 실행`. 결과가 나온다.
- [ ] **③ 판정 3상태** — `추세 상승`(필터 방식)을 실행하고 행을 펼친다. **충족(초록 체크)·불충족(빨강 X)·데이터 없음(노랑 물음표)이 서로 다르게** 보여야 한다. 셋이 둘로 접히면 사용자는 종목이 왜 빠졌는지 알 수 없다.
- [ ] **④ 선정 이유** — `저평가 가치주`(점수 방식) 행을 펼친다. 조건마다 `획득 / 가중치`가 보이고 위에 총점·달성률이 있다. 총점만 보이면 안 된다.
- [ ] **⑤ 지표 설명** — 지표 이름 옆의 `▲`/`▼`/`–`에 마우스를 올리고, `ⓘ` 버튼을 눌러 설명·해석이 나오는지 본다.
- [ ] **⑥ 백분위 방향** — 조건 빌더에서 지표를 `PER 백분위`로 고르고 `상위 20%` 버튼 → **`이하` + `0.2`**가 들어가야 한다. `ROE 백분위`로 바꾸고 같은 버튼 → **`이상` + `0.8`**. 뒤집혀 있으면 정반대 종목이 최상위로 올라온다.
- [ ] **⑦ 기준일** — 결과 머리말에 `2026. 08. 05.` + `서버가 최신 지표 일자로 지정`이 보인다. 기준일을 **미래 날짜**로 바꿔 실행하면 `요청 … → 거래일로 보정`이 보인다. **`2025-01-01`처럼 보존 구간 밖**으로 바꾸면 빈 결과가 아니라 **오류 + 허용 구간 안내**가 나오고, 그 뒤로 날짜 선택 범위가 좁아진다.
- [ ] **⑧ 정렬·필터** — 컬럼 머리글을 눌러 정렬하고, 표 위 검색창에 종목명을 넣어 걸러본다.
- [ ] **⑨ 지지·저항** — 결과에서 종목명을 눌러 상세로 간 뒤 아래쪽 패널을 본다. **주황 파선 테두리 + `참고정보 — 확정된 매매 신호가 아닙니다` + 면책 문구 전문**이 접히지 않고 보여야 한다. **진입가·손절가로 읽힐 단일 숫자가 없어야** 한다(구간과 횟수만 있다). IMPORTANT — 이 항목만 오해가 실제 금전 손실로 이어진다.
- [ ] **⑩ 오류 격리** — 상세 화면에서 DevTools → Network → `price-reference` 요청을 우클릭 → `Block request URL` → 새로고침. **지지·저항 패널만 오류 카드 + `다시 시도`**가 되고 나머지 상세는 정상이어야 한다. 차단을 풀고 `다시 시도`를 누르면 **새로고침 없이** 복구된다(spec §9의 수용 기준이 이것이다).
- [ ] **⑪ 반응형** — DevTools에서 폭을 375px로 줄인다. 결과가 **표가 아니라 카드 목록**이 되고 조건 편집이 세로 카드가 되어야 한다. 같은 표를 좁힌 모양이면 안 된다.
- [ ] **⑫ 조건 저장·재사용** — 조건을 직접 만들어 `조건 저장` → 이름 입력 → 저장. **새로고침한 뒤** `내 화면`에서 불러와 다시 실행한다. 프리셋에는 `읽기 전용` 배지가 붙고 편집이 막히며 `복제해서 편집`이 있어야 한다.
- [ ] **⑬ 종목 검색** — 헤더 검색창에 `삼성`과 `005930`을 쳐서 상세로 들어간다. 이어서 DevTools에서 `/api/stocks?` 요청을 차단하고 아무거나 쳐 본다. 드롭다운이 `일치하는 종목이 없습니다`가 아니라 **`검색에 실패했습니다`(빨강)**여야 한다 — 둘이 같아 보이면 사용자는 없는 종목이라고 믿고 검색을 그만둔다.
- [ ] **⑭ 백분위 주의 문구가 카탈로그 실패에도 남는가** — 백분위 조건(`PER 백분위 ≤ 0.2`)으로 한 번 실행해 결과 아래 `백분위는 현재 편입된 코스피·코스닥 종목 전체를 기준으로…` 문구를 확인한다. 그다음 DevTools에서 **`/api/indicators`를 차단**하고 새로고침 → 다시 실행. **문구가 그대로 있어야 한다.** 예전에는 이 요청만 실패해도 문구가 사라졌고, 그러면 화면이 "이 유니버스의 상위 20%"라고 읽히는데 사실이 아니다(서버가 `usesPercentile`로 알려주도록 고쳤다).
- [ ] **⑮ 표↔카드 경계에서 문구가 같은가** — 폭을 **768px과 767px**로 오가며 같은 결과를 본다. `충족`·`데이터 없음`·빈 결과 문구가 양쪽에서 **글자까지 같아야** 한다(예전에는 데스크톱만 `데이터없음`이었다). ⑪의 375px은 세 브레이크포인트가 전부 켜진 상태라 이 경계를 덮지 못한다. 여유가 있으면 **700px·800px**도 훑어본다 — 641~899px 구간은 어떤 자동 검사도 덮지 않는다.

### 이번에 확인할 수 없는 항목

- **결과 잘림 표시(`truncated`)** — 상한이 2,000행인데 유니버스가 350종목이라 재현되지 않는다. 코드는 있고 `ResultSummary`가 경고 배너를 그린다. 미국 시장이 붙는 v1.1에서 실제로 걸린다.

---

## 2. 추가로 해줘야 할 세팅

- [ ] **지인 계정을 허용목록에 넣는다.** spec §9의 수용 기준이 "지인이 각자의 Google 계정으로 로그인"인데 지금 허용된 계정은 `zerus3934@gmail.com`(관리자) 하나뿐이다. 관리자 화면은 Phase 6이므로 그때까지는 SQL로 넣는다.

  ```sql
  -- psql -d stock_screener
  INSERT INTO allowed_email (email, is_admin, note)
  VALUES ('friend@example.com', false, '지인');
  ```

  이미 로그인한 적 있는 사람의 **권한을 회수·부여하면 다음 로그인부터** 적용된다. `app_user.is_admin`이 로그인 시점의 사본이기 때문이다.

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

- [ ] **3-1. IMPORTANT — OAuth 클라이언트를 `웹 애플리케이션` 유형으로 다시 만든다.**

  지금 있는 `screener-desktop-client`는 **`데스크톱` 유형이라 리디렉션 URI를 등록할 칸이 아예 없다**(2026-08-07 확인). 편집 화면에 이름과 저장 버튼만 나오는 것이 그 증상이고, **유형은 생성 후 변경할 수 없다.** Google은 데스크톱 유형에 loopback(`http://localhost:포트`)만 허용하고 커스텀 HTTPS 주소는 받지 않는다.

  **이 함정이 지금까지 안 보였던 이유**: loopback은 등록 없이 통과하므로 `http://localhost:8080` 로그인은 **정상 동작한다**(실측). 도메인 주소에서만 깨진다. 실측한 두 결과가 이것이다:
  ```
  http://localhost:8080/login/oauth2/code/google      → Google 로그인 화면 (정상)
  https://screener.na1011.net/login/oauth2/code/google → Error 400: redirect_uri_mismatch
  ```

  [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → 사용자 인증 정보 → `사용자 인증 정보 만들기` → `OAuth 클라이언트 ID` → **유형 `웹 애플리케이션`** → `승인된 리디렉션 URI`에 **세 개를 모두** 넣는다:
  ```
  https://screener.na1011.net/login/oauth2/code/google
  http://localhost:8080/login/oauth2/code/google
  http://localhost:5173/login/oauth2/code/google
  ```
  localhost 둘을 함께 넣는 이유는 §2의 4번째 항목에 있다(Vite 프록시가 Host를 바꾸지 않아 포트별로 `redirect_uri`가 갈린다, ADR-0018).

  OAuth 동의 화면의 `승인된 도메인`에는 서브도메인이 아니라 **`na1011.net`**을 넣는다.

  만든 뒤 **`.env`의 `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`을 새 클라이언트 값으로 교체하고 앱을 재시작한다.** 값이 낡으면 증상이 `invalid_client`로 바뀌어 원인이 또 달라 보인다. 기존 데스크톱 클라이언트는 지워도 되고 두어도 된다(6개월 미사용 시 자동 삭제).

  검증은 브라우저 없이도 된다 — 아래가 `redirect_uri_mismatch` 없이 로그인 화면(200)으로 끝나면 통과다:
  ```bash
  LOC=$(curl -s -D- -o /dev/null https://screener.na1011.net/oauth2/authorization/google | grep -i '^location:' | sed 's/^[Ll]ocation: //' | tr -d '\r')
  curl -sL -o /tmp/g.html -w '%{url_effective}\n' "$LOC"    # signin/oauth/error 가 나오면 실패
  ```

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

### ④ Sentry 프로젝트 → `SENTRY_DSN` (+ 프론트용 변수 신설 필요)

- [ ] **4-1.** [sentry.io](https://sentry.io/) 가입 → 조직 생성.
- [ ] **4-2. 프로젝트를 둘 만든다** — 플랫폼 `Java / Spring Boot` 하나, `JavaScript / Vue` 하나.
  **하나로 합치지 않는 이유**: 이슈 그룹핑·릴리스·소스맵 처리가 플랫폼별로 갈려서, 섞으면 프론트 스택트레이스가 minify된 채로 남는다. 무료 티어의 제약은 프로젝트 수가 아니라 **월 이벤트 총량**이라 나눠도 비용이 늘지 않는다.
- [ ] **4-3. 생성 중 나오는 `Set your alert frequency`는 기본값(`Alert me on high priority issues` + `Notify via email`)으로 둔다.** ⑤에서 알림을 직접 구현하지만 **잡는 대상이 다르다** — 자체 알림은 배치 실패·워치리스트처럼 **코드가 예상한 사건**이고, Sentry는 예상 못 한 예외·프론트 JS 오류, 그리고 **알림 코드 자체가 죽은 경우**를 잡는다. 마지막 것 때문에 꺼서는 안 된다. 자체 알림만 두면 그게 고장 난 사실을 알려줄 경로가 없다.
  `Connect to messaging`은 **지금 누르지 않는다.** ⑤에서 채널을 정한 뒤 붙이는 편이 순서에 맞고, Sentry 설정에서 나중에 언제든 추가된다.
- [ ] **4-4.** 각 프로젝트의 DSN을 복사한다. 백엔드 것이 `SENTRY_DSN`이다.
- [ ] **4-5. DSN 두 개를 `.env`에 넣는다.** 키 이름은 `.env.example`에 이미 있다(2026-08-07 추가) — 백엔드가 `SENTRY_DSN`, 프론트가 `VITE_SENTRY_DSN`이다. 넣은 뒤 `./gradlew checkEnv`가 두 키를 `설정됨`으로 내면 된다. **안 넣으면 "`.env.example`에 있으나 `.env`에 없는 키" 목록에 뜬다.**
  프론트 DSN이 번들에 그대로 박혀 공개되는 것은 **정상이다** — Sentry DSN은 쓰기 전용 공개 식별자다. 시크릿으로 착각해 숨기려다 빌드를 꼬지 않는다.
- [ ] **4-6. IMPORTANT — 값을 넣어도 프론트 번들에는 아직 안 들어간다.** Vite의 `envDir`은 `frontend/`라 **루트 `.env`를 읽지 않는다.** `bootRun`에만 걸려 있는 `applyDotEnv`를 `frontendBuild` 태스크에도 걸어야 하고(`build.gradle`), 그때 `inputs.property`로 DSN을 넣어야 값이 바뀔 때 번들이 다시 만들어진다 — 안 그러면 Gradle이 캐시된 옛 번들을 그대로 쓴다.
  **프론트 Sentry SDK를 붙일 때 함께 한다.** 지금 주입 코드만 넣으면 참조하는 곳이 없어 번들에 아무것도 안 남고, 그래서 **동작하는지 확인할 방법도 없다.**

**없으면 막히는 것**: 에러 수집·통지. 현재 프론트 오류는 `console.error`에만 남는다.

---

### ⑤ 알림 채널 → `ALERT_WEBHOOK_URL` + `MAIL_USERNAME`·`MAIL_PASSWORD`

spec §8이 **"이메일과 메신저 두 채널 모두"**를 요구한다. 하나만 만들면 수용 기준을 못 채운다.

**5-A. 메신저 웹훅**

- [ ] **Discord로 한다면**(가장 빠르다): 서버 생성 → 채널 설정 → `연동` → `웹후크` → `새 웹후크` → URL 복사.
- [ ] **Slack으로 한다면**: [api.slack.com/apps](https://api.slack.com/apps) → `Create New App` → `Incoming Webhooks` 활성화 → `Add New Webhook to Workspace` → 채널 선택 → URL 복사.
- **페이로드 형식이 다르다** — Slack은 `{"text": …}`, Discord는 `{"content": …}`다. **Discord 웹훅 URL 끝에 `/slack`을 붙이면 Slack 형식을 그대로 받아준다.** 이걸 쓰면 어느 쪽을 고르든 `AlertPort` 구현이 하나로 끝난다. 어느 채널로 갈지 아직 안 정했다면 이 방식을 전제로 만드는 편이 낫다.
- **MUST NOT — 웹훅 URL을 커밋하지 않는다.** URL 자체가 자격증명이라 아는 사람은 누구나 그 채널에 글을 쓸 수 있다.

**5-B. 이메일(SMTP)**

- [ ] Google 계정에 **2단계 인증을 먼저 켠다.** 켜지 않으면 앱 비밀번호 메뉴 자체가 나타나지 않는다.
- [ ] [myaccount.google.com](https://myaccount.google.com/) → 보안 → 2단계 인증 → **앱 비밀번호** → 이름 입력 → 생성.
- [ ] 나오는 16자를 **공백 없이** `MAIL_PASSWORD`에, 계정 주소를 `MAIL_USERNAME`에 넣는다. 이 값은 그 화면을 닫으면 다시 볼 수 없다.
- 서버 설정은 `smtp.gmail.com:587` STARTTLS다. 일반 Gmail 계정의 발신 한도는 하루 500통 수준이라 이 프로젝트 규모에는 충분하다.

**없으면 막히는 것**: 배치 실패 알림과 워치리스트 알림 — spec §9 수용 기준 두 개가 여기 걸린다.

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
