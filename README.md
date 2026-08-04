# stock-screener

코스피·코스닥·S&P500 우량 종목을 기술적·재무적 조건으로 필터링·추천하는 개인용 웹 스크리너. 본인과 소수 지인이 사용하며, 실용성과 함께 한국 백엔드 채용시장 기준의 개인 학습을 겸한다.

전체 요구사항, 채택 기술 스택과 그 배경, 수용 기준은 [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md)에 정리되어 있다. 이 문서는 기획 정본이며 함부로 수정하지 않는다.

## 기술 스택

Spring Boot(백엔드) + Vue3(프론트엔드), JPA + QueryDSL, PostgreSQL, PrimeVue DataTable. 온프레미스(맥북) 호스팅 + Cloudflare Tunnel, Google OAuth, Sentry. 각 선택의 배경은 기획 정본의 채택 기술 스택 절에 기록되어 있다.

현재 저장소에는 Spring Boot 골격만 있다. 위 스택의 의존성은 아직 `build.gradle`에 반영되지 않았다.

## 문서 지도

| 문서 | 성격 | 대상 독자 |
|---|---|---|
| [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md) | 기획 정본 — 요구사항·수용 기준·범위 | 사람 + 에이전트 |
| [`CLAUDE.md`](CLAUDE.md) | 에이전트 지시문 — 명령어·설계 제약·금지 사항 | 에이전트 전용 |
| [`.claude/rules/`](.claude/rules/) | 영역별(백엔드/프론트/데이터) 판단 기준 | 에이전트 전용 |
| [`.claude/hooks/`](.claude/hooks/) | 결정론적 계층 — 차단(안전망) + 알림(문서·ADR 유지) | 사람 |
| [`docs/adr/`](docs/adr/) | spec 확정 이후의 새 기술 결정 기록 | 사람 + 에이전트 |

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
| `remind-adr.sh` | 의존성·인프라·설정 파일 편집 직후 | ADR 기록 대상인지 판단하라고 상기 |
| `check-doc-staleness.sh` | 세션 시작 | 문서의 주장과 실제 코드 상태의 불일치를 보고 |

알림 계층을 차단이 아닌 상기로 둔 이유는, 기록 여부와 시점의 판단까지 에이전트에게 남기기 위해서다. `PostToolUse`는 구조적으로 차단이 불가능하므로 무한 루프 위험도 없다.

이 계층이 필요한 이유는 산문 지시가 시간이 지나면 희석되기 때문이다. 앞서 인용한 준수율 감쇠(스텝당 약 5.6%)는 세션 후반일수록 심해지는데, 아키텍처 결정은 대개 세션 후반에 내려진다. "ADR을 기록하라"를 `CLAUDE.md`에만 두면 가장 필요한 순간에 가장 약해진다.

모든 스크립트는 매칭되지 않으면 조용히 통과시킨다. hook이 워크플로우를 막지 않는다는 것이 설계 원칙이다. `jq`가 없는 환경에서도 차단 대신 통과를 선택한다.

## 지시문 작성 스타일

`CLAUDE.md`와 `.claude/rules/`, ADR의 규칙 문장은 `MUST:` / `MUST NOT:` 접두어를 문장 앞에 붙여 강도를 명시한다. 모호한 산문 대신 검증 가능한 단정을 주면 에이전트가 요약하지 않고 문자 그대로 따른다.

- 접두어는 문장 **앞**에만 붙인다. 문장 중간에 부사처럼 삽입하면 한국어 문장이 성립하지 않는다.
- 반드시 지켜야 하는 것에만 붙인다. 판단 여지가 있는 권고는 접두어 없이 평문으로 둔다. 전부 MUST로 도배하면 강조가 무의미해진다.
- 규칙에는 **이유를 함께 남긴다.** 이유 없는 명령만 나열하면 에이전트가 예외 상황에서 판단할 근거를 잃는다.
