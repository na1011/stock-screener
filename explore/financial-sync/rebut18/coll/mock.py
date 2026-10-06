#!/usr/bin/env python3
"""rebut18 수집기 0콜 재생 서버 — 저장된 같은 날 캐시(p1v14-cache/out)를 SEC 두 호스트처럼 낸다.
사용: mock.py <port> <캐시 루트> <고장 JSON 파일> <요청 기록 파일>
고장 JSON: {"<경로>": "trunc-once" | "trunc-always" | "503-always" | "503-once" | "404"} — 매 요청마다 다시 읽는다."""
import json, sys, os, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]); ROOT = sys.argv[2]; FAULTS = sys.argv[3]; LOG = sys.argv[4]
seen = {}
lock = threading.Lock()


def tickers_file():
    rows = [[320193, "Apple Inc.", "AAPL", "Nasdaq"], [1800, "ABBOTT LABORATORIES", "ABT", "NYSE"]]
    rows += [[9000000 + i, "DUMMY %d" % i, "ZZD%d" % i, "NYSE"] for i in range(6000)]
    return json.dumps({"fields": ["cik", "name", "ticker", "exchange"], "data": rows}).encode()


FACTS = {"/api/xbrl/companyfacts/CIK0000320193.json": "companyfacts/AAPL.json",
         "/api/xbrl/companyfacts/CIK0000001800.json": "companyfacts/ABT.json"}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        path = self.path
        with lock:
            n = seen.get(path, 0) + 1
            seen[path] = n
        try:
            faults = json.load(open(FAULTS))
        except FileNotFoundError:
            faults = {}
        mode = faults.get(path)
        if path == "/files/company_tickers_exchange.json":
            body = tickers_file()
        elif path in FACTS:
            body = open(os.path.join(ROOT, FACTS[path]), "rb").read()
        else:
            f = os.path.join(ROOT, "sec", path.lstrip("/"))
            body = open(f, "rb").read() if os.path.isfile(f) else None
        status = 200
        if body is None:
            status, body = 404, b"not found"
        elif mode == "503-always" or (mode == "503-once" and n == 1):
            status, body = 503, b"<html>busy</html>"
        elif mode == "404":
            status, body = 404, b"not found"
        elif mode == "trunc-always" or (mode == "trunc-once" and n == 1):
            body = body[: len(body) // 2]
        with open(LOG, "a") as lf:
            lf.write("%s\t%d\t%d\t%s\n" % (path, n, status, len(body)))
        self.send_response(status)
        self.send_header("Content-Type", "application/json" if path.endswith(".json") else "application/xml")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
