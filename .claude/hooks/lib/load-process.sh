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
  pids=$(pgrep -f '(^|[[:space:]])--job=(universe|initial-load|backfill)|spring\.profiles\.active=[^[:space:]]*bootstrap' 2>/dev/null) || true
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
