#!/bin/bash
# 사용: 04-unresolved.sh   — 읽기만. 재적재 뒤 하드 단언(계획 v2 결정 4 · docs/reload-gate.md 순서 10의 「미해결 판정」 문장 그대로).
# 하나라도 서지 않으면 다음 단계로 안 간다. 판정은 사람이 $A/verdicts.tsv에 적는다 — 종류<TAB>열쇠<TAB>판정(원장에 무엇을 물었나 · 진짜/가짜 한계):
#   CLASS_UNREADABLE  <cik>:<접수번호>   클래스 축 인스턴스를 해석하지 못함 — 원장에 물어 진짜 한계인지
#   CLASS_FAILED      <cik>:<접수번호>   클래스 축 인스턴스를 받지 못함 — 다시 받아도 같은 답(폴더 404 · 빈 본문 · 같은 길이 어긋남)이면 원장의 답을 적는다
#   SUPP_FAILED       <cik>:<접수번호>   공시 보충 실패
#   SUPP_STALE        <cik>:<접수번호>   공시 보충이 옛 판을 썼다
#   RETRACT_HOLD      <티커>             실행 기록의 회수정지종목=
# 다시 받기: 편입이면 03과 같은 인자에 --tickers=…(--universe 없이) · 후보면 --population=candidates --tickers=… — 03-refill.sh의 재시도 갈래와 같다.
source "$(dirname "$0")/env.sh"
V="$A/verdicts.tsv"; [ -f "$V" ] || : > "$V"
have() { awk -F'\t' -v k="$1" -v c="$2" '$1==k && $2==c && NF>=3 && $3!="" {f=1} END {exit !f}' "$V"; }
FAIL=()
# ① 클래스 축 — 지금 판독 판의 FAILED 전원에 판정(②와 같은 규칙). 받기 실패는 먼저 다시 받고, 다시 받아도 같은 답인 것만 판정으로 닫는다 —
#   「받기 실패는 다시 받으면 0이 된다」는 전제는 원장이 폴더 404 · 빈 본문으로 답하는 공시에서 안 선다(2026-11-02 CRL · GRNT). 판독 판 번호는 코드에서 읽는다.
RV=$(sed -n 's/.*static final int READER_VERSION = \([0-9]*\);.*/\1/p' "$REPO/src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/ClassFactReader.java")
[ -n "$RV" ] || die "ClassFactReader.READER_VERSION을 못 읽었다"
n=$(q "$PROD" "select count(*) from sec_filing_class_fact where status='FAILED' and failure_kind <> 'UNREADABLE' and reader_version=$RV")
echo "① 클래스 축 미해결(FAILED · 해석 실패 아님 · 판 $RV) $n"
while IFS=$'\t' read -r cik acc; do have CLASS_FAILED "$cik:$acc" || FAIL+=("① 받기 실패 판정 없음 $cik:$acc"); done \
  < <(q "$PROD" "select cik, accession from sec_filing_class_fact where status='FAILED' and failure_kind <> 'UNREADABLE' and reader_version=$RV order by 1,2")
while IFS=$'\t' read -r cik acc; do have CLASS_UNREADABLE "$cik:$acc" || FAIL+=("① 해석 실패 판정 없음 $cik:$acc"); done \
  < <(q "$PROD" "select cik, accession from sec_filing_class_fact where status='FAILED' and failure_kind='UNREADABLE' and reader_version=$RV order by 1,2")
# ② 공시 보충 — FAILED와 옛 판을 쓴 행 전원에 판정. 표 전체를 본다(실행 기록의 미해결보충=N은 그 실행이 본 행뿐이다).
while IFS=$'\t' read -r kind cik acc; do have "$kind" "$cik:$acc" || FAIL+=("② $kind 판정 없음 $cik:$acc"); done \
  < <(q "$PROD" "select case when status='FAILED' then 'SUPP_FAILED' else 'SUPP_STALE' end, cik, accession from sec_filing_supplement where status='FAILED' or stale_used_at is not null order by 1,2,3")
for t in $(grep -o '회수정지종목=[^ ]*' "$A/notes-$PROD.txt" | cut -d= -f2 | tr ',' '\n' | sort -u); do
  have RETRACT_HOLD "$t" || FAIL+=("② 회수정지 판정 없음 $t")
done
# ③ 판 번호·최신 표지 정합 — 미국 0(관문 전 7행은 이른 판 켬 실행의 재번호가 닫는 꼴이다 · 00이 그 종목이 대상에 다 드는지 봤다).
PGOPTIONS='-c statement_timeout=120s' command psql -d "$PROD" -v ON_ERROR_STOP=1 -At -F $'\t' -f "$SCRIPTS/a2.sql" > "$A/a2-after.txt" || die "a2 실패"
us=$(awk -F'\t' '$1=="f" {print $2+$3+$4+$5}' "$A/a2-after.txt")
echo "③ a2 미국 $(awk -F'\t' '$1=="f"' "$A/a2-after.txt" | tr '\t' ' ') (전: $(awk -F'\t' '$1=="f"' "$A/a2-before.txt" | tr '\t' ' '))"
[ "${us:-x}" = 0 ] || FAIL+=("③ a2 미국 $us")
if [ ${#FAIL[@]} -eq 0 ]; then echo "OK 미해결 0 · 판정 $(grep -c . "$V" || true)줄"; else printf '  %s\n' "${FAIL[@]}"; die "미해결이 남았다 — 다시 받거나 ${V}에 판정을 적는다"; fi
