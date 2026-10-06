# RF10 다시: v11 결정 5 항목별 워터마크 — 항목 하나 · 날짜 D0..D(n-1) · 보류일 Dh에서 멈추고 k일 뒤 풀리면 (Dh..) 날짜 순으로 판정.
# 옳은 알림 = 날짜 순 엣지(직전 거짓 → 참). v11 = 보류일에서 멈춤 → 풀린 뒤 같은 순서로 판정 → 알림 날짜 집합이 같은가.
# 더해: 「판정 대상 아님」(편출일 X) 갈래 — 건너뛰고 워터마크는 간다(v11) 대 main(값 없음 → 불충족 기록).
import itertools
def alerts(order, seq):
    last = None; out = []
    for d in order:
        s = seq[d]
        if s is None: continue          # v11: 판정 대상 아님 — 기록 없음
        if s and not last: out.append(d)
        last = s
    return out
def alerts_main(seq):
    last = None; out = []
    for d, s in enumerate(seq):
        s2 = bool(s) if s is not None else False   # main: 편출일은 값 없음 → 불충족 기록
        if s2 and not last: out.append(d)
        last = s2
    return out
n = 6; total = 0; diff = 0
for seq in itertools.product([False, True], repeat=n):
    for h in range(1, n-1):
        for k in (1, 2):
            if h + k >= n: continue
            correct = alerts(list(range(n)), seq)
            got = alerts(list(range(n)), seq)   # 워터마크가 h에서 멈췄다 풀리면 판정 순서는 0..n-1 그대로(알림 시각만 늦다)
            total += 1
            if got != correct: diff += 1
print(f"항목 워터마크: 열거 {total} · 알림 날짜 집합 어긋남 {diff}")
# 편출 갈래: 열에 편출일 X(None)를 하나 이상 끼운 3값 열(참·거짓·X) — 다시 편입 뒤 참이면 main은 다시 알리고 v11은 안 알리는 열
tot = 0; v11_fewer = 0; v11_more = 0; ex = []
for seq in itertools.product([False, True, None], repeat=n):
    if None not in seq: continue
    if seq[0] is None or seq[-1] is None: continue   # 편출 구간이 가운데에 있는 열(나갔다 돌아옴)만
    tot += 1
    a = alerts(list(range(n)), seq); b = alerts_main(seq)
    if len(a) < len(b):
        v11_fewer += 1
        if len(ex) < 3: ex.append((''.join('T' if x else ('X' if x is None else 'F') for x in seq), a, b))
    elif len(a) > len(b): v11_more += 1
print(f"편출 뒤 재편입 열 {tot}: v11 알림이 main보다 적음 {v11_fewer} · 많음 {v11_more}")
for s, a, b in ex: print(f"  예: {s} · v11 {['D%d'%d for d in a]} · main {['D%d'%d for d in b]}")
