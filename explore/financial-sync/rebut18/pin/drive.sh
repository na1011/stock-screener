#!/bin/bash
# 사용: drive.sh <변이 e5|e1> — 사본 r18/m<번호>에서 a(들임) → 판 8 → b(초록이어야) → c(범위 밖 본문만) 순으로 판 고정 시험을 돈다.
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
R=$S/wt-explore/explore/financial-sync/rebut18/pin
M=$1; C=$S/r18/m${M#e}b
python3 $R/mut.py $C $M a || exit 1
$R/run.sh $C $M-a
H=$(cat $R/hash-$M-a 2>/dev/null); echo "a hash=$H"
[ -n "$H" ] || { echo "a에서 해시를 못 읽었다 — 멈춘다"; exit 1; }
python3 $R/mut.py $C $M bump $H || exit 1
$R/run.sh $C $M-b
python3 $R/mut.py $C $M c || exit 1
$R/run.sh $C $M-c
echo "== $M 끝"
