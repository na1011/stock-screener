---
id: ADR-0139
status: 채택됨
date: 2026-09-11
applies_to:
  - "ops/lib.sh"
  - "ops/start.sh"
  - "ops/status.sh"
  - "ops/rotate-logs.sh"
---

# ADR-0139. 로그 회전은 언제나 제자리 truncate다

## 결정

`ops/start.sh`가 여는 세 로그(`stdout.log`·`stderr.log`·`tunnel.log`)에 회전을 넣되,
**rename 없이 언제나 `gzip` + 제자리 `: >`로 한다.** 임계는 파일별로 다르고
(`stdout` 8 MiB / `tunnel` 2 MiB / `stderr` 256 KiB), 세대는 압축본으로 남긴다.
회전은 `ops/start.sh`가 기동 직전에, 그리고 `ops/rotate-logs.sh`를 손으로 칠 때만 돈다.

## 규칙

- **MUST: 언제나 제자리 truncate다. 프로세스 상태로 갈래를 만들지 않는다.**
  도는 JVM의 fd는 경로가 아니라 **inode**를 붙들고 있어 rename·`mv`·`rm`은 전부
  새 파일을 0바이트로 남기고 앱은 사라진 옛 파일에 계속 쓴다. 「살아 있으면 truncate,
  아니면 mv」로 가르려면 그 판정이 서야 하는데 **`lsof -t`가 「아무도 안 열었다」와
  「판정 실패」를 둘 다 종료코드 1 + 빈 출력으로 낸다**(§검증). 갈래가 버는 것은 한
  바이트 미만의 유실이고, 틀린 쪽으로 가면 **링크 수 0인 inode에 계속 써서 어떤 경로로도
  안 보이고 디스크도 JVM이 죽을 때까지 안 돌아온다.** 비대칭이 갈래를 없애라고 정한다.
- **MUST: 압축본을 `.tmp`에 쓴 뒤 원자적으로 옮기고, 그것이 성공했을 때만 자른다**(`&&`).
  `ops/stage.sh`가 jar에 대해 하는 것과 같다. 이 한 줄이 **잘린 `.gz`가 세대 자리에
  들어가는 갈래**와 **`gzip` 실패(ENOSPC 포함) 시 원본을 비우는 갈래**를 동시에 닫는다.
- **MUST: 임계를 파일별로 둔다.** 하나로 묶으면 `tunnel.log`(14.4 KB/일)는 582일,
  `stderr.log`(34일간 성장 0)는 영원히 안 걷혀 **「세 로그에 상한을 뒀다」가 거짓이 된다.**
  값은 `ops/lib.sh` 한 자리에 모은다 — 「한 곳」이지 「한 개」가 아니다.
- **MUST: 회전 호출을 조기 return **뒤**에 둔다.** `start.sh`는 이미 떠 있으면 `return 0`으로
  성공을 내므로, 앞에 두면 **`ops/start.sh`를 한 번 더 친 것만으로 도는 앱의 로그가 잘린다.**
- **MUST: 회전 실패가 기동을 막지 않게 한다**(`|| true`). `start.sh`는 `set -e`이고,
  회전 실패가 기동 실패로 보이면 원인을 엉뚱한 곳에서 찾게 된다.
- **MUST NOT: 락 해제를 `trap ... RETURN`에 맡기지 않는다.** bash 전용 시그널이라 다른 셸에서
  source하면 `undefined signal`로 죽고 **락이 남아 이후 회전이 영구히 조용히 skip된다**
  (실측으로 12회 중 11회가 그렇게 넘어갔다). 명시적으로 풀고, 5분 넘은 락은 걷는다.
- **MUST NOT: 자동 삭제나 차단으로 승격하지 않는다.** `ops/dump-db.sh`가 덤프에 대해
  *"자동으로 지우지 않는다. 지울 것은 사람이 고른다"*로 정한 것과 같은 판단이다.
  회전이 사람 명령에서만 도는 이상 **초과를 아는 유일한 경로가 `ops/status.sh`의
  `== 로그 ==` 절**이고, 그것이 이 결정의 실제 방어선이다.
- **MUST NOT: 이것을 「상한」이라고 적지 않는다.** 기동이 없으면 계속 큰다
  (ADR-0043 §결과 — *"가용성이 사람의 기억에 달렸다"*).

## 검증

```premise 세 리다이렉션이 모두 append다
cd "$(git rev-parse --show-toplevel)" && grep -qF '>>"$LOG_DIR/stdout.log" 2>>"$LOG_DIR/stderr.log"' ops/start.sh && grep -qF '>>"$LOG_DIR/tunnel.log" 2>&1' ops/start.sh && echo "OK 세 리다이렉션이 append다" || echo "리다이렉션이 > 로 바뀌었다 — O_APPEND가 아니면 truncate가 9 MiB짜리 NUL 구멍을 만들고, 겉보기 크기가 안 줄어 회전이 영원히 다시 발화한다"
```

**이 펜스가 크기나 `lsof`가 아닌 이유.** 크기를 재면 §규칙이 정상으로 인정하는 8~11 MiB
구간에서 **매 세션 붉는다** — 템플릿이 금지한 「의도한 동작에 우는 펜스」다. `lsof`도 못 쓴다:
ADR-0043이 *"죽으면 죽은 채로 있다"*를 설계로 못 박아 **앱이 꺼져 있는 것이 정상**이고
그때 판정 불가가 된다. 위험은 「누군가 `>>`를 `>`로 바꾸는 것」 하나이고 그것은 소스에
그대로 있으므로, 저장소에서 결정적으로 답하는 쪽이 빠르고 조용하다.

`lsof -t`가 갈래를 못 만드는 근거(2026-09-11 실측):

| 입력 | stdout | 종료코드 |
|---|---|---|
| 도는 앱이 연 `stdout.log` | `38860` | 0 |
| 존재하지만 아무도 안 연 파일 | (없음) | **1** |
| 존재하지 않는 파일 | (없음) | **1** |

회전이 실제로 fd를 살렸는지는 **inode 동일성**으로 묻는다. 시간 기반(`sleep 60 && wc -c`)은
못 쓴다 — 14:00~14:12 밖에는 하루 종일 60~80줄이라 멀쩡해도 0이 나온다.

```bash
stat -f %i ~/Library/Logs/stock-screener/stdout.log
lsof -p "$(cat "$HOME/Library/Application Support/stock-screener/app.pid")" -a -d 1 -F i
```

## 배경

`start.sh`가 `>>`로 이어붙이기만 해 `stdout.log`가 34일에 9.1 MiB까지 왔고, **2026-09-02에
`AdjustmentRecalculator`가 켜지면서 평일 1.02 MiB/거래일로 계단이 섰다**(그 전엔 4~118 KB).
그런데 그냥 지울 수 없다 — ADR-0125가 이 파일을 진단의 정본으로 지목하고, 실제로 2026-09-05
리밸런싱 거부는 `batch_execution_failure`에 **0행**이고 `batch_execution`에 `PARTIAL_SUCCESS`로만
남아 **「어느 유니버스가 왜」가 로그에만 있다.** 그 줄은 파일 18,559번째라 README가 처방하는
`tail -50`으로는 원리적으로 안 보인다. 그래서 회전은 지우는 것이 아니라 압축해 세대로 미는
것이고, 지난 이력은 `zgrep`으로 뒤진다(`zgrep`·`zcat`·`gzcat`·`zless` 넷 다 이 기계에 있다).

## 버린 대안

- **`newsyslog`(macOS 기본)** — 플래그 전집합이 `B C D G J N U Z -`뿐이라 **copytruncate가
  없다.** 항상 rename이므로 fd가 옛 inode를 따라가 `rm`과 **같은 방식으로** 실패한다.
  보통 데몬은 `pid_file`+`sig_num`으로 재개방하지만 **셸 `>>`로 받은 fd는 JVM이 재개방할
  수단이 없다.** `/etc/newsyslog.d/`가 `root:wheel`이라 sudo도 필요하다.
- **`logrotate`** — 미설치라 새 의존성이고, 얻는 것이 `copytruncate` 지시자 하나인데 그것은
  이 기계에 이미 있는 두 명령이다. 주기 실행에 crontab이 필요한데 **ADR-0043 L45-51의
  `premise`와 `ops/status.sh` L54가 `crontab -l`의 「비어 있는가」만 보고 내용을 안 본다** —
  회전용 한 줄이라도 넣으면 매 세션 `자동기동: 되살아났다 — crontab`이 뜬다.
  **MUST NOT: 이것을 「ADR-0043이 crontab을 금지한다」로 읽지 않는다.** 그 문서 L19의 금지
  주어는 *"부팅·로그인에 걸리는 **자동 기동**"*이고 crontab은 수단의 열거다 — 회전 crontab은
  그 문장에 **안 닿는다.** 기각 사유는 거짓 경보이지 금지가 아니다.
- **`rotatelogs`**(OS 기본이라 새 의존성은 아니다) — 파이프 소비자라 `$!`가 JVM이 아니라
  파이프라인 끝을 가리킨다. `ops/start.sh` L44-46이 `caffeinate`에 대해 적은 함정과 같고,
  그러면 `stop.sh`의 kill이 앱에 안 닿는다.
- **launchd 사용자 에이전트** — ADR-0043 L67이 이미 다뤘다: *"`launchd`를 `KeepAlive` 없이
  남겨 로그·PID 관리만 맡긴다 — 얻는 것이 stdout/stderr 리다이렉트뿐이고 그것은 `>>` 두 개다.
  대신 '등록된 서비스가 있다'는 상태가 남는다."* 게다가 `ops/status.sh` L52가
  `com.stockscreener.app.plist`라는 **이름 하나만** 보므로 다른 이름의 에이전트는 어느
  계수기에도 안 잡힌다 — 같은 ADR L53이 경고한 「열거의 한계」다.
- **logback 롤링 파일 appender** — 저장소에 로깅 설정이 **0건**이라 새로 넣는 것이고,
  `logging.file.name`을 켜도 **콘솔 appender가 안 꺼져** 로그가 두 곳에 쌓여 디스크가 오히려
  는다. 콘솔을 끄려면 문서화되지 않은 편법이나 커스텀 `logback-spring.xml`이 필요하고,
  꺼도 **JVM이 logback 밖에서 stdout에 쓰는 것**(배너·GC·스택덤프)이 남아 `stdout.log` 회전은
  여전히 필요하다. 문제를 줄이지 못하고 표면만 늘린다.

## 결과

- **쉬워지는 것**: 로그가 임계에서 멈추고, 지난 이력이 압축본으로 남아 `zgrep -A`로 원인
  스택까지 뒤질 수 있다. `ops/status.sh`가 처음으로 로그를 센다. 22.3:1로 압축되므로
  10세대가 지금 한 파일보다 작다.
- **어려워지는 것**: `tail`이 회전 직후에는 아무것도 안 낸다. 그래서 README의 진단 블록에
  `zgrep` 경로를 함께 적었다. **`gzip` 읽기와 truncate 사이의 한 바이트 미만이 유실된다** —
  평일 평균 12 B/초, gzip 0.04초 기준이고 **14:00~14:12 배치 구간은 순간 ~1.4 KB/s라 그때
  돌리면 한두 줄이 빈다.** MUST NOT: 이것을 「유실 없음」으로 적지 않는다.
- **보존 지평의 비대칭.** `batch_execution`은 2026-08-06부터 378행이 있고 **삭제 기제가 없다**
  (`BatchExecutionSweeper`는 `UPDATE`뿐). DB 지평은 단조 증가, 로그 지평은 10세대 고정이라
  **언젠가 반드시 갈린다.** 지금은 로그가 넉넉히 덮지만, 로그량이 배가 되면 지평은 반이 된다
  (09-02에 실제로 그런 계단이 있었다). 그날 무엇을 할지는 이 결정 밖이다.
- **범위는 `ops/start.sh`가 여는 세 파일뿐이다.** `/opt/homebrew/var/log/postgresql@16.log`는
  실측 **52 MiB**에 회전이 없고(`stdout.log`의 5.7배) `~/.gradle/daemon`은 137 MB다. 둘 다
  Homebrew·Gradle이 관리해 이 저장소의 `ops/` 기제가 안 닿는다 — **재고 범위 밖으로 둔 것이지
  안 잰 것이 아니다.** postgres는 SIGHUP로 재개방하므로 rename 방식이 쓰일 수 있으나
  **그것은 재보지 않았다.**
- **되돌리려면** 이 커밋들을 되돌린다. 압축 세대는 남으므로 `gzcat`으로 합치면 이력이 복원된다.
