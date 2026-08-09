---
id: ADR-0033
status: 채택됨
date: 2026-08-09
applies_to:
  - "ops/**"
  - "src/main/java/**/pipeline/scheduler/**"
---

# ADR-0033. 앱은 LaunchAgent로 띄우고, 실행 산출물은 TCC 밖으로 내보낸다

## 결정

앱을 `~/Library/LaunchAgents`의 **LaunchAgent**로 등록한다(LaunchDaemon이 아니다). 실행에 필요한 것(jar·`.env`·실행 스크립트)은 `~/Library/Application Support/stock-screener`로 **복사해서** 돌린다. 배치 시각은 새벽 4시에서 **평일 20시**로 옮기고 대상 일자를 당일로 바꾼다.

## 규칙

- **MUST NOT: 저장소 경로를 plist가 직접 가리키게 하지 않는다.** 처음의 근거는 TCC였다 — 저장소가 `~/Desktop` 아래에 있었고 macOS는 그 경로를 보호하므로, launchd가 띄운 프로세스에는 권한이 없어 **스크립트 실행조차 거부됐다**(실측 증상 `/bin/bash: .../ops/run-app.sh: Operation not permitted`, exit 126. **터미널에서는 멀쩡히 실행돼** "왜 launchd에서만 안 되지"로만 보인다). **저장소를 옮겨 그 근거는 사라졌지만 규칙은 남았다 — 근거는 ADR-0034가 이어받는다.**
- **MUST NOT: `/bin/bash`에 전체 디스크 접근 권한을 주어 우회하지 않는다.** TCC 권한은 바이너리 단위라, 그렇게 하면 bash로 실행되는 **모든 것**이 보호 경로를 읽게 된다.
- **MUST NOT: plist에 시크릿을 적지 않는다.** plist는 평문이고 백업·동기화·화면 공유를 그대로 따라다닌다. `.env`를 읽는 주체는 `run-app.sh`다.
- **MUST: `ops/stage.sh`를 거쳐 배포한다.** jar를 제자리에 덮어쓰지 않고 임시 이름으로 쓴 뒤 `mv`한다 — 큰 파일을 덮어쓰는 도중 재시작이 걸리면 반쯤 쓰인 jar를 실행하게 된다.
- **MUST: 살아 있는지는 밖에서 묻는다.** `curl https://screener.na1011.net/actuator/health`. 로컬만 보면 cloudflared가 죽어 있어도 초록이다. **밖에서 502면 터널은 살아 있고 앱이 죽은 것이고, 응답이 아예 없으면 터널이 죽은 것이다** — 둘을 가른 뒤 `~/Library/Logs/stock-screener/{stdout,stderr}.log`를 본다.
- **MUST NOT: `AdminJobLauncher`의 `@Qualifier`를 지우지 않는다.** 아래 배경 참조.
- **MUST: `SchedulerEnabledContextTest`를 유지한다.** 운영 구성(`pipeline.scheduler.enabled=true`)을 빌드가 밟는 유일한 지점이다.

## 검증

```bash
ops/install-agent.sh                                    # 등록 + 기동까지 확인하고 끝난다
launchctl print gui/$(id -u)/com.stockscreener.app      # state = running
curl -s https://screener.na1011.net/actuator/health     # 밖에서 {"status":"UP"}

# KeepAlive가 실제로 되살리는가
kill -9 $(launchctl print gui/$(id -u)/com.stockscreener.app | awk '/^\s+pid = /{print $3}')
sleep 40 && curl -s localhost:8080/actuator/health

# 운영 구성이 기동되는가 (빌드가 판정한다)
./gradlew test --tests "com.stockscreener.pipeline.scheduler.*"
```

**전부 2026-08-09 실측으로 확인했다** — 등록·기동, 터널 경유 200, `kill -9` 후 다른 PID로 재기동, `ops/deploy.sh`의 빌드→스테이징→재기동 왕복까지.

## 배경

**LaunchAgent를 고른 이유는 FileVault다.** 처음에는 cloudflared와 대칭을 맞춰 LaunchDaemon으로 갈 생각이었다 — 데몬의 이점은 "로그인 전에도 뜬다"이므로 §5의 "재부팅 후 사람 개입 없이"에 직접 걸린다. 그런데 이 기기는 **FileVault가 켜져 있어** 부팅 직후 볼륨이 잠겨 있고, 그 상태에서는 데몬도 뜨지 못한다. 사전 부팅 화면에서 사람이 암호를 넣어야 볼륨이 열리며 **그 인증이 곧 로그인**이다. 즉 데몬의 유일한 이점이 이 기기에서 성립하지 않는다. 남는 것은 sudo 없이 설치·재시작·해제가 된다는 이점뿐이고, 그것은 실재한다.

**그 대신 §5의 판정 하나를 문자 그대로는 만족하지 못한다.** 사용자가 FileVault를 켠 채로 두기로 했으므로(2026-08-09), 콜드 부팅은 암호 한 번을 요구한다. 계획된 재부팅은 `sudo fdesetup authrestart`로 넘긴다. 잠자기→깨어남·프로세스 죽음·로그아웃→로그인은 전부 자동이다.

**스테이징은 TCC 때문에 생겼지만 이득이 하나 더 있다.** 도는 jar가 저장소 밖에 있으면 개발 중 재빌드가 도는 인스턴스를 건드리지도 않는다. 대가는 `.env` 사본이 하나 늘어나는 것이다(권한 600).

**여기서 "저장소를 옮기면 그 사본을 없앨 수 있다"고 적었는데, 옮긴 뒤 다시 재고 없애지 않기로 했다**(ADR-0034). 부수 이득으로 적은 그것이 사실은 스테이징의 주된 이유였다 — 없애면 `./gradlew clean`이 도는 앱의 jar를 지운다.

**배치 시각을 옮긴 이유는 둘이다.** 첫째, 이 앱은 맥북 위에서 돌고 새벽 4시는 기기가 자고 있을 가능성이 가장 큰 시각이다. 잠든 사이의 스케줄은 그냥 지나간다. 둘째 — 그리고 이것이 더 크다 — **금요일 종가가 한 번도 수집되지 않고 있었다.** 평일 04시에 전일을 대상으로 도는 구성에서는 월요일 실행의 대상이 일요일이라 주말로 걸러지고 토요일에는 실행이 없다. 매주 하루치가 조용히 비었고, 컴파일도 테스트도 알림도 이것을 잡지 못했다.

**첫 launchd 기동에서 결함이 하나 더 드러났다.** 스케줄러를 켜면 Boot이 `taskScheduler` 빈을 만드는데 그것도 `AsyncTaskExecutor`를 구현해 `AdminJobLauncher`의 주입 후보가 둘이 되고, **컨텍스트가 기동에 실패했다.** 스케줄러 기본값이 꺼짐이라 `./gradlew build`도 평소 `bootRun`도 그 가지를 밟지 않아 435건이 전부 초록이었다. ADR-0030의 Sentry 스모크 테스트와 **같은 종류의 공백**이고, 같은 방식으로 막았다 — 운영 프로퍼티를 켠 컨텍스트 테스트.

## 버린 대안

- **LaunchDaemon(`/Library/LaunchDaemons`)** — FileVault 때문에 이점이 사라진다. 설치·재시작마다 sudo가 필요해 `deploy.sh`가 사람 손을 타게 된다.
- **`/bin/bash`에 전체 디스크 접근 권한 부여** — 위 규칙 참조. 권한 범위가 이 앱을 훨씬 넘는다.
- **저장소를 `~/Desktop` 밖으로 옮긴다** — 이것이 더 근본적이지만 사람이 해야 하는 작업이라, 옮기지 않아도 되는 구조를 먼저 만들어 두는 편이 낫다고 봤다. **나중에 실제로 옮겼다(ADR-0034).** 그때 이 구조가 그대로 살아남은 것이 이 판단의 결과다 — 옮기고도 `ops/`는 한 줄도 바뀌지 않았다.
- **`pmset`으로 절전을 전면 차단** — sudo가 필요하고 배터리로 도는 동안에도 깨어 있게 만든다. `caffeinate -s`는 **전원이 연결된 동안만** 유휴 절전을 막아 범위가 정확히 맞는다. 뚜껑을 닫는 경우만 `pmset`이 남고, 그것은 `TODO.md`에 있다.
- **배치를 20시가 아니라 장 마감 직후(16시)로** — 사람이 아직 밖에 있을 시각이라 기기가 깨어 있을 보장이 더 낮다. 20시가 "종가 확정 이후"와 "기기가 켜져 있음"을 동시에 만족하는 구간이다.

## 결과

- **쉬워지는 것**: 로그인만 하면 앱이 뜬다. 배포가 `ops/deploy.sh` 한 줄이다. 프로세스가 죽어도 30초 안에 돌아온다. 금요일 종가가 들어온다.
- **어려워지는 것**: 콜드 부팅에 암호 한 번이 필요하다. `.env` 사본이 하나 늘었다. 맥북이 자면 사이트도 내려간다(온프레미스의 대가, spec §6).
- **아직 확인하지 못한 것**: data.go.kr이 20시에 **당일** 종가를 이미 내주는지. 아니면 수집이 0건으로 끝나는데, `DailyUpdateJob`이 그 경우를 알리므로 하루 만에 드러난다. 알림이 거래일마다 오면 시각을 늦춘다.
- **되돌리려면**: `ops/` 전체와 `PipelineScheduler`의 크론·대상 일자 규칙, 그리고 `install-agent.sh --uninstall`.
