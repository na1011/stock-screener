#!/bin/bash
# main 현재 HEAD + p0 + p1 모의 병합(--shared 클론 · 원 저장소 ref 무변경) — Q와 경로별 단언
cd "$1" || exit 2
P0=f6a2ecb8
P1=e341c70c
echo "main=$(git rev-parse --short HEAD) p0=${P0:0:8} p1=${P1:0:8}"
q() {
  B=$(git merge-base HEAD^1 HEAD^2); P=$(git diff --name-only $B HEAD^2 | sort); MM=$(git diff --name-only $B HEAD^1 | sort)
  Q=$(comm -12 <(echo "$P") <(echo "$MM"))
  echo "$1: B=$(git log --oneline -1 $B | cut -c1-70) |P|=$(echo "$P" | grep -c .) |M|=$(echo "$MM" | grep -c .) Q=[$(echo $Q)]"
  v=0
  for f in $(comm -23 <(echo "$P") <(echo "$MM")); do
    [ "$(git rev-parse HEAD:$f 2>/dev/null)" != "$(git rev-parse HEAD^2:$f 2>/dev/null)" ] && { echo "  위반 P∖Q $f"; v=$((v+1)); }
  done
  for f in $(git diff --name-only HEAD^1 HEAD); do echo "$P" | grep -qx "$f" || { echo "  위반 P밖 $f"; v=$((v+1)); }; done
  echo "  단언 위반=$v · 충돌표지 파일=$(git grep -l -e '^<<<<<<< ' -e '^>>>>>>> ' HEAD -- . | wc -l | tr -d ' ')"
  for f in $Q; do echo "  Q $f: HEAD=p1판? $([ "$(git rev-parse HEAD:$f)" = "$(git rev-parse HEAD^2:$f)" ] && echo 같음 || echo 다름) · =main판? $([ "$(git rev-parse HEAD:$f)" = "$(git rev-parse HEAD^1:$f)" ] && echo 같음 || echo 다름)"; done
}
git -c user.name=sim -c user.email=sim@example.invalid merge -q --no-ff --no-edit "$P0" -m "merge p0 (sim r18)"; echo "m1 rc=$?"; git diff --name-only --diff-filter=U; q M1
git -c user.name=sim -c user.email=sim@example.invalid merge -q --no-ff --no-edit "$P1" -m "merge p1 (sim r18)"; echo "m2 rc=$?"; echo "충돌 경로: $(git diff --name-only --diff-filter=U | tr '\n' ' ')"
if git diff --name-only --diff-filter=U | grep -q .; then echo "(충돌 — 커밋 안 함 · 단언 생략)"; else q M2; fi
