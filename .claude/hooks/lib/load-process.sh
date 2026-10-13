#!/bin/bash
# 훅 공용: **지금 적재 배치가 돌고 있는가**를 하나의 답으로 낸다.
#
# 소비자가 둘이라 여기 둔다(`warn-load-running.sh`·`inject-progress.sh`). `bash-text.sh`와
# 같은 이유다 — 판정을 훅마다 따로 하면 "적재가 도는가"에 서로 다른 답을 갖게 되고,
# 갈린 사실은 적재가 실제로 도는 날에야 드러난다.
#
# 살아 있는 프로세스만 신호로 쓴다. 시작할 때 마커 파일을 쓰고 끝날 때 지우는 방식은
# **비정상 종료에서 마커가 남는다** — 이 저장소는 이미 같은 병을 `batch_execution`의
# 고아 RUNNING 행으로 앓고 있다. 죽으면 저절로 사라지는 신호가 아니면 쓰지 않는다.

# 적재 JVM의 PID를 한 줄로 낸다(공백 구분).
#
#   0 → 하나 이상 찾았다(stdout에 PID)
#   1 → 없다
#   2 → **판정 불가.** 호출자는 이것을 "없다"로 읽지 않는다.
stray_load_pids() {
  command -v pgrep >/dev/null 2>&1 || return 2
  command -v ps    >/dev/null 2>&1 || return 2

  local pids p comm out=""

  # 신호 둘을 함께 본다.
  #
  # `--job=`이 주 신호다. `BootstrapRunner`가 `ApplicationArguments`로만 읽으므로 어떤
  # 기동 방식에서도 **반드시 argv에 남는다**. 프로파일만 보면
  # `SPRING_PROFILES_ACTIVE=bootstrap ./gradlew bootRun`을 놓치는데, `build.gradle`의
  # `applyDotEnv`가 `.env`를 환경변수로 주입하는 배선을 이미 갖고 있어 가상의 형태가 아니다.
  # `pgrep -f`는 환경변수를 보지 못한다.
  #
  # 패턴이 `-`로 시작하지 않게 괄호로 연다 — pgrep이 옵션으로 읽는다.
  # MUST: `BootstrapRunner`의 `switch`에 잡을 더하면 여기도 함께 더한다. 실제로 넷이 빠져
  # 있었다(`financial-refill`, 그리고 2026-08-24에 `full-statement-refill`·`market-index`·
  # `governance-load`·`governance-derive`).
  #
  # **빠졌을 때 무엇이 안 잡히는지 정확히 적는다** — 정상 기동은 아래 프로파일 가지가 잡는다
  # (실측: 잡 이름 13개 전부 매치). 안 잡히는 것은 위 환경변수 형태 하나이고, 그때는 두 가지가
  # 다 침묵해 몇 시간짜리 적재 위에 다른 적재를 경고 없이 띄우게 된다.
  # `corporate-action-review`·`-judge`는 몇 초에 끝나 "적재가 돈다"의 대상이 아니고, 교체가
  # 앵커되지 않아 `corporate-action` 가지가 이미 그 argv를 잡는다. 그래도 이름을 적는 이유는
  # **탐지가 아니라 `check-doc-staleness.sh` §5c의 거울**이다 — 그 검사는 이 목록과
  # `BootstrapRunner`의 `switch`를 집합으로 대조하므로 빠지면 매 세션 발화한다.
  pids=$(pgrep -f '(^|[[:space:]])--job=(universe|universe-restore|initial-load|financial-refill|full-statement-refill|company-profile|corporate-action|corporate-action-review|corporate-action-judge|adjustment-rebuild|market-index|backfill|indicator-reconcile|governance-load|governance-derive|share-basis-derive|market-calendar|bar-gap-review|filing-listing)|spring\.profiles\.active=[^[:space:]]*bootstrap' 2>/dev/null) || true
  [ -z "$pids" ] && return 1

  # **실행파일이 java인 것만 남긴다. 이 필터가 없으면 훅이 자기 자신을 적재로 읽는다.**
  # Bash 도구의 셸은 `/bin/zsh -c … eval '<명령 전문>'` 형태라 **명령 텍스트를 자기 argv에
  # 싣는다.** 그래서 위 패턴을 언급하기만 한 도구 호출(문서화·grep·커밋 메시지)이 전부
  # 매칭된다. 첫 발화가 거짓이면 그 뒤의 참 발화도 같은 무게로 읽히지 않는다.
  #
  # gradle 클라이언트 JVM도 함께 남는데 그것은 오탐이 아니다 — 그 프로세스가 살아 있는
  # 동안 빌드가 진행 중이고, 포크된 적재 JVM은 그 자식이다.
  # MUST NOT: `for p in $pids`로 쓰지 않는다. 훅은 전부 `#!/bin/bash`지만 **기본 셸이
  # zsh이고 zsh는 단어 분할을 하지 않아**, 손으로 확인하면 같은 코드가 0건을 낸다. 이
  # 저장소는 그 0건을 믿고 멀쩡한 glob 29개를 깨진 것으로 오판한 전례가 있다.
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    comm=$(ps -o comm= -p "$p" 2>/dev/null) || continue
    case "${comm##*/}" in
      java) out="${out}${p} " ;;
    esac
  done <<< "$pids"

  [ -z "$out" ] && return 1
  printf '%s\n' "${out% }"
  return 0
}

# PID가 뜬 지 몇 초 됐는지 낸다. 못 재면 1을 반환한다.
#
# "방금 내가 띄운 적재"와 "이미 돌고 있던 적재"를 가르는 데 쓴다. 둘을 구분하지 못하면
# 적재를 띄울 때마다 "이미 돌고 있다"가 뜬다.
#
# macOS ps에는 `etimes`(초)가 없고 `etime`(`[[dd-]hh:]mm:ss`)만 있어서 직접 푼다.
# `10#`을 붙이는 이유는 `08` 같은 값이 8진수로 읽혀 산술이 죽기 때문이다.
load_process_age_seconds() {
  local e d h m s
  e=$(ps -o etime= -p "$1" 2>/dev/null | tr -d ' ')
  [ -z "$e" ] && return 1
  d=0
  case "$e" in *-*) d=${e%%-*}; e=${e#*-} ;; esac
  case "$e" in
    *:*:*) h=${e%%:*}; m=$(printf '%s' "$e" | cut -d: -f2); s=${e##*:} ;;
    *:*)   h=0;        m=${e%%:*};                          s=${e##*:} ;;
    *)     return 1 ;;
  esac
  printf '%s\n' "$(( 10#$d * 86400 + 10#$h * 3600 + 10#$m * 60 + 10#$s ))"
  return 0
}
