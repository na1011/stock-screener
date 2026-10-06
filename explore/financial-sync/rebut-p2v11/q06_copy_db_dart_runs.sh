#!/bin/bash
# 결정 3 「리미터가 (KST 날짜, JVM)별 콜 수를 DB에 남기고 hasDailyQuota()가 다른 JVM들의 영속 수를 읽는다」의 모집단:
# DART 키는 하나인데 사본 DB에서 돈 bootRun(ops/apply-window/env.sh boot() — DB_URL을 사본으로)은 자기 사본에 남긴다.
# 사본에만 있는 DART 가능 실행(운영 batch_execution에 (id, job, started_at)이 없는 행)을 센다. 읽기만(SELECT).
set -euo pipefail
OUT=$(mktemp -d)
command psql -d stock_screener -At -F $'\t' -c "select id, job_name, started_at from batch_execution" > "$OUT/ops.tsv"
for db in stock_screener_q4v_r13a stock_screener_q4v_r13b stock_screener_q4v_rs1a stock_screener_q4v_rs1b stock_screener_q4v_rs1c stock_screener_q4v_rs1d stock_screener_q4v_rs1e stock_screener_r15b stock_screener_sc_a stock_screener_sc_c; do
  command psql -d $db -At -F $'\t' -c "select '$db', id, job_name, started_at, status, triggered_by, target_count, coalesce(left(note,90),'') from batch_execution where job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','initial-load','company-profile') and started_at > now() - interval '40 days'"
done > "$OUT/copies.tsv"
python3 - "$OUT" <<'PY'
import sys
d=sys.argv[1]; ops=set(tuple(l.rstrip('\n').split('\t')) for l in open(d+'/ops.tsv'))
seen={}
for l in open(d+'/copies.tsv'):
    p=l.rstrip('\n').split('\t')
    if (p[1],p[2],p[3]) in ops: continue
    seen.setdefault((p[1],p[2],p[3]),(p,[]))[1].append(p[0].replace('stock_screener_',''))
print('사본에만 있는 DART 가능 실행(서로 다른 행):', len(seen))
for k,(p,dbs) in sorted(seen.items(), key=lambda x:x[0][2]):
    print(p[3][:19], p[2], p[4], p[5], 'target', p[6], ','.join(sorted(set(dbs))), '|', p[7])
PY
