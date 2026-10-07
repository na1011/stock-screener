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

## 규칙

- MUST: 보호 = public의 **보호 제외 열하나 밖 모든 표**(`write_guard_exempt`). 새 표는 이벤트 트리거가 만들어지는 순간 단다(임시 표 ·
  다른 스키마 제외). 제외 표와 각 사유는 V66 ③에 있다 — 늘리려면 「웹 사용자 흐름만 쓰는 표」인지 성질로 다시 센다.
- MUST: 쓰기 신원은 `ss:j:<pid>:<시작초>`(JVM) · `ss:s:<id>`(세션의 psql·pg_restore — libpq `PGAPPNAME`) ·
  `ss:s:<id>:j:<pid>:<시작초>`(세션에 합류한 bootRun — 환경변수 `SS_WRITE_SESSION`)다. 세션 id는 `[a-z0-9-]{1,24}`.
- MUST: 사망 술어는 **pid 부재 ∨ 프로세스 시작 초 불일치**뿐이다. MUST NOT: 부팅 시각·박동 나이를 사망 근거로 쓰지 않는다.
- MUST: 획득은 관측한 행에 대한 CAS(`version`)이고 술어는 「비었음 ∨ 보유 JVM 죽음 ∨ 세션 만료 ∨ (보유 신원 = 나 ∧ 내 레지스트리에
  산 토큰 0)」이다. 산 대기 표지가 남의 것이면 쥐지 않는다.
- MUST: 쓰기 크론 여섯(13:30 · 14:00 · 14:15 · 14:18 · 14:20 · 토 20:00)은 바깥 `try` 맨 앞에서 쥐고(대기), 발화한 날 23:00이 넘으면
  건너뛴다 — 건너뜀은 예외가 아니고 실행 행이 없다. 15:30 신선도 점검은 읽기라 안 쥔다.
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
대기 표지 · 23:00 건너뜀 · 크론 건너뜀 날 시도 행 없음·끝 줄 · 크론의 자기 누수 회수 · 웹 프로파일 세션 변수 거절 · 시더 둘의
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
- **박동을 `@Scheduled`로(v4)**: 풀 1이라 크론이 기다리는 동안 박동이 못 돌아 자기 누수 행에서 23:00까지 교착 — 전용 데몬 스레드.

## 결과

- 쉬워지는 것: 겹친 쓰기가 조용한 값 오염이 아니라 그 자리의 실패(`SSWG1` · 409 · rc 1)가 된다. 실행 행의 주인이 적혀 스위퍼가
  산 행을 닫지 않는다.
- 어려워지는 것: 손 쓰기는 전부 `ops/write-session.sh` 세션 안이어야 한다(가드가 켜진 DB). 웹 앱은 bootRun이 쥔 동안 못 뜬다.
- **잠금은 손 쓰기 문의 백스톱이다** — 13:50 손 bootRun이 쥐면 14:00~14:20 사슬은 그것이 끝날 때까지 밀린다. 문을 걷는 것은 사슬을
  한 메서드로 묶는 2부 (나)의 몫이다.
- 되돌리기: 잠금 전 jar로 돌아가려면 **먼저** 운영 `guard_setting` 행을 지운다(원시 SQL · 사용자 승인) — 안 지우면 옛 jar의 시더
  문장이 거절돼 기동이 실패한다. V66 객체는 멱등이라 남겨도 다음 배포가 다시 깐다. **이 첫 단계가 되돌리기의 실제 방어다** —
  `ops/deploy.sh`의 「가드 켬 ∧ 트리에 잠금 마이그레이션 없음」 점검은 배포하는 트리 자신의 스크립트라, 되돌려 배포하는 잠금 전
  트리의 옛 `deploy.sh`에는 없다.
