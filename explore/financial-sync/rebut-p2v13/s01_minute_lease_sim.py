# 결정 1 「분 버킷 임대」를 TokenBucketRateLimiter(균등 간격 · 로컬 600/분)와 맞물려 모의한다.
# 각 JVM: 분 m 시작에 자기 몫 L_i를 임대 → 로컬 간격 100ms(600/분)로 L_i개를 쏜다 → 다 쓰면 다음 분까지 기다린다.
# 대조: (R) 「몫 = 속도」 임대 — 각 JVM 간격 = 60000/L_i ms.
# 재는 것: 임의 슬라이딩 창(42s · 60s)에 든 콜 수의 최댓값, 순간 합산 속도.
import itertools
def run(leases, mode, minutes=5, phase_ms=(0,0)):
    calls=[]
    for j,L in enumerate(leases):
        for m in range(minutes):
            start=m*60000+phase_ms[j]
            iv = 100 if mode=='count' else 60000//L
            for k in range(L):
                t=start+k*iv
                if t < (m+1)*60000+phase_ms[j]: calls.append(t)
    calls.sort()
    def maxwin(w):
        best=0; i=0
        for jdx,t in enumerate(calls):
            while calls[i] < t-w+1: i+=1
            best=max(best,jdx-i+1)
        return best
    return maxwin(60000), maxwin(42000), maxwin(1000)
for name,leases in [("1JVM 600 (main 지금)",[600]),("2JVM 600+400 합 1000",[600,400]),("2JVM 500+500 합 1000",[500,500]),("2JVM 300+300 합 600(여유 유지)",[300,300])]:
    for mode in ['count','rate']:
        if len(leases)==1 and mode=='rate': continue
        w60,w42,w1=run(leases,mode)
        print(f"{name:32s} {mode:5s}  max/60s={w60:5d}  max/42s={w42:5d}  max/1s={w1:3d}  (순간 {w1*60} rpm)")
# 위상이 어긋난 경우(분 경계 직후에 B가 임대를 시작 — 장부의 분은 같아도 JVM 기동 시점이 다름)
print("--- count 모드에서 창이 분 경계에 걸칠 때(이미 위에 포함: 창은 연속 슬라이딩) ---")
