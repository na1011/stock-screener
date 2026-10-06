# 결정 4 「항목 단위 보류 = 워터마크 위의 예외 행」이 엣지(last_satisfied)를 날짜 순으로 잇는가.
# 항목 하나 · 날짜 D0..D(n-1)의 참/거짓 열 · Dh 하나가 그날 보류(일시 구멍 · ⑦ 비성공)이고 원인이 k일 뒤에 풀린다.
# 옳은 알림 = 날짜 순 엣지(직전 거짓 → 참)의 날짜 집합. v10 = 워터마크가 Dh를 넘겨 Dh+1.. 을 먼저 판정하고, 풀린 날 Dh를 판정.
# WatchlistAlertRunner: triggered = satisfied && !wasSatisfied · recordEvaluation이 last_satisfied를 덮음.
import itertools
def alerts(order, seq):
    last = None; out = []
    for d in order:
        s = seq[d]
        if s and not last: out.append(d)
        last = s
    return out
n = 6
res = {'same':0,'lost':0,'shifted':0,'extra':0}
examples = []
total = 0
for seq in itertools.product([False, True], repeat=n):
    for h in range(1, n-1):          # 보류일(첫날 아님 · 마지막 날 아님)
        for k in (1, 2):              # 원인이 풀리기까지 판정된 뒤 날짜 수
            if h + k >= n: continue
            correct = alerts(list(range(n)), seq)
            order = [d for d in range(n) if d != h]
            order.insert(order.index(h + k) + 1, h)   # h+k를 판정한 뒤 h를 판정(풀린 날 따라잡기)
            got = alerts(order, seq)
            total += 1
            if sorted(got) == correct: res['same'] += 1
            elif len(got) < len(correct): res['lost'] += 1; examples.append((seq, h, k, correct, got)) if len(examples) < 4 else None
            elif len(got) > len(correct): res['extra'] += 1
            else: res['shifted'] += 1
print(f"열거 {total}(열 2^{n} × 보류일 × 풀림 지연 1·2): 같음 {res['same']} · 알림 잃음 {res['lost']} · 알림 더 많음 {res['extra']} · 날짜 어긋남 {res['shifted']}")
for seq, h, k, c, g in examples:
    print("  예:", ''.join('T' if x else 'F' for x in seq), f"보류 D{h} · {k}일 뒤 풀림 · 옳은 알림 {['D%d'%d for d in c]} · v10 {['D%d'%d for d in g]}")
