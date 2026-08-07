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
| [`docs/design/`](docs/design/) | phase별 설계 방향 — 검증된 사실과 판단 논점 | 오래감 | 사람 + 에이전트 |
| [`docs/adr/`](docs/adr/) | spec 확정 이후의 새 기술 결정 기록 | 오래감 | 사람 + 에이전트 |
| [`docs/progress.md`](docs/progress.md) | 진행 상태 — 어디까지 왔는가 | 매 세션 변함 | 사람 + 에이전트 |
| [`docs/external-credentials.md`](docs/external-credentials.md) | 사용자가 직접 발급해야 하는 외부 키·계정 목록 | 발급 시 갱신 | 사람 |
| [`TODO.md`](TODO.md) | 에이전트가 대신할 수 없는 작업 — 수동 검증·외부 발급·계정 권한 | 끝내면 지움 | 사람 |
| [`CLAUDE.md`](CLAUDE.md) | 에이전트 지시문 — 명령어·설계 제약·금지 사항 | 오래감 | 에이전트 전용 |
| [`.claude/rules/`](.claude/rules/) | 영역별(백엔드/프론트/데이터) 판단 기준 — `paths` glob으로 그 파일을 다룰 때만 로드 | 오래감 | 에이전트 전용 |
| [`.claude/skills/`](.claude/skills/) | 작업별 절차 — 그 작업을 하기로 정했을 때만 로드 | 오래감 | 에이전트 전용 |
| [`.claude/hooks/`](.claude/hooks/) | 결정론적 계층 — 차단(안전망) + 알림(상태·문서·ADR 유지) | — | 사람 |

수명으로 나눈 것이 핵심이다. 요구사항·결정은 오래 가므로 조건부로 로드하고, 매 세션 변하는 진행 상태만 항상 주입한다. 전부를 항상 로드하면 컨텍스트 비용이 커지고 정작 중요한 신호가 묻힌다.

## 하네스 설계 — 기획형 하네스

이 저장소의 `CLAUDE.md`와 `.claude/rules/`는 **기획형 하네스**로 설계했다. 아키텍처, 패키지 구조, 레이어 분리, 구현 순서 등 "어떻게 만들 것인가"는 하네스에 규정하지 않는다. 작업 시점의 에이전트가 그때그때 판단하도록 맡긴다. 하네스가 고정하는 것은 "무엇을 향해 가는가" — 판단 기준, 범위의 경계, 반드시 지켜야 할 제약뿐이다.

이 결정의 근거는 아래와 같다.

- **구조를 정교화해도 준수율이 오른다는 근거는 없다.** [Instruction Adherence in Coding Agent Configuration Files: A Factorial Study of Four File-Structure Variables](https://arxiv.org/abs/2605.10039) (1,650 Claude Code 세션, 16,050 함수 단위 관측)는 CLAUDE.md의 크기·지시 위치·파일 구조·모순 네 변수와 그 상호작용 어디에서도 다중검정 보정 후 유의한 대비가 검출되지 않았다고 보고한다. 이는 "효과가 없음의 증명"이 아니라 "검출되지 않음"이다. 반면 세션 내 준수 오즈는 스텝당 약 5.6%씩 감소했다(OR = 0.944). 산문을 다듬는 것만으로 준수를 확보할 수 있다는 가정은 세우지 않고, 반드시 지켜야 할 것은 결정론적 계층(hook)에 둔다.

- **최소 고신호 토큰이 최적이다.** Anthropic의 [Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)는 "right altitude" 개념을 제시한다 — 과잉 명세는 LLM을 룰 엔진처럼 취급해 취약하게 만들고, 과소 명세는 신호를 주지 못한다. 구현 방식을 못박는 대신 강한 휴리스틱만 준다.

- **금지 나열보다 판단 기준을 준다.** [Trust Over Fear: How Motivation Framing in System Prompts Affects AI Agent Debugging Depth](https://arxiv.org/abs/2603.14373)는 신뢰 프레이밍이 **무프레이밍 baseline 대비** 숨은 이슈 발견 +59%, 탐색 스텝 +83%였다고 보고한다(Study 1). 신뢰 대 공포 직접 비교는 Study 2로, 스텝 +74%·숨은 이슈 +25%였고 공포 프레이밍은 baseline 대비 유의한 개선이 없었다. 다만 이 논문은 단일 저자 preprint이고 자체 방법론을 스스로 평가한 것이며 Study 1의 시나리오가 9개에 불과해 증거력이 약하다. 하네스 설계를 이 근거에만 기대지 않되, 금지 목록을 정말 위험한 것만 남기는 방향과 어긋나지 않는다는 정도로 참고한다.

- **자율성은 선언이 아니라 규칙의 부재로 구현한다.** CLAUDE.md에 "너는 자율적이다"라고 적는 것은 에이전트가 읽고 취할 행동이 없는 메타 문장이다. 아키텍처 규칙을 애초에 쓰지 않으면 에이전트는 이미 자율적이다. 그래서 `CLAUDE.md`에는 자율성 선언이 없다 — 대신 명령어, 검증 가능한 설계 제약, 최소한의 금지 사항만 있다.

- **rules에는 spec 재진술을 넣지 않는다.** 에이전트가 spec을 읽으면 알 수 있는 내용은 토큰만 쓰고 신호를 주지 못한다. `.claude/rules/`에는 "이 영역에서 실수하기 쉬운 지점"과 그 판단의 인과관계만 남긴다.

- **판단이 개입하는 규칙에는 이유를 함께 적는다.** 자율판단 하네스의 실패 모드는 규정되지 않은 상황에서 표면만 비슷한 다른 판단을 내리는 것이다. 이유를 알면 같은 의도로 새 상황을 판단할 수 있다. Anthropic이 2026년 1월 Claude의 constitution을 개정하며 채택한 원리와 같다 — *"models... need to understand why we want them to behave in certain ways, and we need to explain this to them rather than merely specify what we want them to do. If we want models to exercise good judgment across a wide range of novel situations, they need to be able to generalize—to apply broad principles rather than mechanically following specific rules."* ([Claude's new constitution](https://anthropic.com/news/claude-new-constitution), 2023년판의 "list of standalone principles"에서 의도적으로 전환한 것이다.)

  다만 근거의 강도를 정확히 밝혀둔다. 이는 **벤더가 자사 모델 설계에 채택한 원리이자 실사용 경험과 부합하는 것**이지, 통제 실험으로 증명된 것이 아니다. 규칙 집합을 고정하고 이유 문장만 넣고 뺀 비교 연구는 찾지 못했다. 반대로 지시가 길어질수록 준수율이 떨어진다는 결과들이 있으나, 그 실험들은 서로 다른 새 제약을 추가한 것이지 기존 규칙에 설명을 덧붙인 것이 아니어서 그대로 전이되지는 않는다. 이 불확실성 때문에 이유는 **판단이 갈리는 지점에만** 붙이고, 빌드 명령·형식 명세·열거에는 붙이지 않는다.

- **되돌리기 어려운 결정은 자율성의 대가로 기록한다.** 아키텍처 자율을 주는 대신, 새 의존성·저장 구조·외부 연동처럼 되돌리기 어려운 판단은 `docs/adr/`에 남긴다. 허가를 구하지 않고 결정한 뒤 기록하는 방식이다.

- **CLAUDE.md 자체는 [공식 작성 가이드](https://code.claude.com/docs/en/best-practices)를 따른다.** 공식 권장 분량은 200줄 미만이며, 각 줄에 "이걸 지우면 에이전트가 실수하는가?"를 적용해 아니면 잘라낸다. 강조는 `IMPORTANT:` 접두사로 정말 위험한 항목에만 붙인다.

phase 분할·구체적 아키텍처 설계는 이 하네스의 범위가 아니며, 별도의 설계 세션에서 다룬다.

## 무엇을 어디에 둘 것인가 — 기제는 트리거로 고른다

주입 상한을 반복해서 넘긴 끝에 내용을 glob 규칙으로 내렸고, 그때 "이럴 바엔 skill이 낫지 않은가"라는 질문이 나왔다. 전수 감사 결과는 반대였다.

**비용으로 보면 매칭되지 않는 `paths` 규칙은 0토큰이지만 skill은 `description`이 매 세션 상주한다. 신뢰도로 보면 규칙은 기계적 조건에, skill은 모델의 판단에 발화한다.** glob은 더 싸고 더 확실하다. 문제는 기제가 아니라 glob이 *주제*로 쓰인 것이었다 — `application.yaml`을 지키는 규칙이 `src/main/java/**`에, `build.gradle`을 지키는 규칙이 `src/test/**`에 붙어 있었다. 고칠 파일이 아니라 안 고칠 파일에 규칙이 있었다.

그래서 **기제는 트리거로 고르고, 트리거는 지식의 종류가 정한다**로 정리했다.

| 종류 | 발화 조건 | 기제 |
|---|---|---|
| **제약** — 일어나면 안 되고 위반이 조용한 것 | 위반은 계획되지 않는다. 어길 참인 줄 모른다 | 항상 참이면 `CLAUDE.md` · 파일이 트리거면 `rules`+`paths` · 명령이 트리거면 hook |
| **절차** — 하기로 정한 뒤 실행하는 순서 | 이미 정했으므로 찾을 줄 안다 | skill |
| **상태** — 지금 참이고 앞으로 변할 사실 | 다음 세션은 자기가 뭘 모르는지 모른다 | `SessionStart` 주입 |
| **근거** — 과거 결정의 이유 | 뒤집으려 할 때만 | 참조 문서 + 포인터 hook |

**제약을 skill에 두지 않는다.** skill은 "도움이 필요하다"는 모델의 믿음에 발화하는데, 제약의 실패 모드는 정확히 그 반대다 — 당연히 맞다고 믿기 때문에 조회하지 않는다. 튜닝 문제가 아니라 구조 문제다. 게다가 pull 기제는 세션이 길어질수록 떨어지는 준수율에 종속되는데, 제약이 가장 필요한 시점이 바로 그 약한 시점이다.

**기제는 정보를 더할 뿐 선택지를 줄이지 않는다.** 차단(`deny`/`ask`)은 되돌릴 수 없는 것에만 쓴다. 되돌릴 수 있는 실수는 알림으로 둔다 — `aece427`이 무인 자율 실행을 위해 승인 게이트를 걷어낸 것과 같은 기준이다. `warn-bootstrap-args.sh`가 `ask`가 아니라 알림인 이유가 이것이다. 같은 이유로 **skill은 지시가 아니라 참조로 쓴다** — 따라야 할 순서가 아니라 알아야 할 사실과 함정을 적는다. 순서로 적으면 에이전트가 대본으로 읽고 판단을 멈추는데, 그것이 기획형 하네스가 무너지는 방식이다.

### 실측 — 규칙 집합은 세션 시작에 확정되고, 그 경계는 `/clear`다

두 번의 카나리로 확인했다.

**첫째, 세션 중간에 만든 규칙 파일은 매칭 파일을 열어도 그 세션에서 발화하지 않는다.** 한 세션에서 만들어진 `test.md`가 같은 세션 내내 로드되지 않았고 아무도 몰랐다.

**둘째, 갱신 경계는 새 프로세스가 아니라 `/clear`다**(2026-08-07). 직전 세션이 만든 카나리 규칙 두 개(`**/*.sql`·`src/main/java/**`)가 `/clear` 직후 매칭 파일을 Read하자 즉시 발화했고, `settings.json`에 아무것도 등록하지 않았는데 인식됐다. `SessionStart` matcher가 `startup|resume|clear`인 것과 일치한다.

둘째가 첫째의 대가를 없앤다. **규칙을 새로 만들거나 glob을 고친 세션이 `/clear` 한 번으로 스스로 검증할 수 있다** — 확인이 다음 세션까지 미뤄지지 않는다. 절차는 두 단계다.

1. `git ls-files '<glob>'`으로 매칭이 1건 이상인지 본다. 0건인 glob은 규칙을 영원히 로드되지 않게 만들고, 그 사실은 어디에도 드러나지 않는다.
2. `/clear` 후 매칭 파일 하나를 Read해 규칙 본문이 실제로 뜨는지 본다.

`/clear`보다 얕은 경계(턴 단위 지연 로드 등)가 있는지는 확인하지 않았다. 확인한 것은 `/clear`로 충분하다는 것까지다. **2단계를 밟지 않았으면 "적었으니 걸린다"고 가정하지 않는다.**

## hook — 차단과 알림

`.claude/hooks/`의 스크립트는 에이전트의 판단과 무관하게 실행되는 결정론적 계층이다. 두 가지 성격이 있다.

**차단(`PreToolUse`)** — 되돌릴 수 없는 사고를 막는다.

| 스크립트 | 대상 | 결정 |
|---|---|---|
| `guard-secrets.sh` | 시크릿을 담을 수 있는 파일 경로 접근 | `deny` |
| `guard-destructive.sh` | 파괴적 명령 / Bash 경유 시크릿 읽기 | `deny` / `ask` |
| `guard-spec.sh` | 기획 정본 문서 수정 | `ask` |

**알림(`PostToolUse`·`SessionStart`·`SessionEnd`)** — 차단하지 않고 상기시킨다.

| 스크립트 | 시점 | 하는 일 |
|---|---|---|
| `inject-progress.sh` | 세션 시작 | `docs/progress.md`를 컨텍스트에 주입 |
| `check-doc-staleness.sh` | 세션 시작 | 문서의 주장과 실제 코드 상태의 불일치를 보고 |
| `remind-adr.sh` | 파일 편집 직후 | 두 방향 — ADR **기록** 대상인지 상기(의존성·인프라·설정), 그리고 그 파일을 **지배하는** ADR이 있으면 포인터로 알림(`applies_to` 매칭) |
| `warn-bootstrap-args.sh` | 적재 배치 실행 직후 | `--reload-existing` 없는 `initial-load`가 아무 일도 안 하고 성공으로 끝나는 조합을 알림 |
| `remind-progress.sh` | 세션 종료 | 소스가 바뀌었는데 진행 상태가 그대로면 갱신을 상기 |

`remind-adr.sh`의 두 번째 방향이 생기기 전까지 20개 ADR의 `applies_to`는 **아무도 읽지 않는 필드**였다. 문서 두 곳이 "자동 로드된다"고 적고 있었지만 그것을 구현한 코드가 없었다. 지금은 편집한 경로를 frontmatter와 매칭해 **본문이 아니라 포인터**를 낸다 — 본문 주입은 편집당 수천 자가 되어 아무것도 안 하는 것보다 나쁘다.

`warn-bootstrap-args.sh`만 트리거가 파일이 아니라 **argv의 모양**이다. 적재는 어떤 파일도 열지 않는 Bash 작업이라 `paths` glob으로는 원리적으로 표현할 수 없고, 그래서 이 함정만 hook에 있다. 나머지 절차는 `batch-load` skill에 있다.

알림 계층을 차단이 아닌 상기로 둔 이유는, 기록 여부와 시점의 판단까지 에이전트에게 남기기 위해서다. `PostToolUse`는 구조적으로 차단이 불가능하므로 무한 루프 위험도 없다. `SessionEnd` 역시 문서상 차단을 지원하지 않는다.

## 세션 간 핸드오프

phase마다 세션이 나뉘면 새 세션은 이전 세션이 무엇을 했는지 모른 채 시작한다. Claude Code에는 이를 위한 네이티브 수단이 없다 — 플랜 모드 산출물은 대화 안에만 존재하고 승인해도 저장소에 남지 않으며, `--resume`은 *같은* 세션 재개일 뿐이다. [claude-code#11455](https://github.com/anthropics/claude-code/issues/11455)가 이 기능을 요청 중이나 미해결이다.

그래서 직접 만든다. 구조는 **수명으로 나누고, 변하는 것만 항상 주입한다**는 것이다.

- **`docs/progress.md`가 진행 상태의 단일 지점이다.** `SessionStart` 훅이 이를 주입하므로 새 세션은 첫 프롬프트 이전에 이미 알고 있다. 저장소에 파일을 두는 것만으로는 부족하다 — 새 세션은 자신이 무엇을 모르는지조차 모르므로 "알아서 찾아 읽겠지"는 성립하지 않는다.
- **ADR 대상이 아닌 결정도 여기 남긴다.** 패키지 구조나 조건 모델의 형태처럼 되돌리기는 쉽지만 모르면 다시 만들게 되는 판단이다. ADR 규약은 이런 것을 의도적으로 제외하는데(사소한 것까지 남기면 중요한 결정이 묻힌다), 그렇다고 아무 데도 안 남기면 **다음 세션이 가장 알아야 할 것이 가장 안 남는다.**
- **주장은 반증 가능한 형태로 쓴다.** "Phase 1 완료"가 아니라 "완료 판정: `./gradlew build` 성공 + ADR 존재"처럼 쓴다. 사람은 낡은 문서를 보면 낡았다고 의심하지만 에이전트는 그대로 믿는다. `check-doc-staleness.sh`가 이 주장을 실제 코드와 대조한다.
- **쓰기 측이 있어야 한다.** 갱신되지 않는 진행 상태는 다음 세션을 돕는 대신 잘못 인도한다. `remind-progress.sh`가 소스만 바뀌고 진행 상태가 그대로인 채 세션이 끝나는 것을 상기시킨다.

설계 문서(`docs/design/`)를 저장소에 두는 이유도 같다. 플랜 모드 산출물은 `~/.claude/plans/`에 쌓이는데 그곳은 git 밖이고 자동 로드되지 않아, 다음 세션이 찾을 수 없다. 저장소에 있어야 grep되고, 코드와 함께 diff되고, 틀렸으면 revert된다.

이 구조는 spec-kit·Kiro·task-master 같은 도구들이 독립적으로 수렴한 형태와 같다 — 오래 가는 것은 좁게 조건부로 로드하고, 변하는 상태만 항상 주입한다.

이 계층이 필요한 이유는 산문 지시가 시간이 지나면 희석되기 때문이다. 앞서 인용한 준수율 감쇠(스텝당 약 5.6%)는 세션 후반일수록 심해지는데, 아키텍처 결정은 대개 세션 후반에 내려진다. "ADR을 기록하라"를 `CLAUDE.md`에만 두면 가장 필요한 순간에 가장 약해진다.

모든 스크립트는 매칭되지 않으면 조용히 통과시킨다. hook이 워크플로우를 막지 않는다는 것이 설계 원칙이다. `jq`가 없는 환경에서도 차단 대신 통과를 선택한다.

## 지시문 작성 스타일

`CLAUDE.md`와 `.claude/rules/`, ADR의 규칙 문장은 `MUST:` / `MUST NOT:` 접두어를 문장 앞에 붙여 강도를 명시한다. 모호한 산문 대신 검증 가능한 단정을 주면 에이전트가 요약하지 않고 문자 그대로 따른다.

- 접두어는 문장 **앞**에만 붙인다. 문장 중간에 부사처럼 삽입하면 한국어 문장이 성립하지 않는다.
- 반드시 지켜야 하는 것에만 붙인다. 판단 여지가 있는 권고는 접두어 없이 평문으로 둔다. 전부 MUST로 도배하면 강조가 무의미해진다.
- 규칙에는 **이유를 함께 남긴다.** 이유 없는 명령만 나열하면 에이전트가 예외 상황에서 판단할 근거를 잃는다.
