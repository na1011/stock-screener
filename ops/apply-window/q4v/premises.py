import sys, re, subprocess, os
repo, db, out = sys.argv[1], sys.argv[2], sys.argv[3]
adrs = ['ADR-0126-share-scale-mistagging-watched-on-the-output-axis', 'ADR-0135-restore-the-past-universe-as-a-second-plane',
        'ADR-0151-fourth-quarter-must-match-vintages', 'ADR-0161-earlier-sec-vintages-are-stored']
env = dict(os.environ, PGOPTIONS='-c statement_timeout=120s')
with open(out, 'w') as w:
    for adr in adrs:
        text = open(f'{repo}/docs/adr/{adr}.md', encoding='utf-8').read()
        for m in re.finditer(r'^```premise ?(.*?)\n(.*?)^```', text, re.S | re.M):
            title, body = m.group(1), m.group(2)
            body = body.replace('psql -d stock_screener ', f'psql -d {db} ')
            r = subprocess.run(['bash', '-c', body], cwd=repo, env=env, capture_output=True, text=True, timeout=180)
            w.write(f'== {adr} :: {title}\n{r.stdout.strip()}\n' + (f'(rc={r.returncode}) {r.stderr.strip()[:300]}\n' if r.returncode else ''))
