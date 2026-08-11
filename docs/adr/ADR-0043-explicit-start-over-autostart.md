---
id: ADR-0043
status: 채택됨
date: 2026-08-11
applies_to:
  - "ops/**"
---

# ADR-0043. 앱과 터널은 사람이 친 명령으로만 뜬다

## 결정

**자동 기동을 전부 걷는다.** 앱의 LaunchAgent(`com.stockscreener.app`)와 cloudflared의 LaunchDaemon(`com.cloudflare.cloudflared`)을 해제하고, 기동·중지를 `ops/start.sh`·`ops/stop.sh`·`ops/status.sh`로 옮긴다. **크래시 자동 복구도 없다** — 죽으면 죽은 채로 있다.

**ADR-0033의 LaunchAgent 결정을 대체한다.** 그 ADR의 나머지(스테이징 경유, plist에 시크릿 금지, `@Qualifier` 유지, `SchedulerEnabledContextTest` 유지, "살아 있는지는 밖에서 묻는다")는 전부 유효하고, 스테이징의 근거는 ADR-0034가 이어받는다.

## 규칙

- **MUST NOT: 부팅·로그인에 걸리는 자동 기동을 다시 만들지 않는다.** LaunchAgent·LaunchDaemon·crontab·로그인 항목 어느 것으로도. 되살리려면 이 ADR을 먼저 뒤집는다.
- **MUST: PID 파일을 그대로 믿지 않는다.** 살아 있는지는 **그 PID의 명령줄이 우리 것인가**까지 확인한다(`ops/lib.sh`의 `running_pid`). PID는 재사용되므로, 남은 PID 파일을 믿고 kill하면 무관한 프로세스가 죽는다. launchd가 대신 해 주던 판정이다.
- **MUST: `run-app.sh`는 `exec`으로 JVM이 된다.** 중간에 무언가를 끼우면 PID 파일이 그 중간 프로세스를 가리키고, 그것을 죽여도 앱은 남는다. `caffeinate`를 여기가 아니라 `start.sh`에서 `-w`로 따로 붙이는 이유가 그것이다 — `caffeinate`는 시그널을 자식에게 넘기지 않는다.
- **MUST NOT: 터널 토큰을 `--token <값>`으로 넘기지 않는다.** `ps`에 그대로 뜬다. `--token-file`로 600 파일을 건넨다.
- **MUST NOT: `pkill -f java`류로 끄지 않는다.** 이 저장소와 무관한 JVM까지 함께 죽는다. `ops/stop.sh`가 PID를 특정해 끈다.

## 검증

```bash
ops/start.sh                                            # 앱 + 터널
ops/status.sh                                           # 둘 다 "돈다", 자동기동 "없다"
curl -s https://screener.na1011.net/actuator/health      # 밖에서 {"status":"UP"}

# kill이 실제로 듣는가 — KeepAlive가 사라졌다는 증거다. ADR-0033의 같은 자리가 반대를 쟀다.
kill "$(cat ~/Library/Application\ Support/stock-screener/app.pid)"
sleep 40 && curl -s --max-time 2 localhost:8080/actuator/health   # 응답이 없어야 한다

ops/start.sh && ops/stop.sh
lsof -nP -iTCP:8080 -sTCP:LISTEN                        # 비어 있어야 한다

# 운영 구성이 여전히 기동하는가 (ADR-0033의 규칙이 유지를 요구한다)
./gradlew test --tests "com.stockscreener.pipeline.scheduler.*"
```

**이 결정은 저장소를 봐서는 지켜지는지 알 수 없다.** 자동 기동은 저장소 밖 세 곳에 등록되고, 되살아나도 **코드에는 아무 흔적이 없다.** 그래서 전제로 건다.

```premise 자동 기동 등록이 되살아나지 않았다
found=""
[ -f "$HOME/Library/LaunchAgents/com.stockscreener.app.plist" ] && found="$found app-LaunchAgent"
[ -f "/Library/LaunchDaemons/com.cloudflare.cloudflared.plist" ] && found="$found cloudflared-LaunchDaemon"
[ -n "$(crontab -l 2>/dev/null)" ] && found="$found crontab"
[ -z "$found" ] && echo "OK 자동 기동 등록 0건" || echo "자동 기동이 되살아났다:$found — ADR-0043은 0건을 전제한다"
```

**MUST: 이 침묵을 "전수 확인"으로 읽지 않는다.** 세 경로는 열거이고 그것이 한계다 — 로그인 항목이나 다른 이름의 데몬은 잡지 못한다. 여기서 조용하다는 것은 **아는 세 곳이 비었다**는 뜻뿐이다.

## 배경

**사용자가 이것을 기억하지 못한 채 발견했다**(2026-08-11). 맥북을 켜면 자기가 아무것도 하지 않았는데 서버가 떠 있고 밖으로 공개돼 있었다. 요청은 "배포와 서버 가동은 무조건 명시적인 터미널 명령으로, 끄고 싶을 때 끈다"였다.

**그 상태를 만든 것은 ADR-0033이고, 그때는 그것이 맞았다.** Phase 6의 "재부팅 후 사람 개입 없이 뜬다"가 판정 기준이었고 spec §6이 "부팅 시 자동 기동"을 요구사항으로 적고 있었다. **이번에 뒤집힌 것은 구현이 아니라 그 요구사항이다** — 사용자가 정본 개정을 승인했고(2026-08-11), spec §6과 `phase-criteria.md`의 해당 판정을 함께 고쳤다.

**`KeepAlive`가 특히 문제였다.** `SuccessfulExit=false`라 0이 아닌 코드로 죽으면 30초 뒤 되살아나고, `kill`이 보내는 SIGTERM이 정확히 그 경우다. 즉 **끄는 정상적인 수단이 안 들었고**, 끄려면 `launchctl bootout`을 알아야 했다. 해제 시점의 `launchctl print`에 `last exit code = 143`이 남아 있었다 — 누군가 이미 그것을 시도했다는 뜻이다.

## 버린 대안

- **plist를 남기고 `RunAtLoad`만 `false`로** — 등록된 서비스가 그대로 남는다. 사용자의 요구는 "안 뜨게 하는 것"만이 아니라 **"뭐가 등록돼 있는지 눈으로 확인할 수 있는 것"**이었고, `ls ~/Library/LaunchAgents`가 그 답이 되려면 아무것도 없어야 한다. `KeepAlive`도 함께 남아 `kill`이 여전히 안 듣는다.
- **cloudflared 데몬은 남긴다** — 앱이 없으면 502만 내므로 무해해 보이지만, **부팅 시 root로 뜨는 유일한 것**이고 앱을 켜는 순간 곧바로 공개된다. "내가 켠 것만 돈다"를 반쯤만 지키는 상태가 된다. 대가로 해제에 sudo 한 번이 필요하지만, 그 뒤로는 토큰을 `.env`에서 스테이징하므로 매번은 아니다.
- **`launchd`를 `KeepAlive` 없이 남겨 로그·PID 관리만 맡긴다** — 얻는 것이 stdout/stderr 리다이렉트뿐이고 그것은 `>>` 두 개다. 대신 "등록된 서비스가 있다"는 상태가 남는다.

## 결과

- **쉬워지는 것**: 켜고 끄는 것이 `ops/start.sh`·`ops/stop.sh`다. `kill`이 그대로 듣는다. 무엇이 자동으로 뜨는지 `ops/status.sh` 한 줄이 답한다. 배포가 터널을 건드리지 않아 재배포 중 밖에서 보이는 시간이 짧다.
- **어려워지는 것**: 재부팅하면 사람이 다시 띄워야 한다. 크래시하면 내려간 채로 있다. **가용성이 사람의 기억에 달렸다** — 온프레미스의 대가를 갚지 않기로 한 것이고, 그 판단은 spec §6에 적혀 있다.
- **IMPORTANT — 배치가 앱 프로세스 안에 있다.** `run-app.sh`가 `--pipeline.scheduler.enabled=true`로 켜므로, **평일 13시 30분에 앱이 꺼져 있으면 그날 실행이 통째로 빠진다**(ADR-0042). 증상은 즉시 나오지 않는다 — 0건이어도 배치는 SUCCESS고 이력도 초록이다. 완화는 이미 있다: ADR-0042의 **15일 창**이 뒤늦게 켠 실행에서 결손을 메운다. **즉 15일에 한 번은 13시 30분에 켜져 있어야 한다.**
- **되돌리려면**: 앱 쪽 plist 생성 스크립트는 커밋 `5a403da`가 지운 것이 원본이다(`git show 5a403da^ --stat`이 경로를 낸다). 터널은 `sudo cloudflared service install <TOKEN>`. 그리고 spec §6과 `phase-criteria.md`의 Phase 6 운영 판정을 되돌린다.
