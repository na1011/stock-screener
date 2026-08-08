#!/bin/bash
#
# 앱을 launchd에 등록해 로그인 시 자동으로 뜨게 한다. sudo가 필요 없다.
#
# LaunchAgent(사용자)이지 LaunchDaemon(시스템)이 아닌 이유는 **FileVault가 켜져 있기
# 때문**이다. FileVault가 켜진 맥은 부팅 직후 디스크가 잠겨 있어 LaunchDaemon도 뜨지
# 못한다 — 사전 부팅 화면에서 사람이 암호를 넣어야 볼륨이 열리고, 그 인증이 곧 로그인이다.
# 즉 "로그인 전에도 뜬다"는 데몬의 유일한 이점이 이 기기에서는 성립하지 않는다.
# 대신 sudo 없이 설치·재시작·해제가 되는 이점만 남는다. 근거는 ADR-0033.
#
#   설치:   ops/install-agent.sh
#   해제:   ops/install-agent.sh --uninstall
#   상태:   launchctl print gui/$(id -u)/com.stockscreener.app

set -euo pipefail

LABEL="com.stockscreener.app"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/stock-screener"
DEPLOY="$HOME/Library/Application Support/stock-screener"
DOMAIN="gui/$(id -u)"

if [[ "${1:-}" == "--uninstall" ]]; then
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  if [[ -f "$PLIST" ]]; then
    rm "$PLIST"
    echo "해제했다: $PLIST"
  else
    echo "등록되어 있지 않다."
  fi
  exit 0
fi

mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR"

# 실행 산출물을 TCC 밖으로 먼저 내보낸다. 이유는 ops/stage.sh 머리에 있다.
"$ROOT/ops/stage.sh"

cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>

  <!-- 저장소가 아니라 스테이징 사본을 가리킨다. ~/Desktop은 TCC 보호 경로라
       launchd가 띄운 프로세스는 그 아래를 읽지 못한다(ops/stage.sh 참조).
       시크릿은 여기 적지 않는다 — run-app.sh가 .env를 읽는다. 이 파일은 평문이고
       백업·동기화·화면 공유를 그대로 따라다닌다. -->
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$DEPLOY/run-app.sh</string>
  </array>

  <key>WorkingDirectory</key>
  <string>$DEPLOY</string>

  <key>RunAtLoad</key>
  <true/>

  <!-- 0이 아닌 코드로 죽으면 다시 띄운다. true(무조건 재시작)로 두면 --uninstall 전에
       수동으로 멈출 방법이 없어진다. -->
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>

  <!-- 기동 즉시 죽는 상태에서 초당 재시작이 도는 것을 막는다. -->
  <key>ThrottleInterval</key>
  <integer>30</integer>

  <!-- 이것이 없으면 기동 실패가 아무 데도 남지 않는다. -->
  <key>StandardOutPath</key>
  <string>$LOG_DIR/stdout.log</string>
  <key>StandardErrorPath</key>
  <string>$LOG_DIR/stderr.log</string>
</dict>
</plist>
PLIST_EOF

# bootout → bootstrap 순서다. 이미 등록된 상태에서 bootstrap만 하면 "service already
# loaded"로 실패하고, 그러면 plist는 새것인데 도는 것은 옛것인 상태가 된다.
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
launchctl bootstrap "$DOMAIN" "$PLIST"

echo "등록했다: $PLIST"
echo "로그:     $LOG_DIR/{stdout,stderr}.log"
echo "상태:     launchctl print $DOMAIN/$LABEL"
echo
echo "기동 확인(최대 60초):"
for _ in $(seq 1 60); do
  if curl -sf --max-time 2 http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "  OK — $(curl -s http://localhost:8080/actuator/health)"
    exit 0
  fi
  sleep 1
done
echo "  아직 응답이 없다. $LOG_DIR/stderr.log를 본다." >&2
exit 1
