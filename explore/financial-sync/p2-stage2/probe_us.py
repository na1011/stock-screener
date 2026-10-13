import json, os, sys, time, urllib.request, gzip
UA = os.environ["SEC_EDGAR_USER_AGENT"]
hits = json.load(open(sys.argv[1]))
out = []
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=60) as r:
        b = r.read()
        if r.headers.get("Content-Encoding") == "gzip": b = gzip.decompress(b)
    time.sleep(0.15)
    return json.loads(b)
for day, form, cik, tkr, accn in hits:
    pad = "CIK%010d" % cik
    sub = get(f"https://data.sec.gov/submissions/{pad}.json")["filings"]["recent"]
    idx = sub["accessionNumber"].index(accn) if accn in sub["accessionNumber"] else -1
    xbrl = sub["isXBRL"][idx] if idx >= 0 else None
    ixbrl = sub["isInlineXBRL"][idx] if idx >= 0 else None
    rdate = sub["reportDate"][idx] if idx >= 0 else None
    cf = get(f"https://data.sec.gov/api/xbrl/companyfacts/{pad}.json")
    gaap = set()
    for concept in cf.get("facts", {}).get("us-gaap", {}).values():
        for unit in concept.get("units", {}).values():
            for f in unit:
                gaap.add(f.get("accn"))
    ifrs = set()
    for concept in cf.get("facts", {}).get("ifrs-full", {}).values():
        for unit in concept.get("units", {}).values():
            for f in unit:
                ifrs.add(f.get("accn"))
    out.append((day, form, tkr, accn, idx >= 0, xbrl, ixbrl, rdate, accn in gaap, accn in ifrs))
for r in out: print("\t".join(map(str, r)))
