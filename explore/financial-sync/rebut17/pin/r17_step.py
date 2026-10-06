# rebut17 판 고정 변이 — 인터페이스 디스패치(v11 반증 pm a·c의 디스패치 꼴).
# a: isLinkbase 본문을 면제 형 SecTickerCikCache의 Predicate 구현 클래스로 옮긴다(필드 형은 java.util.function.Predicate)
# b: 판 7 + 해시 덧붙임(인자 둘) · c: 그 구현 클래스의 test 본문만 바꾼다(소문자화 — rebut16 pm c와 같은 행동 변화)
import sys, pathlib
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
    c = c.replace(old, '''        return SecTickerCikCache.LINKBASE_STEM.test(base);
    }
''')
    (U/"SecFilingClient.java").write_text(c)
    k = (U/"SecTickerCikCache.java").read_text()
    idx = k.rstrip().rfind("}")
    k = k[:idx] + '''
    /** 링크베이스 줄기 규칙(rebut17 변이 — 디스패치). */
    static final java.util.function.Predicate<String> LINKBASE_STEM = new LinkbaseStem();

    static final class LinkbaseStem implements java.util.function.Predicate<String> {
        @Override
        public boolean test(String base) {
            return base.endsWith("_cal") || base.endsWith("_def") || base.endsWith("_lab")
                    || base.endsWith("_pre") || base.endsWith("_ref");
        }
    }
}
'''
    (U/"SecTickerCikCache.java").write_text(k)
elif step == "b":
    src_hash, out_hash = sys.argv[3], sys.argv[4]
    x = X.read_text()
    assert x.count("static final int READER_VERSION = 6;") == 1
    X.write_text(x.replace("static final int READER_VERSION = 6;", "static final int READER_VERSION = 7;"))
    t = T.read_text()
    old = '"e8bf8893ee9c89efc789f6258e79ba09aa2403adc897a424e5232be3e1294550");'
    assert t.count(old) == 1
    t = t.replace(old, '"e8bf8893ee9c89efc789f6258e79ba09aa2403adc897a424e5232be3e1294550",\n            "%s");' % src_hash)
    marker = '"d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1",\n            "d15f000efa8f1ed40cb9da32188a700e4055497c0c7e04a9ce65f870fe7f61c1");'
    assert t.count(marker) == 1
    t = t.replace(marker, marker[:-2] + ',\n            "%s");' % out_hash)
    T.write_text(t)
elif step == "c":
    k = (U/"SecTickerCikCache.java").read_text()
    old = '''        public boolean test(String base) {
            return base.endsWith("_cal")'''
    assert k.count(old) == 1
    k = k.replace(old, '''        public boolean test(String base) {
            base = base.toLowerCase(java.util.Locale.ROOT);
            return base.endsWith("_cal")''')
    (U/"SecTickerCikCache.java").write_text(k)
print("ok", step)
