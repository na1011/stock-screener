#!/bin/bash
# 사용: 08-gate.sh <S1 db> <S0 db>  — 적용 판정 뒤, 두 사본에 스케줄러와 같은 refill(플래그 거짓 · 기본 8년)을 돌려 종목별 결과가 같은지 본다.
# Q4V가 넣은 행을 다음 정기 refill이 되돌리는지 가른다. 창 의존 같은 기존 결함은 두 사본에 똑같이 나타나 상쇄된다(LNT 2018·2019 Q4 — 캐시 스윕 2015 대 2018).
source "$(dirname "$0")/env.sh"
G1="$1"; G0="$2"; read N_ALL N_CUR N_PAST < $A/counts.txt
for db in "$G1" "$G0"; do
  [ "$(date +%H%M)" -lt 1850 ] || [ "$PROD" != "$LIVE" ] || die "게이트는 18:50 KST 전에만 — SEC 접수 창"
  P=$(max_exec_id $db)
  boot $db --job=financial-refill --universe=US_TOP1000 --insert-earlier-vintages=false > $A/gate-$db.log 2>&1 || true
  id=$(new_exec $db "$P" financial-refill "$N_CUR"); echo "gate $db $(q $db "select note from batch_execution where id=$id")"
  grep -E '결손 보정\. stockId=' $A/gate-$db.log | sed -E 's/.*(stockId=[0-9]+).*(신규=[0-9]+ 정정=[0-9]+ 채움=[0-9]+ 정정보류=[0-9]+ 교정=[0-9]+).*(이른판삽입=[0-9]+).*/\1 \2 \3/' | sort > $A/gate-lines-$db.txt
  grep -E '정정공시를 반영한다|유도 판을 회수한다' $A/gate-$db.log | sed -E 's/.*(정정공시를 반영한다|유도 판을 회수한다)\. //' | sort > $A/gate-events-$db.txt
done
if cmp -s $A/gate-lines-$G1.txt $A/gate-lines-$G0.txt && cmp -s $A/gate-events-$G1.txt $A/gate-events-$G0.txt; then
  echo "OK 두 사본의 두 번째 refill이 같다 — 사건: $(tr '\n' ';' < $A/gate-events-$G1.txt)"
else
  echo "[다름] Q4V 행이 다음 정기 refill에서 다르게 다뤄진다"; diff $A/gate-lines-$G1.txt $A/gate-lines-$G0.txt | head; diff $A/gate-events-$G1.txt $A/gate-events-$G0.txt | head; exit 1
fi
