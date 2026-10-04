#!/bin/zsh
# 쌍 평가: $1 대조 TSV 이름 · $2 변형 TSV 이름 · $3 태그 — 어댑터 행 진실(4컬럼+PQ4)과 원장 지배주주 진실, 오프셋 0·4, 기간 둘
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
cd /Users/na1011/dev/stock-screener
for off in 0 4; do
  for per in "2016-01-04 2021-12-31 7" "2022-01-03 2025-06-30 7"; do
    set -- $1 $2 $3 ${=per}
    from=$4; to=$5; step=$6
    tag=$3-o$off-${from:0:4}
    POP=$S/members.txt OFF=$off FROM=$from TO=$to STEP=$step python3 $S/p3/q4eval.py $S/p3/$1.tsv $S/p3/$2.tsv $S/p3/ev-$tag.tsv > $S/p3/ev-$tag.txt 2>&1
    TTM_TRUTH=settled STEP=$step FROM=$from TO=$to TTM_POP=$S/cur.txt TTM_OFFSET=$off python3 $S/p3/ttm_settled.py $S $S/p3/$1.tsv $S/p3/$2.tsv > $S/p3/nic-$tag.txt 2>&1
  done
done
echo done > $S/p3/done-$3
