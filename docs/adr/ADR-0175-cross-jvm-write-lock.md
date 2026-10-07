---
id: ADR-0175
status: 제안됨
date: 2026-10-07
applies_to:
  - "src/main/resources/db/migration/V66__write_lock_guard.sql"
  - "src/main/java/**/pipeline/lock/**"
  - "src/main/java/**/pipeline/scheduler/PipelineScheduler.java"
  - "src/main/java/**/batch/AdminJobLauncher.java"
  - "src/main/java/**/batch/BatchExecutionSweeper.java"
  - "src/main/java/**/batch/BatchExecutionRecorder.java"
  - "src/main/java/**/batch/BootstrapRunner.java"
  - "src/main/java/**/collector/web/AdminCorporateActionController.java"
  - "ops/write-session.sh"
  - "ops/start.sh"
  - "ops/stop.sh"
  - "ops/deploy.sh"
  - "ops/dump-db.sh"
  - "ops/crons-done.sh"
  - "ops/status.sh"
  - "ops/apply-window/env.sh"
  - "src/main/java/**/screening/preset/PresetSynchronizer.java"
  - "src/main/java/**/batch/web/AdminBatchController.java"
---

# ADR-0175. 운영 DB 쓰기는 JVM을 건너 한 번에 한 쪽만 — 잠금 행과 DB 트리거

## 결정

같은 DB의 파이프라인 표에 쓰는 주체(앱 JVM의 쓰기 크론·관리자 launch·원장 판정 `PUT` · bootRun JVM · 손 세션의 psql·
pg_restore·bootRun 연쇄)는 `write_lock` 한 행(DB당)을 쥔 쪽만 쓴다. **강제는 DB가 한다** — 보호 표마다 문장 단위 트리거가 연결의
`application_name`(쓰기 신원)이 보유자인지 보고 아니면 `SSWG1`로 거절한다. 스위치는 `guard_setting`의 이 DB 이름 행이다.

**쓰기 크론은 잠금이 풀릴 때까지 상한 없이 기다린다**(평일 사슬 · 토요일 리밸런싱 모두 — 날짜 상한과 건너뜀이 없다). 대기 동안
첫 대기와 그 뒤 매시간 보유자·기다린 시간을 알리고, 그만두는 것은 종료 신호뿐이다.

- 사용자 결정(원문, 2026-10-07): 「끝날 때까지 기다림」 · 이어 「굳이 특정 날짜를 기다려야 해?」 · 「가장 정합성을 위한 판단이
  무엇인지 판단해줘」(판단을 위임했다).
- 위임받은 판단(사용자 문장이 아니다): 건너뜀은 그 몫을 다른 실행이 메운다는 가정에 기대는데 주 1회 리밸런싱에는 그 가정이 없다 ·
  멈춘 보유자는 세션 TTL·사망 술어가 풀므로 상한이 필요한 경우는 살아서 오래 쓰는 손 작업뿐이고 그것이 끝난 순간이 맞는 순서다 ·
  대상일은 잠금을 쥔 뒤 계산한다(`PipelineScheduler`의 크론 몸통). 대가: 스케줄러 풀이 1이라 대기 동안 이 JVM의 다른 크론이 줄을
  서 끝난 뒤 늦게 돈다(수면과 같은 동작) · 하루를 넘기면 같은 크론의 발화가 하나로 합쳐진다(재보지 않음).

## 규칙

- MUST: 보호 = public의 **보호 제외 열하나 밖 모든 표**(`write_guard_exempt`). 새 표는 이벤트 트리거가 만들어지는 순간 단다(임시 표 ·
  다른 스키마 제외). 제외 표와 각 사유는 V66 ③에 있다 — 늘리려면 「웹 사용자 요청이 잠금 없이 쓰는 표」인지 성질로 다시 센다.
  제외 표에도 배치가 쓰는 자리가 둘 있다: 프리셋 시더(같으면 문장 0 · 다르면 `CatalogWriteGate`)와 `watchlist_item`의 알림 판정
  (`WatchlistStore.recordEvaluation` — `DailyUpdateJob`에서만 불려 크론·관리자 launch의 토큰 안이다). 그 둘은 DB가 아니라 호출
  경로가 지킨다.
- MUST: 쓰기 신원은 `ss:j:<pid>:<시작초>`(JVM) · `ss:s:<id>`(세션의 psql·pg_restore — libpq `PGAPPNAME`) ·
  `ss:s:<id>:j:<pid>:<시작초>`(세션에 합류한 bootRun — 환경변수 `SS_WRITE_SESSION`)다. 세션 id는 `[a-z0-9-]{1,24}`.
- MUST: 사망 술어는 **pid 부재 ∨ 프로세스 시작 초 불일치**뿐이다. MUST NOT: 부팅 시각·박동 나이를 사망 근거로 쓰지 않는다.
- MUST: 획득은 관측한 행에 대한 CAS(`version`)이고 술어는 「비었음 ∨ 보유 JVM 죽음 ∨ 세션 만료 ∨ (보유 신원 = 나 ∧ 내 레지스트리에
  산 토큰 0)」이다. 산 대기 표지가 남의 것이면 쥐지 않는다.
- MUST: 쓰기 크론 여섯(13:30 · 14:00 · 14:15 · 14:18 · 14:20 · 토 20:00)은 바깥 `try` 맨 앞에서 쥐고 풀릴 때까지 기다린다(상한
  없음). 못 도는 발화(종료 신호 · 잠금 표 실패)는 실행 행이 없고 시작 실패 행도 안 쓴다 — 끝 줄은 `finally`가 찍는다. 대기에서
  어떤 예외로 빠져도 대기 표지를 걷는다. 15:30 신선도 점검은 읽기라 안 쥔다.
- MUST: 반납은 컨텍스트가 닫힌 뒤 종료 처리기(`SpringApplication.getShutdownHandlers()`)가 자기 연결로 한다 — 종료 마감이 그 전에
  보호 표에 쓴다.
- MUST: 거절은 bootRun·웹 기동 모두 `System.exit(1)`이다(던지면 devtools로 gradle rc 0). 시험 프로파일만 던진다.
- MUST NOT: 이 저장소의 코드가 운영 `guard_setting`에 행을 넣지 않는다. 운영 켜기는 잠금 jar 배포 뒤 사용자 승인이다.
- MUST NOT: `corporate-action-judge`를 읽기 전용 잡에 넣지 않는다 — 원장과 실행 행을 쓴다(ADR-0084의 「적재 중 쳐도 안전」을 고친다).

## 검증

`./gradlew test --tests "*WriteGuardTriggerTest" --tests "*WriteLockTest" --tests "*PipelineSchedulerChainTest"
--tests "*WriteLockGateTest" --tests "*WriteSessionScriptTest" --tests "*CatalogSeederStatementTest"
--tests "*PresetSeederStatementTest" --tests "*OpsLockScriptTest" --tests "*WriteLockAdminApiTest"`가 초록이어야
한다 — 스위치 · 신원 술어 · 제외 표 · 이벤트 트리거 · `search_path=''` · CAS · 사망 술어 · 세션 만료(실행 중 exec) · 합류 CAS ·
대기 표지 · 상한 없는 대기와 매시간 알림 · 종료 신호로 그만둠 · 대기 중 잠금 표 실패의 표지 걷음 · 못 돈 발화의 시도 행 없음·끝 줄 · 크론의 자기 누수 회수 · 웹 프로파일 세션 변수 거절 · 시더 둘의
「같으면 문장 0 · 다르면 거절」 · `stop.sh`(앱 JVM이 쥘 때만 멈춤) · `crons-done.sh`(실행 행 주인) · 잡 목록 보유자와 409.

전제 펜스는 두지 않는다 — V66 전 운영 DB에는 `guard_setting`이 없어 그 표를 부르는 질의가 파싱에서 죽고(무판정), 운영 켜기 전까지는
잴 것이 없다. 켠 뒤의 관측은 `ops/status.sh`의 보유자 줄이 한다.

## 배경

JVM을 건너는 쓰기 배타가 없었다. 스케줄러 풀 1이 앱 JVM 안의 크론끼리만 겹침을 막았고, bootRun·손 psql·관리자 launch는 손 규칙
(progress의 「다음 명목 발화 전에 끝낸다」)에만 기댔다. 60일 교차 15쌍 6일이 있었고(마지막 09-15), 2부 (나)가 미완료 날 15:00·17:00
재시도를 더하면 쓰기 창이 1에서 3으로 는다 — 손 규칙 하나로는 지금 규칙 아래 관측 0건 위에 선다. 설계는 계획 v1~v5를 반증 여덟
렌즈가 깨며 왔다(PLAN 「쓰기 잠금 계획 v4」 결정 1~15 · v5).

## 버린 대안

- **JVM 안 목록만으로 강제(v1)**: 빠뜨린 진입점(psql · 옛 jar · 새 경로)을 못 잡는다. DB 트리거면 목록이 스스로 검사된다.
- **`ALTER DATABASE … SET app.write_guard`(v2)**: `pg_dump -Fc`가 안 옮기고 열린 세션에 늦게 반영된다 — 스위치는 표의 행이다.
- **부팅 시각을 사망 술어에(v3)**: `kern.boottime`이 같은 부팅 안에서도 움직여(10-05 18:03:39.28 → 10-07 18:03:42.54) 산 보유자를
  죽었다고 판정했다.
- **사슬 예약 D(v3)**: Spring `ScheduledTask.nextExecution()`이 기한 넘긴 대기 크론에 null을 내고, 명목 날짜가 수면에서 갈린다 —
  크론마다 토큰으로 바꿨다.
- **`exec`가 인자 `--write-session=`을 붙임(v3)**: bootRun 앱 인자는 `--args="…"` 문자열 안이라 구현 불가(실측 `Unknown
  command-line option`). 환경변수로 그 자식에게만 준다.
- **박동을 `@Scheduled`로(v4)**: 풀 1이라 크론이 기다리는 동안 박동이 못 돌아 자기 누수 행에서 풀리지 않는 교착 — 전용 데몬 스레드.

## 결과

- 쉬워지는 것: 겹친 쓰기가 조용한 값 오염이 아니라 그 자리의 실패(`SSWG1` · 409 · rc 1)가 된다. 실행 행의 주인이 적혀 스위퍼가
  산 행을 닫지 않는다.
- 어려워지는 것: 손 쓰기 연쇄는 `ops/write-session.sh` 세션 안이어야 한다 — 잠금 jar 뒤에는 잡 사이 틈을 지키려고, 가드를 켠 뒤에는
  무신원 쓰기가 거절되므로. 웹 앱은 bootRun이 쥔 동안 못 뜬다.
- **두 층이 켜지는 때가 다르다.** **잠금 jar 배포만으로** 바뀌는 것(JVM 층 — `WriteLockGate`가 `guard_setting`과 무관하게 켠다):
  bootRun 기동 거절(다른 보유자) · 쓰기 크론의 대기 · `start.sh`·`deploy.sh`의 보유자 거절 · 판정(`-judge`) bootRun 거절 · 관리 화면
  버튼 잠금과 409 · `stop.sh`의 앱 보유 멈춤 · 실행 행 주인과 스위퍼 · 시더의 「같으면 문장 0」. **가드를 켜야** 바뀌는 것(DB 층 ·
  운영 `guard_setting` 행 · 사용자 승인): 무신원 psql·`pg_restore`·옛 jar의 보호 표 쓰기 거절 · 시더의 「다르면 배포된 jar만 쓴다」.
- 가드를 켠 뒤 운영 DB에서는 옛 jar 기동이 시더 거절로 죽는다 — 「두 jar 재생」 오라클(옛 jar와 새 jar를 같은 DB에 돌려 비교)은
  작은 DB에서 돈다.
- **잠금은 손 쓰기 문의 백스톱이다** — 13:50 손 bootRun이 쥐면 14:00~14:20 사슬은 그것이 끝날 때까지 밀린다. 손 쓰기 문은 사용자
  결정 「규칙 유지 + 잠금은 백업」으로 2부 (나) 뒤에도 선다.
- 되돌리기: 잠금 전 jar로 돌아가려면 **먼저** 운영 `guard_setting` 행을 지운다(원시 SQL · 사용자 승인) — 안 지우면 옛 jar의 시더
  문장이 거절돼 기동이 실패한다. V66 객체는 멱등이라 남겨도 다음 배포가 다시 깐다. **이 첫 단계가 되돌리기의 실제 방어다** —
  `ops/deploy.sh`의 「가드 켬 ∧ 트리에 잠금 마이그레이션 없음」 점검은 배포하는 트리 자신의 스크립트라, 되돌려 배포하는 잠금 전
  트리의 옛 `deploy.sh`에는 없다.
