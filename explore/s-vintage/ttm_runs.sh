#!/bin/zsh
cd /Users/na1011/dev/stock-screener
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
run() { local name=$1; shift; env "$@" python3 $S/ttm.py $S $S/$A $S/$B > $S/ev6/$name.txt 2>&1; echo "$name rc=$?"; }
A=base2.tsv; B=q4only.tsv
for truth in pit post; do
  ( run b-o-$truth-win TTM_TRUTH=$truth TTM_POP=$S/members.txt ) &
  ( run b-o-$truth-off4 TTM_TRUTH=$truth TTM_OFFSET=4 ) &
  ( run b-o-$truth-off20 TTM_TRUTH=$truth TTM_OFFSET=20 ) &
  ( run b-o-$truth-5y TTM_TRUTH=$truth TTM_DAYS=$S/days_5y_monthly.txt ) &
  wait
done
A=q4guard.tsv; B=q4only.tsv
( run g-o-post-win TTM_TRUTH=post TTM_POP=$S/members.txt ) &
( run g-o-post-off4 TTM_TRUTH=post TTM_OFFSET=4 ) &
( run g-o-post-off20 TTM_TRUTH=post TTM_OFFSET=20 ) &
( run g-o-post-5y TTM_TRUTH=post TTM_DAYS=$S/days_5y_monthly.txt ) &
wait
echo ALLDONE
