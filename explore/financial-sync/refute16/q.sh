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
}
q M2
