# 색인에만 있던 6-K 넷(rebut-p2v1 rf1)의 원장 상태 — isXBRL · companyfacts us-gaap 수록 · 0콜
import json, os, glob
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad'
C=S+'/p1rs/cache-1006'
want={'ESLT':['0001178913-25-003603'],'BNT':['0001837429-25-000026'],'JHX':['0001159152-25-000058'],'SOBO':['0001171843-25-007305','0002019061-25-000011','0002019061-25-000014']}
for t,accs in want.items():
    cf=json.load(open(f'{C}/companyfacts/{t}.json'))
    cik=cf['cik']
    us=set()
    for c,n in cf.get('facts',{}).get('us-gaap',{}).items():
        for u,arr in n.get('units',{}).items():
            for f in arr: us.add(f['accn'])
    ifrs=set()
    for c,n in cf.get('facts',{}).get('ifrs-full',{}).items():
        for u,arr in n.get('units',{}).items():
            for f in arr: ifrs.add(f['accn'])
    subs=[]
    for p in glob.glob(f'{C}/sec/submissions/CIK{int(cik):010d}*.json'):
        j=json.load(open(p)); tab=j['filings']['recent'] if 'filings' in j else j
        for i,a in enumerate(tab['accessionNumber']):
            if a in accs: subs.append((a, tab['form'][i], tab['filingDate'][i], tab['isXBRL'][i], tab['isInlineXBRL'][i], tab['primaryDocDescription'][i] if 'primaryDocDescription' in tab else ''))
    for a in accs:
        print(t, a, 'usgaap' if a in us else '-', 'ifrs' if a in ifrs else '-', [s for s in subs if s[0]==a])
