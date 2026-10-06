#!/bin/bash
# crons-done.sh:81 의 줄(그대로)과 .claude/hooks/lib/load-process.sh 의 stray_load_pids 를 같은 순간에 비교한다.
R="$1"; REPO=/Users/na1011/dev/stock-screener
. "$REPO/.claude/hooks/lib/load-process.sh"
JAVA=/Users/na1011/Library/Java/JavaVirtualMachines/temurin-21.0.8/Contents/Home/bin/java
line() { ps -Ao pid=,command= | grep -E 'spring\.profiles\.active=bootstrap' | grep -v -e ' grep ' | awk '{print $1}' | tr '\n' ' ' || true; }
show() { local h; h=$(stray_load_pids); local rc=$?; echo "$1 | crons-done 줄: [$(line)] | 기존 도우미 stray_load_pids: rc=$rc [$h]"; }
show "K0 아무것도 안 띄움"
# K1 환경변수 프로파일 꼴(문서 밖이지만 도우미 주석이 「가상의 형태가 아니다」라 적은 것) — java · --job= 있음 · argv에 프로파일 없음
SPRING_PROFILES_ACTIVE=bootstrap "$JAVA" "$R/bootline/Sleep.java" --job=financial-refill & P1=$!; sleep 3
show "K1 SPRING_PROFILES_ACTIVE=bootstrap java … --job=financial-refill (PID $P1)"
kill $P1; wait $P1 2>/dev/null
# K2 프로파일 목록 꼴
"$JAVA" "$R/bootline/Sleep.java" --spring.profiles.active=local,bootstrap --job=financial-refill & P2=$!; sleep 3
show "K2 --spring.profiles.active=local,bootstrap (PID $P2)"
kill $P2; wait $P2 2>/dev/null
# K3 그 문자열을 명령 글자에 실은 셸(에이전트 Bash 도구가 zsh -c '<명령 전문>'으로 돈다 — 그 꼴) · java 아님
/bin/zsh -c "sleep 6; : --spring.profiles.active=bootstrap" & P3=$!; sleep 1
show "K3 zsh -c '…--spring.profiles.active=bootstrap' (PID $P3 · java 아님)"
wait $P3 2>/dev/null
# K4 문서가 쓰는 꼴(대조군) — java · argv에 프로파일
"$JAVA" "$R/bootline/Sleep.java" --spring.profiles.active=bootstrap --job=share-basis-derive & P4=$!; sleep 3
show "K4 java … --spring.profiles.active=bootstrap --job=share-basis-derive (PID $P4 · 대조군)"
kill $P4; wait $P4 2>/dev/null
show "K5 다 끈 뒤"
