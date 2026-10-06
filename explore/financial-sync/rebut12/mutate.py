# 1부 v7 반증(계획 안) — 변이 하나를 사본(scratchpad/r12copy = git archive f9a1d4c7)에 넣는다. 사용: mutate.py <tag>|restore
import sys, pathlib, shutil
R = pathlib.Path(__file__).parent
B = pathlib.Path('/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/r12copy/src/main/java/com/stockscreener/pipeline/datasource/us')
FILES = ('XbrlInstanceReader.java', 'SecFilingSupplement.java', 'SecFilingClient.java', 'SecFilingSupplementStore.java')
for f in FILES:
    shutil.copy(R / 'orig' / f, B / f)
X = 'XbrlInstanceReader.java'; S = 'SecFilingSupplement.java'; C = 'SecFilingClient.java'; T = 'SecFilingSupplementStore.java'
M = {
    # 판 범위 밖 — 소스 해시 밖에서 종료 상태(NO_XBRL · UNREADABLE · 저장 팩트)를 바꾸는 자리
    'S1-folder-drop-xsd': [(C, '        for (JsonNode item : items) {\n            names.add(item.path("name").asString(""));\n        }',
                            '        for (JsonNode item : items) {\n            String n = item.path("name").asString("");\n            if (n.endsWith(".xsd")) {\n                continue;\n            }\n            names.add(n);\n        }')],
    'S2-fetch-filter-xsd': [(S, '        String name = pickInstance(names, context);',
                             '        String name = pickInstance(names.stream().filter(n -> !n.endsWith(".xsd")).toList(), context);')],
    'S3-codec-drop-start': [(T, '                    f.start() == null ? null : f.start().toString(), f.end().toString(),',
                             '                    null, f.end().toString(),')],
    'S7-instance-empty-ok': [(C, '        if (body == null || body.length == 0) {', '        if (body == null) {')],
    # 판 범위 안 · 판 무관 행동(합치기) — 소스 해시만 붉고 출력 해시는 그대로인가
    'S5-merge-drop-fp': [(X, '            node.put("fp", Objects.requireNonNullElse(fiscalPeriodFocus, ""));\n', '')],
    # 판 범위 안 · v6 생존 변이(읽기) — 같은 모양인가
    'S6-fy-no-trim': [(X, 'case "DocumentFiscalYearFocus" -> fiscalYear = text.trim();', 'case "DocumentFiscalYearFocus" -> fiscalYear = text;')],
    # 주석만 — 초록이어야
    'C0-comments-only': [(X, '        int ownContexts = (int) contexts.values()', '        // rebut12 "/*" \'x\' 주석만\n        /* 블록 "주석" */\n        int ownContexts = (int) contexts.values()'),
                         (S, '        List<String> candidates = names.stream()', '        // rebut12 주석\n        List<String> candidates = names.stream()')],
    # 판 범위 안 · 문자열 안의 공백만 — collapse가 리터럴 안 공백을 접는가(메시지라 행동 불변)
    'C1-literal-double-space': [(X, 'throw malformed("인스턴스를 읽지 못했다. " + context, e);', 'throw malformed("인스턴스를  읽지 못했다. " + context, e);')],
}
tag = sys.argv[1]
if tag == 'restore':
    print('restored'); sys.exit()
for f, old, new in M[tag]:
    p = B / f; s = p.read_text(); n = s.count(old); assert n == 1, (tag, f, n)
    p.write_text(s.replace(old, new))
print('applied', tag)
