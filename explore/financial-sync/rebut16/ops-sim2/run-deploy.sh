#!/bin/bash
# deploy.sh(main d023cd9f 사본 · start.sh·gradlew 자리표시)를 가짜 홈에서. 앱 자리는 내가 띄운 sleep 하나 · psql·date·ps·jcmd·curl은 shim.
O="$(cd "$(dirname "$0")" && pwd)"
ep() { /bin/date -j -f "%Y-%m-%d %H:%M:%S" "$1" +%s; }
endline() { echo "$1+09:00  INFO $2 --- [stock-screener] [   scheduling-1] c.s.p.scheduler.PipelineScheduler        : 크론 발화를 마쳤다. 크론=$3"; }
scenario() { local name="$1" seq="$2" ignore_term="$3" move="$4"
  local home="$O/home-$name-$$"; mkdir -p "$home/Library/Application Support/stock-screener" "$home/Library/Logs/stock-screener"
  local DEP="$home/Library/Application Support/stock-screener"
  if [ "$ignore_term" = 1 ]; then bash -c 'trap "" TERM; exec -a "java -jar /sim/app.jar --pipeline.scheduler.enabled=true" /bin/sleep 600' &
  else bash -c 'exec -a "java -jar /sim/app.jar --pipeline.scheduler.enabled=true" /bin/sleep 600' & fi
  local APP=$!; sleep 0.3
  echo "$APP" > "$DEP/app.pid"; echo "OLD-JAR" > "$DEP/app.jar"; echo "OLD_ENV=1" > "$DEP/.env"
  printf 'commit=OLDCOMMIT0000\ndirty=false\ncaptured=build\nstaged_at=2026-10-05 15:43:00\n' > "$DEP/app.jar.commit"
  { endline 2026-10-06T13:53:51.300 $APP runDailyUpdate; endline 2026-10-06T14:12:21.290 $APP runCorporateActionChain
    endline 2026-10-06T14:15:00.020 $APP runHeavyJobOfTheDay; endline 2026-10-06T14:18:01.870 $APP deriveReconcileInputs
    endline 2026-10-06T14:26:11.470 $APP reconcileIndicators; endline 2026-10-06T15:30:00.295 $APP checkJobFreshness; } > "$home/Library/Logs/stock-screener/stdout.log"
  local n=$(ep "2026-10-06 16:00:00") s=$(ep "2026-10-05 15:43:37"); local el=$((n - s))
  local et=$(printf "%d-%02d:%02d:%02d" $((el/86400)) $((el%86400/3600)) $((el%3600/60)) $((el%60)))
  echo "=== $name · RUNNING 차례=[$seq] · TERM 무시=$ignore_term · 빌드 중 커밋=$move · 앱 자리 PID $APP"
  local t0=$(/bin/date +%s)
  ( cd "$O/repo" && HOME="$home" PATH="$O/bin:/usr/bin:/bin:/usr/sbin:/sbin" FAKE_NOW=$n FAKE_ETIME=$et FAKE_UPTIME="$el.0" \
      FAKE_STATE="$home/psql.state" FAKE_RUNNING_SEQ="$seq" FAKE_MOVE=$move GIT_AUTHOR_NAME=sim GIT_AUTHOR_EMAIL=sim@example.invalid \
      GIT_COMMITTER_NAME=sim GIT_COMMITTER_EMAIL=sim@example.invalid bash "$O/repo/ops/deploy.sh" ); echo "deploy rc=$? · $(( $(/bin/date +%s) - t0 ))초"
  echo "--- 뒤: 앱 자리 PID $APP 살아 있나: $(kill -0 $APP 2>/dev/null && echo 예 || echo 아니오) · app.pid 파일: $(cat "$DEP/app.pid" 2>/dev/null || echo 없음)"
  echo "--- 뒤: app.jar = $(cat "$DEP/app.jar") · .env = $(cat "$DEP/.env" | head -1)"; echo "--- 뒤: app.jar.commit:"; sed 's/^/    /' "$DEP/app.jar.commit"
  echo "--- sim repo HEAD = $(git -C "$O/repo" rev-parse --short HEAD)"
  kill -9 $APP 2>/dev/null; wait $APP 2>/dev/null; echo "(내가 띄운 PID $APP 정리: $(kill -0 $APP 2>/dev/null && echo 남음 || echo 끝))"
}
scenario E0-control "0 0" 0 0
scenario E2-moved-during-build "0 0" 0 1
# scenario E1-stop-times-out "0 0" 1 0
