# 관문 전 정기 무거운 잡을 관문까지 멈추는 스위치 (계획 v1 · 2026-10-04 · 반증 전)

**사용자 결정(원문)**: 「무거운 잡만 관문까지 멈춤 (Recommended)」 — 선택지 본문: 무거운 잡만 끄는 설정 스위치(멈춘 동안 신선도 경보 제외 포함)를 넣고 계획·반증 뒤 10-29 전에 배포(배포 직전에 다시 확인) ·
시세·일일 지표·리밸런싱은 계속 · Q3 재무는 관문까지 안 들어옴 · 스위치는 관문 날 해제. (앞선 질문 원문: 「화면의 지표가 낡아도 된다고 가정하면, 가장 작업하기 편하고 정합성이 좋은 방법은 2번인가?」)

## 실측
- 끌 수 있는 스위치는 `pipeline.scheduler.enabled` 하나(`@ConditionalOnProperty` — 스케줄러 빈 전체)라 무거운 잡만 끌 길이 없다.
- 무거운 잡: `PipelineScheduler.runHeavyJobOfTheDay`(평일 14:15)가 `heavyJobDueToday`로 고르는 셋 — 화 `governance-load`(+ 이어 `governance-derive`) · 수 `full-statement-refill` · 목 `financial-refill`(+ 자동 백필 · 둘 다 뒤 `share-basis-derive`).
  마지막 성공 뒤 25일 초과면 배정 요일 아니어도 연체 보정이 집는다 → 세 잡 시계가 10-03에 함께 리셋돼 첫 실행은 10-30~11-05(반증 측정).
- 신선도 점검(평일 15:30 `JobFreshnessAlerter`)의 예산이 넷 다 40일(`JobFreshnessPolicy.BUDGETS`) → 멈춘 채면 11월 중순부터 매일 연체 경보.
- 토요일 리밸런싱 꼬리(`UniverseRebalanceJob` 안)는 새 편입 종목의 재무(한 행도 없을 때 · APPEND_ONLY)와 FS·G GAP_FILL을 돈다 — 이것은 멈추지 않는다(새 편입이 비지 않게).

## 수정
1. `application.yaml`의 `pipeline.scheduler` 아래 `heavy-jobs-paused: true` — **환경변수 이름을 두지 않는다**(`CLAUDE.md` 「재적재·백필」: 켜는 길은 코드 기본값 하나 — `.env`는 배포마다 복사된다). 관문 날 이 줄을 `false`로 뒤집은 jar를 배포한다.
2. `PipelineScheduler.runHeavyJobOfTheDay`: 멈춤이면 고르지도 돌리지도 않고 INFO 한 줄(「관문까지 멈췄다 — 설정 pipeline.scheduler.heavy-jobs-paused」)을 남긴다.
3. 신선도 점검: 멈춤이면 넷(`financial-refill` · `full-statement-refill` · `governance-load` · `governance-derive`)을 연체 판정에서 뺀다 — 빼는 대신 경보 본문 맨 끝에 「무거운 잡 넷은 관문까지 멈춤(설정)」 한 줄을 다른 연체가 있을 때 함께 싣는다(멈춘 사실이 잊히지 않게 · 그것만으로는 경보를 안 보낸다).
4. 시험: 멈춤이면 무거운 잡이 안 불린다(배정 요일 · 연체 보정 둘 다) · 꺼짐이면 지금과 같다 · 신선도 판정이 멈춤에서 넷을 빼고 꺼짐에서는 지금과 같다 · yaml 기본값이 `true`인지 컨텍스트로 읽는 시험(배선).
5. 문서: `docs/reload-gate.md` 순서 10에 「스위치를 `false`로 뒤집은 jar」 · `docs/progress.md` 「재적재 관문」의 스위치 줄 · `PipelineScheduler` javadoc.

## 배포(10-29 전 · 사용자에게 직전 확인)
- 운영 jar(10-01 빌드)와 다른 것: 서버 클래스 S0 세 파일(설정 꺼짐 = 출력 동일) + 이 수정 · **프론트 번들 44개 + `index.html`이 다르다(원인 미측정 — 배포 전에 잰다)**.
- 저장소 `.env`에 `US_GATE`가 있으면 배포가 운영 S0를 켠다 — 사용자에게 `grep -c '^US_GATE' .env` 확인을 받는다(나는 `.env`를 안 읽는다).
- 배포 뒤 확인: 앱 기동 로그의 멈춤 줄 · 다음 평일 14:15의 INFO 줄 · `batch_execution`에 SCHEDULER 무거운 잡 행이 안 생김.

## 재지 않은 것
- 프론트 번들 차이의 원인.
- 관문 날짜(그만큼 Q3 재무가 낡는다).
