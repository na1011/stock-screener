# 운영

맥북 한 대 위에서 앱과 터널을 살려 두는 방법. 결정의 근거는 [ADR-0033](../docs/adr/ADR-0033-launchd-user-agent.md)에 있다.

```
브라우저 ──HTTPS──▶ Cloudflare 엣지 ──터널──▶ cloudflared ──HTTP──▶ Spring :8080
                                            (LaunchDaemon,          (LaunchAgent,
                                             sudo로 설치됨)          ops/install-agent.sh)
```

## 명령

| 하려는 것 | 명령 |
|---|---|
| 등록(로그인 시 자동 기동) | `ops/install-agent.sh` |
| 해제 | `ops/install-agent.sh --uninstall` |
| 새 코드 배포 | `ops/deploy.sh` |
| 상태 | `launchctl print gui/$(id -u)/com.stockscreener.app` |
| 로그 | `~/Library/Logs/stock-screener/{stdout,stderr}.log` |
| 살아 있나(로컬) | `curl -s localhost:8080/actuator/health` |
| 살아 있나(밖에서) | `curl -s https://screener.na1011.net/actuator/health` |

**밖에서 묻는 것이 진짜 판정이다.** 로컬만 보면 cloudflared가 죽어 있어도 초록이다. 반대로 밖에서 502가 나면 터널은 살아 있고 앱이 죽은 것이다(터널이 죽었으면 아예 응답이 없다).

## 무엇이 자동이고 무엇이 아닌가

| 상황 | 복구 |
|---|---|
| 앱 프로세스가 죽음 | 자동 (`KeepAlive`, 30초 간격) |
| 로그아웃 → 로그인 | 자동 (`RunAtLoad`) |
| 잠자기 → 깨어남 | 자동 (프로세스가 그대로 살아 있다) |
| **콜드 부팅** | **사람이 FileVault 암호를 넣어야 한다** ↓ |

**FileVault가 켜져 있어 부팅 직후에는 디스크가 잠겨 있다.** 그 상태에서는 LaunchAgent도 LaunchDaemon도 뜨지 못한다 — cloudflared도 마찬가지다. 사전 부팅 화면에서 암호를 넣으면 볼륨이 열리고 둘 다 뜬다.

계획된 재부팅은 암호 없이 넘길 수 있다:

```bash
sudo fdesetup authrestart    # 지금 암호를 한 번 받고, 재부팅 후 자동으로 잠금 해제한다
```

## 절전

`run-app.sh`가 앱을 `caffeinate -s`로 감싼다. **전원이 연결된 동안만** 유휴 절전을 막으므로 배터리로 도는 동안에는 평소대로 잠든다.

뚜껑을 닫으면 `caffeinate`로도 잠든다. 닫아 둔 채 쓰려면 sudo가 필요하다:

```bash
sudo pmset -a disablesleep 1   # 되돌리기: sudo pmset -a disablesleep 0
```

**맥북이 자면 사이트도 함께 내려간다.** 온프레미스를 택한 대가이고(spec §6), 배치는 그래서 사람이 기기를 쓰는 시각(평일 20시)에 돌도록 옮겼다.

## 시크릿

**plist에 값을 적지 않는다.** `run-app.sh`가 저장소 루트의 `.env`를 읽는다. plist는 `~/Library/LaunchAgents` 아래 평문 파일이라 백업·동기화·화면 공유를 그대로 따라다닌다.
