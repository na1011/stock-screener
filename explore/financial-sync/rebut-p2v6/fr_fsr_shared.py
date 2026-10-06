# FR(fnlttSinglAcnt) 대 FSR(fnlttSinglAcntAll) 공유 칸 대략 대조 — 저장 응답만(0콜). 매핑은 어댑터의 근사(정밀 매퍼 아님).
import json, sys
FR = {'자산총계':'assets','부채총계':'liab','자본총계':'equity','유동자산':'cur_a','유동부채':'cur_l','이익잉여금':'re','매출액':'rev','영업이익':'oi','당기순이익(손실)':'ni','당기순이익':'ni'}
FS = {'ifrs-full_Assets':'assets','ifrs-full_Liabilities':'liab','ifrs-full_Equity':'equity','ifrs-full_CurrentAssets':'cur_a','ifrs-full_CurrentLiabilities':'cur_l','ifrs-full_RetainedEarnings':'re','ifrs-full_Revenue':'rev','dart_OperatingIncomeLoss':'oi','ifrs-full_ProfitLoss':'ni'}
def num(s):
    s=(s or '').replace(',','').strip()
    try: return int(s)
    except: return None
def fr_vals(path):
    d=json.load(open(path)); rows=d.get('list',[])
    cfs=[r for r in rows if r['fs_div']=='CFS']; use=cfs or [r for r in rows if r['fs_div']=='OFS']
    out={}
    for r in sorted(use,key=lambda r:int(r['ord'] or 0)):
        k=FR.get(r['account_nm'])
        if k and k not in out: out[k]=num(r['thstrm_amount'])
    return ('CFS' if cfs else 'OFS'), out
def fs_vals(path):
    d=json.load(open(path)); out={}
    if d.get('status')!='000': return None
    for r in d['list']:
        if r['sj_div'] not in ('BS','IS','CIS'): continue
        k=FS.get(r['account_id'])
        if k and k not in out: out[k]=num(r['thstrm_amount'])
    return out
pairs=[('dart1/q2.json','dart1/q3.json','dart1/q4.json','450080 2025Q3'),('dart1/q5.json','dart1/q6.json','dart1/q7.json','195940 FY2025'),
       ('dart2/q1.json','dart2/q2.json','dart2/q3.json','000480 2026H1'),('dart2/q4.json','dart2/q5.json','dart2/q6.json','013720 2026H1'),('dart2/q7.json','dart2/q8.json','dart2/q9.json','376180 2026H1')]
for fr,cfs,ofs,label in pairs:
    div,a=fr_vals(fr); b=fs_vals(cfs) if div=='CFS' else fs_vals(ofs)
    if b is None: b=fs_vals(ofs); div2='OFS'
    else: div2=div
    common=[k for k in a if k in b and a[k] is not None and b[k] is not None]
    diff=[(k,a[k],b[k]) for k in common if a[k]!=b[k]]
    print(label, 'FR',div,'FSR',div2,'공통',len(common),'불일치',len(diff), diff[:4])
