---
id: ADR-0034
status: 채택됨
date: 2026-08-09
applies_to:
  - "ops/**"
---

# ADR-0034. 저장소를 TCC 밖으로 옮기되, 스테이징은 남긴다

## 결정

저장소를 `~/Desktop/Study/stock-screener`에서 **`/Users/na1011/dev/stock-screener`**로 옮긴다. 그러면서도 실행 산출물을 `~/Library/Application Support/stock-screener`로 복사해 돌리는 구조는 **`.env` 사본까지 그대로 유지한다**. ADR-0033이 "옮기면 사본을 없앨 수 있다"고 예고했던 것을 뒤집는다.

## 규칙

- **MUST NOT: 저장소 경로를 plist가 직접 가리키게 하지 않는다.** ADR-0033은 이 규칙의 근거로 TCC를 들었고 그 근거는 이동으로 사라졌다. **규칙은 남고 근거만 바뀐다** — 이 기기는 개발 기기이면서 동시에 운영 기기라, 도는 것이 작업 트리를 가리키면 빌드가 배포가 된다.
- **MUST NOT: 스테이징의 `.env` 사본을 심볼릭 링크로 바꾸지 않는다.** 링크는 저장소 경로를 스테이징 트리에 다시 박는다. 저장소를 또 옮기거나 지우면 **그 순간이 아니라 몇 시간 뒤 아무 재시작 시점에** 앱이 뜨지 않는다. `run-app.sh`가 저장소를 전혀 참조하지 않는 것이 이 설계의 핵심 성질이고, 링크가 그것을 정확히 깬다.
- **MUST NOT: `~/Desktop`이 iCloud 동기화 대상이라고 다시 적지 않는다.** 2026-08-09 실측으로 꺼져 있음을 확인했다(`FXICloudDriveDesktop = 0`, `CloudDocs/Desktop`이 빈 껍데기). 이동으로 그 경로 자체를 떠났으므로 두 번 무효가 된 주장이다.
- **MUST: 코드를 고쳤으면 `ops/deploy.sh`를 돌린다.** `./gradlew build`만으로는 도는 인스턴스가 바뀌지 않는다. 이 함정은 이동 뒤에도 그대로다 — 오히려 이것이 스테이징을 남기는 대가다.

## 검증

```bash
# 1. plist가 저장소를 가리키지 않는다 (아무것도 나오지 않아야 한다)
grep -c 'dev/stock-screener' ~/Library/LaunchAgents/com.stockscreener.app.plist

# 2. 스테이징 사본이 저장소를 참조하지 않는다 (심볼릭 링크가 아니어야 한다)
test -f "$HOME/Library/Application Support/stock-screener/.env" \
  && ! test -L "$HOME/Library/Application Support/stock-screener/.env" && echo OK

# 3. 새 경로에서 훅 계층이 온전하다
bash .claude/hooks/lib/regression.sh          # 실패 0 · 건너뜀 0 (통과 수는 표가 자라므로 적지 않는다)

# 4. 이동이 배포 경로를 깨지 않았다 — 밖에서 물어야 갈린다
curl -s https://screener.na1011.net/actuator/health     # {"status":"UP"}
```

**이 문서를 쓴 시점에 확인된 것은 3번의 이동 *전* 기준선(33/33)뿐이다.** 나머지는 이동 직후에 돌린다 — 순서가 그래야 하는 이유는 아래 "배경"의 마지막 문단에 있다.

## 배경

**이동을 권한 사유는 둘이었는데, 착수 시점에 하나는 이미 사라져 있었다.** `TODO.md`가 든 첫 사유는 iCloud가 `build/` 산출물을 `X 2.class`로 복제해 테스트가 `wrong name`으로 깨진다는 것이었다. 그런데 Desktop & Documents 동기화는 이미 꺼져 있었고(`~/Library/Mobile Documents/com~apple~CloudDocs/Desktop`이 `.DS_Store`만 남은 빈 껍데기, 실제 `~/Desktop`과 inode가 다름), 저장소 안에 복제본도 `.icloud` 자리표시자도 없었다. **문서 대여섯 곳이 그 사유를 현재형으로 적고 있던 것이 실제 부채였다.** 남은 사유는 TCC 하나였고 그것이 이 이동의 근거다.

**그래서 "스테이징을 없앨 수 있는가"를 다시 물어야 했다.** ADR-0033은 스테이징을 TCC 때문에 만들었고, 이득이 하나 더 있다고 적으면서도(재빌드가 도는 인스턴스를 건드리지 않는다) 사본을 없앨 수 있다고 예고했다. **다시 재면 그 "부수 이득"이 사실은 주된 이유다.** 없애면 `./gradlew clean`이 도는 앱의 jar를 지우고, 30초 뒤 KeepAlive가 띄울 것을 못 찾는다 — **빌드의 부수 효과로 사이트가 내려가고 밖에서 502로만 보인다.** `stage.sh`의 `.tmp` → `mv` 원자적 교체도 함께 사라지고, `ls -t build/libs/*.jar | head -1`이 실험용 빌드를 골라 운영에 올릴 수 있게 된다.

**`.env` 사본이 남는 비용은 다시 재 보니 거의 0이다.** 두 파일은 md5까지 동일하고 둘 다 같은 사용자의 `600`(디렉터리 `700`)이다. 오히려 역전이 있다 — **`~/Desktop`을 떠나면 저장소 `.env`에서 TCC 보호가 벗겨진다.** 이제 더 잘 보호되는 쪽이 스테이징 사본이다. 배포해야 반영된다는 규칙이 jar와 시크릿에 똑같이 적용되는 일관성도 남는다.

**문서를 먼저 고치고 옮기는 순서에는 이유가 있다.** `CLAUDE_PROJECT_DIR`는 세션 시작에 확정되고 바뀌지 않으므로, `mv` 직후 그 값은 없는 경로를 가리킨다. `.claude/settings.json`의 훅 명령이 전부 `${CLAUDE_PROJECT_DIR}/.claude/hooks/…`라 **파일이 없어 실행되지 않고**, 실행된다 해도 `remind-adr.sh`의 `rel="${path#"${root}/"}"`가 접두어를 못 벗겨 `applies_to` 매칭이 **조용히** 실패한다. 그래서 편집·커밋은 이동 전에 끝내고 이동 후에는 셸 검증만 한다. **다음에 이 저장소를 또 옮기는 세션도 같은 순서를 밟아야 한다.**

## 버린 대안

- **`.env`를 심볼릭 링크로 바꿔 사본만 없앤다** — 위 규칙 참조. 평문 파일 하나를 줄이는 대가로 지연·비가시 실패를 산다.
- **스테이징을 통째로 걷고 plist가 저장소를 직접 가리키게 한다** — 가장 단순하지만 개발과 운영을 같은 트리에 묶는다. `deploy.sh`를 안 돌려도 반영된다는 이점은 실재하나, 그 대가가 "빌드가 사이트를 내린다"이면 교환이 성립하지 않는다.
- **`~/dev` 대신 `~/Documents` 아래** — Documents도 TCC 보호 경로이고 iCloud 동기화 후보다. 같은 문제를 다시 산다.

## 결과

- **쉬워지는 것**: TCC 회피가 더 이상 설계 제약이 아니다. `~/Desktop`에 있어서 생기던 함정(동기화 복제, launchd 접근 거부)이 원인 단에서 사라진다.
- **어려워지는 것**: 없다. `ops/` 스크립트는 전부 `$(dirname "${BASH_SOURCE[0]}")` 상대 유도라 한 줄도 바뀌지 않는다. plist도 `$HOME` 기준이라 재작성이 필요 없다.
- **따라오지 않는 것**: Claude Code의 프로젝트 상태는 경로를 키로 쓰므로 `~/.claude/projects/-Users-na1011-dev-stock-screener`로 복사해야 한다. Gradle의 `executionHistory.bin`에는 옛 절대경로가 박혀 있어 `.gradle/`을 비우고 다시 빌드해야 한다.
- **되돌리려면**: `mv` 한 번과 `ops/install-agent.sh`. plist가 저장소를 참조하지 않으므로 그게 전부다.
