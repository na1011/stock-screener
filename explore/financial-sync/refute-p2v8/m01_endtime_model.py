# v8 결정 1·8의 「끝 시각」 — 13:30 메서드 뒤에 오는 사슬·유도·화해까지 더하고, 단계 짝 수를 결정 3의 DART 예산에 묶는다.
# 수는 운영 batch_execution(q01·q02) · PipelineScheduler 15:30 javadoc · PLAN v5 결정 9 · v6 반증 6에서 옮김(재는 법은 각 줄).
DAILY = 23 + 51/60        # daily-update SCHEDULER 최근 최대(10-05 #714 · q02) — 08-31 이후 13:35 → 23:51로 자람
CA = 15 + 56/60           # corporate-action 10-06 #742(대상 1,683) · q02
INCHAIN = 7 + 57/60       # 사슬 안 화해 ② 최악(javadoc 15:30 · 2026-10-04 이력)
DERIVE = 4/60             # 14:18 유도 3.8초 + 기저 0.1초(javadoc)
RECON = 4 + 8/60 + 7 + 57/60   # 14:20 ① 4:08 + ② 7:57 최악(javadoc) — 최근 SCHEDULER는 2:35 + 3:45(q01)
HEAVY = {'G': 54 + 58/60, 'FSR': 42 + 31/60, 'FR': 23.3 + 16 + 8, None: 0}   # G #632 · FSR PLAN v5 결정 9 · FR 국내 350×4초 + 미국 #683 16:00 + 1부 보충 약 +8분
HEAVY_DART = {'G': 8050, 'FSR': 15844, 'FR': 12600, None: 0}                 # PipelineScheduler javadoc · v2 결정 7
CHAIN_DART = 700          # irdsSttus 국내 몫(javadoc 수 · 실측 아님 — v7 반증)
PAIR_CALLS, PAIR_SEC = 81, 11.7   # v4 실측 짝 81콜 · 8.7~11.7초(최악 쪽)
US_STAGE = 3              # 미국 단계(하루 최대 113 공시 종목 × 약 1.5초 · v1 실측 1 · 재지 않음 — 위쪽 어림)
def hhmm(m): m = 13*60+30+m; return f'{int(m//60):02d}:{int(m%60):02d}'
print('날 종류 | 단계 짝(예산 상한) | 13:30 메서드 끝 | 사슬 DART 끝 | 화해 끝(=15:30 점검 시작)')
for h in ['G', 'FSR', 'FR', None]:
    pairs = min(287, (18000 - HEAVY_DART[h] - CHAIN_DART) // PAIR_CALLS)
    stage = pairs * PAIR_SEC / 60 + US_STAGE
    m_end = stage + DAILY + HEAVY[h]
    ca_end = m_end + CA
    rec_end = ca_end + INCHAIN + DERIVE + RECON
    print(f'{h or "없음":>4} | {pairs:3d}짝 {stage:5.1f}분 | {hhmm(m_end)} | {hhmm(ca_end)} | {hhmm(rec_end)}')
main_end = DAILY  # main: 13:30 갱신 → 14:00 사슬 → 14:15 G → 유도·화해
m = max(DAILY, 30) + CA + INCHAIN; m = max(m, 45) + HEAVY['G'] + DERIVE + RECON
print(f'대조 main G 날 최악: 화해 끝 {hhmm(m)}(progress ⓪ 「약 15:31」과 같은 꼴)')
print(f'v8 문장 「13:30 + 단계 42 + 갱신 22 + 무거운 잡 55 ≈ 15:30」: 42분 단계(213짝 = 17,253콜)와 G(8,050콜)는 결정 3의 18,000 안에 함께 못 든다 · 그리고 그 뒤 사슬·유도·화해(약 {CA+INCHAIN+DERIVE+RECON:.0f}분)가 빠졌다 → 그 수로 더하면 {hhmm(42+22+55+CA+INCHAIN+DERIVE+RECON)}')
