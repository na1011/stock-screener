# stock-screener

코스피·코스닥 우량 종목을 기술적·재무적 조건으로 필터링·추천하는 개인용 웹 스크리너. 본인과 소수 지인이 사용하며, 실용성과 함께 한국 백엔드 채용시장 기준의 개인 학습을 겸한다. 미국(S&P500)은 무료로 접근 가능한 일별 시세 소스가 확인되지 않아 v1.1로 미뤘다([ADR-0010](docs/adr/ADR-0010-defer-us-market.md)).

전체 요구사항, 채택 기술 스택과 그 배경, 수용 기준은 [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md)에 정리되어 있다. 이 문서는 기획 정본이며 함부로 수정하지 않는다.

## 기술 스택

Spring Boot(백엔드) + Vue3(프론트엔드), JPA + QueryDSL, PostgreSQL, PrimeVue DataTable. 온프레미스(맥북) 호스팅 + Cloudflare Tunnel, Google OAuth, Sentry. 각 선택의 배경은 기획 정본의 채택 기술 스택 절에 기록되어 있다.

백엔드는 `src/main/java`, 프론트엔드는 `frontend/`(Vite + Vue3 + TypeScript)에 있다. Gradle이 프론트 번들을 빌드해 classpath의 `static/`으로 실으므로 배포 산출물은 jar 하나다 — 인증이 세션 쿠키 기반이라 같은 오리진이어야 한다([ADR-0018](docs/adr/ADR-0018-single-origin-spa-serving.md)). 어디까지 구현되었는지는 [`docs/progress.md`](docs/progress.md)에 있다.

```bash
./gradlew build     # 백엔드 + 프론트 번들 + 양쪽 테스트
./gradlew bootRun   # http://localhost:8080 — 빌드된 번들을 서빙한다
cd frontend && npm run dev   # http://localhost:5173 — HMR. API는 :8080으로 프록시한다
```

## 문서 지도

| 문서 | 성격 | 수명 | 대상 독자 |
|---|---|---|---|
| [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md) | 기획 정본 — 요구사항·수용 기준·범위 | 불변 | 사람 + 에이전트 |
| [`docs/phase-criteria.md`](docs/phase-criteria.md) | phase 완료 판정 — 착수 전에 먼저 적는다 | 오래감 | 사람 + 에이전트 |
| [`docs/adr/`](docs/adr/) | spec 확정 이후의 새 기술 결정 기록 | 오래감 | 사람 + 에이전트 |
| [`docs/progress.md`](docs/progress.md) | 진행 상태 — 어디까지 왔는가 | 매 세션 변함 | 사람 + 에이전트 |
| [`TODO.md`](TODO.md) | 에이전트가 대신할 수 없는 작업 — 수동 검증·외부 발급·계정 권한 | 끝내면 지움 | 사람 |
| [`CLAUDE.md`](CLAUDE.md) | 에이전트 지시문 — 명령어·설계 제약·금지 사항 | 오래감 | 에이전트 전용 |
| [`.claude/rules/`](.claude/rules/) | 판단 기준 — 영역별(백엔드/프론트/데이터/테스트·설정)과 하네스 자체(`harness-*.md`) 둘로 갈린다. `paths` glob으로 그 파일을 다룰 때만 로드 | 오래감 | 에이전트 전용 |
| [`.claude/skills/`](.claude/skills/) | 작업별 절차 — 그 작업을 하기로 정했을 때만 로드 | 오래감 | 에이전트 전용 |
| [`.claude/hooks/`](.claude/hooks/) | 결정론적 계층 — 차단(안전망) + 알림(상태·문서·ADR 유지) | — | 사람 |

수명으로 나눈 것이 핵심이다. 요구사항·결정은 오래 가므로 조건부로 로드하고, 매 세션 변하는 진행 상태만 항상 주입한다. 전부를 항상 로드하면 컨텍스트 비용이 커지고 정작 중요한 신호가 묻힌다.

## 운영

맥북 한 대 위에서 앱과 터널을 돌린다. **자동으로 뜨는 것은 아무것도 없다** — 당신이 켤 때만 뜨고, 껐으면 꺼진 채로 있다([ADR-0043](docs/adr/ADR-0043-explicit-start-over-autostart.md)). 실행은 [`ops/`](ops/)의 스크립트에 있다(각 스크립트 머리에 용법이 있다).

```
브라우저 ──HTTPS──▶ Cloudflare 엣지 ──터널──▶ cloudflared ──HTTP──▶ Spring :8080
                                          (ops/start.sh --tunnel)   (ops/start.sh --app)
```

| 상황 | 무슨 일이 일어나는가 |
|---|---|
| 앱 프로세스가 죽음 | **내려간 채로 있다.** 다시 `ops/start.sh` |
| 로그아웃 → 로그인 | **안 뜬다.** 다시 `ops/start.sh` |
| 재부팅 | **안 뜬다.** FileVault 암호를 넣고 로그인한 뒤 `ops/start.sh` |
| 잠자기 → 깨어남 | 프로세스가 그대로 살아 있다 (유일하게 저절로 돌아오는 것) |

### 터미널에서 직접 — 켜기, 끄기, 배포

```bash
ops/start.sh          # 앱 + 터널 (--app / --tunnel 로 하나만)
ops/stop.sh           # 끈다. 되살아나지 않는다
ops/status.sh         # 뭐가 도는지, 밖에서 보이는지, 자동 기동이 되살아났는지
ops/deploy.sh         # 코드나 .env를 고쳐 반영 (빌드 → 복사 → 앱만 재기동)
```

`start.sh`는 기동에 20~30초 걸리고 로컬·외부 health를 직접 확인한 뒤에야 끝난다. **외부까지 물어보는 이유는 로컬만 보면 `cloudflared`가 죽어도 초록이기 때문이다** — 로컬은 `UP`인데 밖이 502면 앱이 아니라 터널이 문제다.

- **`kill`이 그대로 듣는다.** 되살리는 것이 아무것도 없기 때문이다. 다만 `stop.sh`를 쓰는 편이 낫다 — PID 파일을 정리하고 포트가 풀렸는지까지 본다.
- **`pkill -f java`는 쓰지 않는 편이 좋다.** 이 저장소와 무관한 JVM까지 함께 죽고, 어떤 가드도 그것을 막지 않는다.
- **`./gradlew build`만으로는 아무것도 반영되지 않는다.** 도는 것은 저장소가 아니라 `~/Library/Application Support/stock-screener/`의 사본이라, 옛 빌드가 계속 돌고 화면에는 아무 단서가 없다([ADR-0034](docs/adr/ADR-0034-repo-out-of-tcc-path.md)). `deploy.sh`가 그 순서를 대신한다.
- 기동 실패 원인은 `~/Library/Logs/stock-screener/stderr.log`에, 터널 쪽은 같은 디렉터리의 `tunnel.log`에 있다.

**IMPORTANT — 배치는 앱 안에서 돈다.** 평일 15시에 앱이 꺼져 있으면 그날 수집이 통째로 빠지고 **아무 알림도 나가지 않는다**(0건이어도 배치는 SUCCESS다). 다음 실행이 15일 창으로 메우므로([ADR-0042](docs/adr/ADR-0042-batch-schedule-and-target-date.md)) 매일 켤 필요는 없지만, **15일에 한 번은 15시에 켜 두어야 한다.** 맥북이 자면 사이트도 함께 내려간다 — 온프레미스를 택한 대가다(spec §6). 절전으로 당신이 할 일은 [`TODO.md`](TODO.md)에 있다.

## 하네스 설계 — 기획형 하네스

이 저장소의 `CLAUDE.md`와 `.claude/rules/`는 **기획형 하네스**로 설계했다. 아키텍처, 패키지 구조, 레이어 분리, 구현 순서 등 "어떻게 만들 것인가"는 하네스에 규정하지 않는다. 작업 시점의 에이전트가 그때그때 판단하도록 맡긴다. 하네스가 고정하는 것은 "무엇을 향해 가는가" — 판단 기준, 범위의 경계, 반드시 지켜야 할 제약뿐이다.

> **규칙 자체는 [`.claude/rules/harness-core.md`](.claude/rules/harness-core.md)에 있고 그 파일이 정본이다.** 이 절은 사람을 위한 **근거와 출처**만 담는다 — 지켜야 할 문장을 여기 적지 않는다. 두 곳에 적으면 반드시 한쪽이 낡는다.

근거는 아래와 같다.

- **구조를 정교화해도 준수율이 오른다는 근거는 없다.** [Instruction Adherence in Coding Agent Configuration Files: A Factorial Study of Four File-Structure Variables](https://arxiv.org/abs/2605.10039) (1,650 Claude Code 세션, 16,050 함수 단위 관측)는 CLAUDE.md의 크기·지시 위치·파일 구조·모순 네 변수와 그 상호작용 어디에서도 다중검정 보정 후 유의한 대비가 검출되지 않았다고 보고한다. 이는 "효과가 없음의 증명"이 아니라 "검출되지 않음"이다. 반면 세션 내 준수 오즈는 스텝당 약 5.6%씩 감소했다(OR = 0.944). 산문을 다듬는 것만으로 준수를 확보할 수 있다는 가정은 세우지 않고, 반드시 지켜야 할 것은 결정론적 계층(hook)에 둔다.

- **최소 고신호 토큰이 최적이다.** Anthropic의 [Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)는 "right altitude" 개념을 제시한다 — 과잉 명세는 LLM을 룰 엔진처럼 취급해 취약하게 만들고, 과소 명세는 신호를 주지 못한다. 구현 방식을 못박는 대신 강한 휴리스틱만 준다.

- **금지 나열보다 판단 기준을 준다.** [Trust Over Fear: How Motivation Framing in System Prompts Affects AI Agent Debugging Depth](https://arxiv.org/abs/2603.14373)는 신뢰 프레이밍이 **무프레이밍 baseline 대비** 숨은 이슈 발견 +59%, 탐색 스텝 +83%였다고 보고한다(Study 1). 신뢰 대 공포 직접 비교는 Study 2로, 스텝 +74%·숨은 이슈 +25%였고 공포 프레이밍은 baseline 대비 유의한 개선이 없었다. 다만 이 논문은 단일 저자 preprint이고 자체 방법론을 스스로 평가한 것이며 Study 1의 시나리오가 9개에 불과해 증거력이 약하다. 하네스 설계를 이 근거에만 기대지 않되, 금지 목록을 정말 위험한 것만 남기는 방향과 어긋나지 않는다는 정도로 참고한다.

- **판단이 개입하는 규칙에는 이유를 함께 적는다.** 자율판단 하네스의 실패 모드는 규정되지 않은 상황에서 표면만 비슷한 다른 판단을 내리는 것이다. 이유를 알면 같은 의도로 새 상황을 판단할 수 있다. Anthropic이 2026년 1월 Claude의 constitution을 개정하며 채택한 원리와 같다 — *"models... need to understand why we want them to behave in certain ways, and we need to explain this to them rather than merely specify what we want them to do. If we want models to exercise good judgment across a wide range of novel situations, they need to be able to generalize—to apply broad principles rather than mechanically following specific rules."* ([Claude's new constitution](https://anthropic.com/news/claude-new-constitution), 2023년판의 "list of standalone principles"에서 의도적으로 전환한 것이다.)

  다만 근거의 강도를 정확히 밝혀둔다. 이는 **벤더가 자사 모델 설계에 채택한 원리이자 실사용 경험과 부합하는 것**이지, 통제 실험으로 증명된 것이 아니다. 규칙 집합을 고정하고 이유 문장만 넣고 뺀 비교 연구는 찾지 못했다. 반대로 지시가 길어질수록 준수율이 떨어진다는 결과들이 있으나, 그 실험들은 서로 다른 새 제약을 추가한 것이지 기존 규칙에 설명을 덧붙인 것이 아니어서 그대로 전이되지는 않는다. 이 불확실성이 `harness-core.md`가 이유의 사용 범위를 좁힌 까닭이다.

- **되돌리기 어려운 결정은 자율성의 대가로 기록한다.** 아키텍처 자율을 주는 대신, 새 의존성·저장 구조·외부 연동처럼 되돌리기 어려운 판단은 `docs/adr/`에 남긴다. 허가를 구하지 않고 결정한 뒤 기록하는 방식이다.

- **`CLAUDE.md` 작성 기준의 출처는 [공식 작성 가이드](https://code.claude.com/docs/en/best-practices)다.** 선별·강조 규칙은 `harness-core.md`, 분량 상한은 `harness-docs.md`에 있다.

phase 분할·구체적 아키텍처 설계는 이 하네스의 범위가 아니며, 별도의 설계 세션에서 다룬다.

### 사람이 쓰는 문서는 두 장뿐이다

이 저장소에서 사람이 저작하는 문서는 [`기획 정본`](docs/stock-screener-project-spec.md)과 [`스윙 프리셋 조건셋`](docs/external/screener-spec-midterm-swing.md) 둘이고, **층이 다르다 — 충돌하면 기획 정본이 이긴다**(ADR-0041). phase 완료 판정도, 결정의 근거도, 진행 상태도, 커밋 로그도 전부 **실행 시점의 에이전트가 판단해서 쌓은 것**이다. 사람이 하는 일은 정본을 쓰는 것과, 정본을 바꿀지 승인하는 것과, 에이전트가 원리적으로 못 하는 일([`TODO.md`](TODO.md))뿐이다.

그래서 이 구조는 프로젝트에 종속되지 않는다. 슬롯의 이름과 각 슬롯이 답하는 질문만 옮기면 다른 저장소에서도 같은 방식이 성립한다 — 그 절차는 전역 skill로 빼 두었고, 이 저장소가 그 참조 구현이다. **여기에 스킬 이름을 적지 않는다** — 루트 README는 훅의 검증 대상이 아니라(위 배치 원칙) 이름이 바뀌어도 아무도 알려주지 않는다. `.claude/skills/`와 전역 skill 목록이 정본이다.

안전 가드(`guard-secrets.sh`·`guard-destructive.sh`)는 **저장소 안과 전역 양쪽에** 등록해 둔다. 저장소 사본은 다른 클론에서도 살아남고, 전역 사본은 이 하네스가 아직 없는 프로젝트와 저장소 밖 세션을 덮는다. 둘이 갈리면 저장소 안에서 막히는 것이 밖에서는 통과하므로, 전역 `SessionStart` 훅이 두 사본의 해시를 대조한다.

## 무엇을 어디에 둘 것인가 — 기제는 트리거로 고른다

**규칙 자체는 `.claude/rules/`의 `harness-*.md` 넷에 있다** — 배치표는 [`harness-core.md`](.claude/rules/harness-core.md), 훅의 강제 수단은 [`harness-hooks.md`](.claude/rules/harness-hooks.md), `paths` 확인 절차는 [`harness-rules.md`](.claude/rules/harness-rules.md), 지시문·상태 문서 기준은 [`harness-docs.md`](.claude/rules/harness-docs.md)다. 하네스 파일을 열면 자동으로 뜨므로 여기 옮겨 적지 않는다. 이 절에는 그 규칙이 **어떻게 나왔는지**만 남긴다. 결론을 바꾸려는 게 아니면 읽을 필요 없다.

주입 상한을 반복해서 넘긴 끝에 내용을 glob 규칙으로 내렸고, 그때 "이럴 바엔 skill이 낫지 않은가"라는 질문이 나왔다. 전수 감사 결과는 반대였다 — glob이 비용(매칭 안 되면 0토큰)과 신뢰도(모델 판단이 아니라 기계적 조건에 발화) 양쪽에서 이겼다. 문제는 기제가 아니라 glob이 *주제*로 쓰인 것이었다: `application.yaml`을 지키는 규칙이 `src/main/java/**`에, `build.gradle`을 지키는 규칙이 `src/test/**`에 붙어 있었다. 고칠 파일이 아니라 안 고칠 파일에 규칙이 있었다.

그래서 **기제는 트리거로 고르고, 트리거는 지식의 종류가 정한다**로 정리했다.

### 실측 — 규칙 집합은 세션 시작에 확정되고, 그 경계는 `/clear`다

셋을 카나리로 확인했다. 셋 다 절차 형태로는 `harness-rules.md`에 있고, 여기에는 어떻게 알아냈는지만 남긴다.

**첫째, 세션 중간에 만든 규칙 파일은 매칭 파일을 열어도 그 세션에서 발화하지 않는다.** 한 세션에서 만들어진 `test.md`가 같은 세션 내내 로드되지 않았고 아무도 몰랐다.

**둘째, 갱신 경계는 새 프로세스가 아니라 `/clear`다**(2026-08-07). 직전 세션이 만든 카나리 규칙 두 개(`**/*.sql`·`src/main/java/**`)가 `/clear` 직후 매칭 파일을 Read하자 즉시 발화했고, `settings.json`에 아무것도 등록하지 않았는데 인식됐다. `SessionStart` matcher가 `startup|resume|clear`인 것과 일치한다. **둘째가 첫째의 대가를 없앤다** — 규칙을 고친 세션이 `/clear` 한 번으로 스스로 검증할 수 있고, 확인이 다음 세션까지 미뤄지지 않는다.

**셋째, 재주입 여부는 관측이 갈렸다.** 2026-08-07에는 `config.md`가 `build.gradle`에서 뜬 뒤 같은 `paths`의 `frontend/vite.config.ts`에서 다시 뜨지 않아 "세션당 1회"로 읽혔다. 그런데 2026-08-09에는 당시 단일 파일이던 하네스 규칙이 한 세션에 **두 번** 떴다(`docs/progress.md`·`.claude/rules/test.md`). 무엇이 둘을 가르는지 모른다.

셋 다 관측이 얕다. `/clear`보다 얕은 경계(턴 단위 지연 로드 등)가 있는지는 확인하지 않았다. **확인을 밟지 않았으면 "적었으니 걸린다"고 가정하지 않는다.**

### 규칙에는 규칙만 둔다 (2026-08-07)

정본을 규칙 파일로 옮긴 것이 이 감사의 결과다(당시에는 단일 파일이었고, 뒤에 트리거 단위로 `harness-*.md` 넷으로 갈렸다). 그전까지는 근거가 이 문서에, 강제가 규칙 파일에 있었다.

그 배치는 **규칙 파일이 제약에 대해 금지한 것과 같은 형태**였다. README는 자동 로드되지 않아 에이전트가 스스로 "읽어야겠다"고 판단해야 도달하는데, MUST를 뒤집으려는 세션은 자기가 근거를 모른다는 것을 모른다 — 제약을 skill에 두면 안 되는 이유와 정확히 같다(`harness-core.md`). 게다가 근거의 발화 조건("뒤집으려 할 때만")은 그 규칙의 `paths`와 **이미 일치하고 있었다** — 지금 `harness-core.md`가 `CLAUDE.md`·`README.md`·`.claude/rules/**`·`.claude/hooks/**`·`.claude/skills/**`·`.claude/settings.json`·`docs/progress.md`를 덮는 것이 그것이다. 문서만 둘로 갈려 있었고, 그 결과 두 문서가 약 3,000자를 서로 복사하고 있었다.

대가는 규칙 파일로도 샜다. 감사해 보니 `.claude/rules/` 5개 파일 10군데에 "어디서 옮겨 왔다", "트리거가 X라서 여기 뒀다" 같은 이력이 붙어 있었다 — 같은 문장이 세 파일에 한 글자도 다르지 않게 반복되기도 했다. **근거를 밖으로 내보내니 규칙마다 조금씩 새어 들어온 것이다.** 증상을 걷고, 재발을 막는 금지를 `harness-core.md`에 명시했다.

## hook — 차단과 알림

`.claude/hooks/`의 스크립트는 에이전트의 판단과 무관하게 실행되는 결정론적 계층이다. 두 가지 성격이 있다.

**차단(`PreToolUse`)** — 되돌릴 수 없는 사고를 막는다.

| 스크립트 | 대상 | 결정 |
|---|---|---|
| `guard-secrets.sh` | 시크릿을 담을 수 있는 파일 경로 접근 | `deny` |
| `guard-destructive.sh` | 파괴적 명령 / Bash 경유 시크릿 읽기 | `deny` / `ask` |
| `guard-spec.sh` | 기획 정본 문서 수정 | `ask` |

**알림(`PostToolUse`·`SessionStart`)** — 차단하지 않고 상기시킨다.

| 스크립트 | 시점 | 하는 일 |
|---|---|---|
| `inject-progress.sh` | 세션 시작 | `docs/progress.md`를 컨텍스트에 주입 |
| `check-doc-staleness.sh` | 세션 시작 | 문서의 주장과 실제 상태(코드 또는 다른 정본)의 불일치를 보고 |
| `check-premises.sh` | 세션 시작 | ADR `## 검증`의 `premise` 펜스를 실제로 돌려, 결정의 **전제가 아직 참인지**를 잰다 |
| `remind-adr.sh` | 파일 편집 직후 | 두 방향 — ADR **기록** 대상인지 상기(의존성·인프라·설정), 그리고 그 파일을 **지배하는** ADR이 있으면 포인터로 알림(`applies_to` 매칭) |
| `echo-adr-citation.sh` | 파일 편집 직후 | 방금 인용한 ADR 번호의 **정본 제목**을 되돌려 준다. 번호만 맞춘 인용을 다음 세션이 근거로 믿는 것을 막는다 |
| `remind-rule-on-write.sh` | 파일 편집 직후 | 편집한 파일을 지배하는 `paths` 규칙을 포인터로 알림. 새 파일 `Write`는 사전 Read를 요구하지 않아 규칙 없이 고치게 되는 구멍을 메운다 |
| `warn-bootstrap-args.sh` | 적재 배치 실행 직후 | `--reload-existing` 없는 `initial-load`가 아무 일도 안 하고 성공으로 끝나는 조합을 알림 |
| `warn-load-running.sh` | Bash 실행 직후 | 적재가 도는 동안 gradle을 건드리면 알림. 컴파일 산출물이 도는 JVM 밑에 깔려 있어 `clean`·`--stop`이 적재를 죽인다 |
| `remind-rule-on-shell.sh` | Bash 실행 직후 | 셸로 다룬 파일을 지배하는 `paths` 규칙을 알림. `paths`는 Read·Edit에만 발화해 셸 경유는 구멍이 된다 |
| `remind-skill-on-channel.sh` | MCP 채널 진입 직후 | 그 채널의 skill을 알림. 파일도 argv도 아닌 트리거라 glob·Bash 훅으로는 표현할 수 없다 |
| `remind-plan-obligations.sh` | 계획 기록 직후 | 새 phase의 완료 판정과 ADR 기록 지점을 착수 **전에** 정하도록 상기 |

**등록의 정본은 [`.claude/settings.json`](.claude/settings.json)이다.** 위 표는 각 훅이 왜 있는지를 사람에게 설명하는 자리이고, 무엇이 실제로 도는지는 그 파일이 답한다.

`remind-adr.sh`의 두 번째 방향이 생기기 전까지 20개 ADR의 `applies_to`는 **아무도 읽지 않는 필드**였다. 문서 두 곳이 "자동 로드된다"고 적고 있었지만 그것을 구현한 코드가 없었다. 지금은 편집한 경로를 frontmatter와 매칭해 **본문이 아니라 포인터**를 낸다 — 본문 주입은 편집당 수천 자가 되어 아무것도 안 하는 것보다 나쁘다.

`warn-bootstrap-args.sh`만 트리거가 파일이 아니라 **argv의 모양**이다. 적재는 어떤 파일도 열지 않는 Bash 작업이라 `paths` glob으로는 원리적으로 표현할 수 없고, 그래서 이 함정만 hook에 있다. 나머지 절차는 `batch-load` skill에 있다.

알림 계층을 차단이 아닌 상기로 둔 이유는, 기록 여부와 시점의 판단까지 에이전트에게 남기기 위해서다. 어느 이벤트가 차단할 수 있고 없는지는 `harness-hooks.md`에 있다.

## 세션 간 핸드오프

phase마다 세션이 나뉘면 새 세션은 이전 세션이 무엇을 했는지 모른 채 시작한다. Claude Code에는 이를 위한 네이티브 수단이 없다 — 플랜 모드 산출물은 대화 안에만 존재하고 승인해도 저장소에 남지 않으며, `--resume`은 *같은* 세션 재개일 뿐이다. [claude-code#11455](https://github.com/anthropics/claude-code/issues/11455)가 이 기능을 요청 중이나 미해결이다.

그래서 직접 만든다. 구조는 **수명으로 나누고, 변하는 것만 항상 주입한다**는 것이다.

- **`docs/progress.md`가 진행 상태의 단일 지점이다.** `SessionStart` 훅이 이를 주입하므로 새 세션은 첫 프롬프트 이전에 이미 알고 있다. 저장소에 파일을 두는 것만으로는 부족하다 — 새 세션은 자신이 무엇을 모르는지조차 모르므로 "알아서 찾아 읽겠지"는 성립하지 않는다.
- **ADR 대상이 아닌 결정도 여기 남긴다.** 패키지 구조나 조건 모델의 형태처럼 되돌리기는 쉽지만 모르면 다시 만들게 되는 판단이다. ADR 규약은 이런 것을 의도적으로 제외하는데(사소한 것까지 남기면 중요한 결정이 묻힌다), 그렇다고 아무 데도 안 남기면 **다음 세션이 가장 알아야 할 것이 가장 안 남는다.**
- **주장은 반증 가능한 형태로 쓴다.** "Phase 1 완료"가 아니라 "완료 판정: `./gradlew build` 성공 + ADR 존재"처럼 쓴다. 사람은 낡은 문서를 보면 낡았다고 의심하지만 에이전트는 그대로 믿는다. `check-doc-staleness.sh`가 이 주장을 실제 코드와 대조한다.
- **쓰기 측이 비어 있다.** 갱신되지 않는 진행 상태는 다음 세션을 돕는 대신 잘못 인도하는데, 갱신을 상기시키는 계층이 지금 없다. `SessionEnd`에 걸었던 것은 그 시점에 소비자가 없어 걷어냈다 — 남은 것은 `CLAUDE.md`의 규칙 한 줄뿐이다.

완료 판정(`docs/phase-criteria.md`)을 저장소에 두는 이유도 같다. 플랜 모드 산출물은 `~/.claude/plans/`에 쌓이는데 그곳은 git 밖이고 자동 로드되지 않아, 다음 세션이 찾을 수 없다. 저장소에 있어야 grep되고, 코드와 함께 diff되고, 틀렸으면 revert된다.

**`progress.md`에 합치지 않은 이유는 예산이다.** 그 파일은 매 세션 전량 주입되고 절단선이 8,000자인데, 판정 블록은 phase 하나가 450~2,746자다. 합치면 경고선(4,000자)이 상시로 켜지고, 그러면 주입이 실제로 잘렸을 때 그 신호가 상시 경보에 묻힌다 — 이 저장소가 가장 경계하는 조용한 실패다. 판정은 그 phase를 하는 세션만 필요로 하므로 조건부 로드가 맞다.

이 구조는 spec-kit·Kiro·task-master 같은 도구들이 독립적으로 수렴한 형태와 같다 — 오래 가는 것은 좁게 조건부로 로드하고, 변하는 상태만 항상 주입한다.

이 계층이 필요한 이유는 산문 지시가 시간이 지나면 희석되기 때문이다. 위에 인용한 준수율 감쇠는 세션 후반일수록 심해지는데, 아키텍처 결정은 대개 세션 후반에 내려진다.

모든 스크립트는 매칭되지 않으면 통과시킨다. hook이 워크플로우를 막지 않는다는 것이 설계 원칙이고, `jq`가 없는 환경에서도 차단 대신 통과를 선택한다.

**다만 "통과"가 "침묵"은 아니다.** 매칭되지 않은 것은 판정 결과이므로 조용히 통과하지만, **판정 자체를 못 한 것**(의존성 부재 등)은 드러낸다 — 그러지 않으면 가드가 죽은 것이 성공과 구분되지 않고, 안전을 전부 훅에 건 이 설계가 소리 없이 무너진다. 드러내는 수단은 이벤트가 정한다(`SessionStart`는 평문 stdout, 도구 호출 훅은 stderr + `exit 1`). 매 호출 훅은 의존성 부재를 `check-doc-staleness.sh`에 위임해 조용히 통과하되, **위임은 보고자가 그것을 실제로 세고 있을 때만 성립한다.** 규칙 전문과 경계는 [`.claude/rules/harness-hooks.md`](.claude/rules/harness-hooks.md)에 있다.

## 지시문 작성 스타일

규칙 문장에 `MUST:` / `MUST NOT:` 접두어를 붙여 강도를 명시한다. 모호한 산문 대신 검증 가능한 단정을 주면 에이전트가 요약하지 않고 문자 그대로 따른다. **지켜야 할 규칙 자체는 [`.claude/rules/harness-core.md`](.claude/rules/harness-core.md)에 있다.**
