rebut17 — 1부 v12(hold/p1-filing-supplement a8d137cf · 코드 df8e3005) 확인 반증 · 계획 안 렌즈 · 2026-10-06 밤
외부 API 0콜 · 운영 DB(stock_screener)·사본(q4v_rs1c/rs1d/rs1e · sc_a · dumpdrill_* · opsdrill_b)은 SELECT만 · 앱 로그는 읽기만
진짜 홈·앱 PID·.env 안 건드림 · gradle은 --no-daemon(단발 데몬은 빌드 끝에 끝남) · 내가 띄운 배경 프로세스 없음(끝남)
DB: 쓴 것은 stock_screener_r17a_test 하나(createdb -T stock_screener_test · 시험이 붙기만 함). stock_screener_r17b_test는 내 것이 아니다(손 안 댐).
코드 사본: scratchpad/r17/v12(git archive a8d137cf + R17ProbeTest) · r17/v11(56a97d3d) · r17/pin(a8d137cf + 디스패치 변이 a·b·c 적용 상태)

gate/   gate4-v12-rewind.sql/.out — ADR-0173 0단계 4의 v12 글자(target_date = 토−1 · 트리거 무관 · SUCCESS/PARTIAL ·
        order by id desc limit 1)를 지난 토요일 6번 × 토13:00·토19:59·토23:00·일12:00로 되감음(그 시각까지 끝난 행 · 명부는 created_at)
        → 09-05 토13:00·19:59에 #262(MANUAL · 금 09-04 09:48 · target 09-04)를 골라 SQL·명부(US max eff = 09-04) 둘 다 통과 ·
          그날 20:00 크론 #278(SCHEDULER PARTIAL · 같은 target)이 그 뒤에 돌았다. 나머지 다섯 주는 20:00 전 0행(멈춤) · 뒤 통과
        scheduler-start-times.txt — SCHEDULER 리밸런싱 6행 중 5행이 19:59:59.8~.96에 시작(「20:00 이후」로 박으면 놓친다)
sig/    서명 v2(post_signature.sql · f02d2738) — 109 대상: rs1d 두 번 같은 출력 · sc_a·rs1e·rs1c·운영의 fs/fca 부분(nosup.sql) 218줄
        서로 0차이(같은 행 → 같은 값) · rs1d는 fs 109줄 전부 다름(재생 결과) · sc_a는 보충 표가 없어 전체 서명이 오류(psql rc 0 · 출력 0줄)
        dumpdrill_*·opsdrill_b는 재무 0행인 사본이라 비교 불가(빈 파일)
probe/  R17ProbeTest.java · probe-v12.txt — (1) 공시 목록 호스트는 살고 Archives만 503: 종목 다섯 × 대상 2·4 → 종목마다 대기 24·36초
        (차단이 종목마다 닫힘 · 합 120·180초) · 대조군(목록 없이 Archives만 연달아) → 앞 셋만 대기(합 36초)
        · 열린 채 끝난 뒤 다음 실행 첫 실패는 대기 0 · 열려도 콜마다 3시도(files 요청 30 = folder 10 × 3)
        (2) byteeq-v12.tsv — rebut16 61파일 탐침 그대로: v11과 바이트 동일(61/61 OK · 콜 1)
sweep/  run.sh · A-v11/v12.tsv · extra-A-* — 같은 날 캐시(p1rs/cache-1006 · SupplementReplay · 0콜)로 보충 켬 스윕을 v11·v12에서:
        1,207종목 · 보고서 62,031 · extra 22,849줄 · 두 판 sha256 동일(보충 성공 경로 바이트 동일)
pin/    r17_step.py · run.sh · *.log · *-behavior.txt — 디스패치 변이:
        a) isLinkbase 본문을 면제 형 SecTickerCikCache의 Predicate 구현 클래스(LinkbaseStem)로 옮김 → 소스 해시만 붉음
        b) 판 7 + 해시 덧붙임 → 9 ok/1 skip(덧붙이기 검사는 git 없는 사본이라 건너뜀)
        c) LinkbaseStem.test 본문만 바꿈(소문자화) → 9 ok/1 skip 초록인데 pickInstance([abc-20180331.xml, abc-20180331_LAB.xml])가
           UnreadableFiling → abc-20180331.xml(OK)로 바뀜 — 종료 상태가 판 7 그대로 바뀐다

판정: 수정 후 진행 — (1) 0단계 4에 시각 울타리(토 19:55 KST 이후 시작 · gate/) (2) 받기 차단 상태를 호스트별로(probe/)
(3) 판 고정 폐포가 디스패치를 못 봄(잠복 · pin/) (4) 꼬리 줄이 적재를 건너뛴 날에도 찍힘(코드 · MembershipGapFiller) · 나머지는 보고 본문
