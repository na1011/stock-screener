"""F2b 국내 실측 — 편입 350에 fnlttSinglAcntAll(CFS)을 2025 사업(11011) · 2026 반기(11012)로 묻고 원응답을 저장한다.

키는 환경변수 DART_API_KEY로만 받는다. 응답 파일에 키가 남지 않는다(요청 URL을 안 적는다).
"""
import io, json, os, sys, time, urllib.request, urllib.parse, zipfile, xml.etree.ElementTree as ET

out = sys.argv[1]
members = [l.split('\t')[0] for l in open(sys.argv[2], encoding='utf-8') if l.strip()]
key = os.environ['DART_API_KEY']
os.makedirs(out, exist_ok=True)

def get(path, params):
    q = urllib.parse.urlencode({'crtfc_key': key, **params})
    with urllib.request.urlopen('https://opendart.fss.or.kr/api' + path + '?' + q, timeout=60) as r:
        return r.read()

corp_file = os.path.join(out, 'corp.json')
if not os.path.exists(corp_file):
    z = zipfile.ZipFile(io.BytesIO(get('/corpCode.xml', {})))
    root = ET.fromstring(z.read(z.namelist()[0]))
    m = {}
    for e in root.iter('list'):
        sc = (e.findtext('stock_code') or '').strip()
        if sc:
            m[sc] = e.findtext('corp_code')
    json.dump(m, open(corp_file, 'w'))
corp = json.load(open(corp_file))

calls = 0
for t in members:
    cc = corp.get(t)
    if not cc:
        print('NO_CORP', t); continue
    for year, code in (('2025', '11011'), ('2026', '11012')):
        f = os.path.join(out, f'{t}_{year}_{code}.json')
        if os.path.exists(f):
            continue
        body = json.loads(get('/fnlttSinglAcntAll.json',
                              {'corp_code': cc, 'bsns_year': year, 'reprt_code': code, 'fs_div': 'CFS'}))
        calls += 1
        if body.get('status') == '013':
            body = json.loads(get('/fnlttSinglAcntAll.json',
                                  {'corp_code': cc, 'bsns_year': year, 'reprt_code': code, 'fs_div': 'OFS'}))
            body['_fs_div'] = 'OFS'
            calls += 1
        else:
            body['_fs_div'] = 'CFS'
        if body.get('status') not in ('000', '013'):
            print('STATUS', t, year, code, body.get('status'), body.get('message'))
            if body.get('status') in ('020', '010', '011', '012', '901'):
                sys.exit(2)
        json.dump(body, open(f, 'w'), ensure_ascii=False)
        time.sleep(0.15)
print('calls', calls)
