#!/bin/zsh
# 쌍 평가(원장 공시별 진실 표 TRUTH): $1 대조 · $2 변형 · $3 태그 — 오프셋 0·4 · 기간 둘(주 1회 표본)
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
for off in 0 4; do
  for per in "2016-01-04 2021-12-31" "2022-01-03 2025-06-30"; do
    from=${per% *}; to=${per#* }
    tag=$3-o$off-${from:0:4}
    TRUTH=${TRUTHF:-$S/p3/truth.tsv} POP=$S/members.txt OFF=$off FROM=$from TO=$to STEP=7 python3 $S/p3/q4eval.py ${D:-$S/p3}/$1.tsv ${D:-$S/p3}/$2.tsv ${D:-$S/p3}/tv-$tag.tsv > ${D:-$S/p3}/tv-$tag.txt 2>&1
  done
done
echo done > ${D:-$S/p3}/tdone-$3
