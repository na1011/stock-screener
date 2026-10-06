# 결정 1 속도 임대의 「합류·이탈」 전이 모의. 정상 상태(s01 v13: 합 600이면 순간 600rpm)가 아니라 몫이 바뀌는 순간을 잰다.
# A는 혼자 600(간격 100ms)을 쥐고 TTL마다 갱신(갱신 주기 R). B가 t=10s에 합류.
# 정책 P1 「합류 즉시 공정 몫(300)을 쥔다 · 기존 JVM은 다음 갱신 때 줄인다」
# 정책 P2 「합류는 남은 몫만 쥔다(0) · 기존 JVM이 갱신 때 공정 몫으로 줄이면 그때 B가 받는다」
# 정책 P3 = P1 + A가 장부를 못 읽는 동안(갱신 실패) 옛 간격으로 계속 쏜다(정지 규칙 없음) — 30초 장애
import bisect
def sim(policy, R=15.0, T=120.0, outage=None):
    calls=[]; 
    a_iv=0.1; b_iv=None; a_next_renew=R; b_join=10.0; b_start=None
    t_a=0.0; t_b=None
    shares={'A':600}
    while t_a<T:
        # renewals
        while a_next_renew<=t_a:
            if outage and outage[0]<=a_next_renew<outage[1]:
                pass  # renew failed; keep old interval (P3)
            else:
                live=['A']+(['B'] if a_next_renew>=b_join else [])
                fair=600/len(live); shares['A']=fair; a_iv=60.0/fair
                if 'B' in live and policy=='P2' and b_iv is None:
                    b_iv=60.0/fair; t_b=a_next_renew
            a_next_renew+=R
        calls.append(t_a); t_a+=a_iv
    if policy in('P1','P3'):
        b_iv=60.0/300; t_b=b_join
    if t_b is not None:
        while t_b<T: calls.append(t_b); t_b+=b_iv
    calls.sort()
    def maxwin(w):
        m=0
        for i,c in enumerate(calls):
            j=bisect.bisect_left(calls,c+w)-1; m=max(m,j-i+1)
        return m
    return {w:maxwin(w) for w in (1,42,60)}
for p,kw in (('P1',{}),('P2',{}),('P3',{'outage':(14,60)})):
    for R in (5,15,30):
        r=sim(p,R=R,**kw); print(p,'renew',R,'s  max calls per window 1s/42s/60s =',r[1],r[42],r[60],' (상한 600/분 → 60s 창 ≤ 600 · 42s ≤ 420)')
