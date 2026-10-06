# v15 결정 3을 정수 ms로: A 혼자 600(100ms) · B가 t=10s에 수요 등록(몫 0) · A가 다음 갱신(주기 R)에 300으로 줄여 장부에 씀 · B는 그 기록 뒤에야 남은 300을 받는다.
# 변형 X: A가 축소 직전 이미 옛 간격으로 잡아 둔 다음 콜(last+100)을 그대로 쏜다 · 변형 Y: 축소가 다음 콜부터(last+200).
# 반열린 창 [t, t+w) 최대 콜 수(정수 ms라 부동소수 누적 없음).
import bisect
def sim(R, mode, lag_ms=0, T=180000):
    calls=[]; a_iv=100; t=0; renew=R; b_t=None
    while t<T:
        calls.append(t)
        nxt=t+a_iv
        while renew<=nxt and renew<=T:
            if renew>=10000 and a_iv==100:
                if mode=='Y': nxt=t+200
                a_iv=200; b_t=renew+lag_ms
            renew+=R
        t=nxt
    if b_t is not None:
        while b_t<T: calls.append(b_t); b_t+=200
    calls.sort()
    def mw(w): return max(bisect.bisect_left(calls,c+w)-i for i,c in enumerate(calls))
    return mw(1000),mw(42000),mw(60000)
for R in (5000,15000,30000):
    for mode in ('X','Y'):
        print('갱신 %2ds 변형 %s  1s/42s/60s ='%(R//1000,mode), *sim(R,mode))
