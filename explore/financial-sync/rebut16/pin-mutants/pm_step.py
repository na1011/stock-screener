import sys, re, pathlib
root = pathlib.Path(sys.argv[1]); step = sys.argv[2]
U = root / "src/main/java/com/stockscreener/pipeline/datasource/us"
T = root / "src/test/java/com/stockscreener/pipeline/datasource/us/ReaderVersionPinTest.java"
X = U / "XbrlInstanceReader.java"
if step == "a":
    c = (U/"SecFilingClient.java").read_text()
    old = '''        return base.endsWith("_cal") || base.endsWith("_def") || base.endsWith("_lab")
                || base.endsWith("_pre") || base.endsWith("_ref");
    }
'''
    assert c.count(old) == 1
    c = c.replace(old, '''        return SecTickerCikCache.linkbaseStem(base);
    }
''')
    (U/"SecFilingClient.java").write_text(c)
    k = (U/"SecTickerCikCache.java").read_text()
    idx = k.rstrip().rfind("}")
    k = k[:idx] + '''
    /** 링크베이스 줄기인가 — 공용 도우미(rebut16 변이). */
    static boolean linkbaseStem(String base) {
        return base.endsWith("_cal") || base.endsWith("_def") || base.endsWith("_lab")
                || base.endsWith("_pre") || base.endsWith("_ref");
    }
}
'''
    (U/"SecTickerCikCache.java").write_text(k)
elif step == "b":
    src_hash, out_hash = sys.argv[3], sys.argv[4]
    x = X.read_text()
    assert x.count("static final int READER_VERSION = 5;") == 1
    X.write_text(x.replace("static final int READER_VERSION = 5;", "static final int READER_VERSION = 6;"))
    t = T.read_text()
    t = t.replace('"0e1038c9921de8acebeefdc8cd55aaa8b6e197c021a63d9cd91267f3d518af40");',
                  '"0e1038c9921de8acebeefdc8cd55aaa8b6e197c021a63d9cd91267f3d518af40",\n            "%s");' % src_hash, 1)
    marker = '"d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1");'
    assert t.count(marker) == 1
    t = t.replace(marker, '"d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",\n            "%s");' % out_hash)
    T.write_text(t)
elif step == "c":
    k = (U/"SecTickerCikCache.java").read_text()
    old = '''    static boolean linkbaseStem(String base) {
        return base.endsWith("_cal")'''
    assert k.count(old) == 1
    k = k.replace(old, '''    static boolean linkbaseStem(String base) {
        base = base.toLowerCase(java.util.Locale.ROOT);
        return base.endsWith("_cal")''')
    (U/"SecTickerCikCache.java").write_text(k)
print("ok", step)
