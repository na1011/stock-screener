# stock-screener

코스피·코스닥·S&P500 우량 종목을 기술적·재무적 조건으로 필터링·추천하는 개인용 웹 스크리너. 본인과 소수 지인이 사용하며, 실용성과 함께 한국 백엔드 채용시장 기준의 개인 학습을 겸한다.

전체 요구사항, 채택 기술 스택과 그 배경, 수용 기준은 [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md)에 정리되어 있다. 이 문서는 기획 정본이며 함부로 수정하지 않는다.

## 기술 스택

Spring Boot(백엔드) + Vue3(프론트엔드), JPA + QueryDSL, PostgreSQL, PrimeVue DataTable. 온프레미스(맥북) 호스팅 + Cloudflare Tunnel, Google OAuth, Sentry. 각 선택의 배경은 spec 6장 참고.

## 문서 지도

| 문서 | 성격 | 대상 독자 |
|---|---|---|
| [`docs/stock-screener-project-spec.md`](docs/stock-screener-project-spec.md) | 기획 정본 — 요구사항·수용 기준·범위 | 사람 + 에이전트 |
| [`CLAUDE.md`](CLAUDE.md) | 에이전트 지시문 — 명령어·설계 제약·금지 사항 | 에이전트 전용 |
| [`.claude/rules/`](.claude/rules/) | 영역별(백엔드/프론트/데이터) 에이전트 지시문 | 에이전트 전용 |
| [`docs/adr/`](docs/adr/) | spec 확정 이후의 새 기술 결정 기록 | 사람 + 에이전트 |

## 하네스 설계 — 기획형 하네스

이 저장소의 `CLAUDE.md`와 `.claude/rules/`는 **기획형 하네스**로 설계했다. 아키텍처, 패키지 구조, 레이어 분리, 구현 순서 등 "어떻게 만들 것인가"는 하네스에 규정하지 않는다. 작업 시점의 에이전트가 그때그때 판단하도록 맡긴다. 하네스가 고정하는 것은 "무엇을 향해 가는가" — 판단 기준, 범위의 경계, 반드시 지켜야 할 제약뿐이다.

이 결정은 아래 조사에 근거한다.

- **구조를 정교화해도 준수율은 오르지 않는다.** [Instruction Adherence in Coding Agent Configuration Files](https://arxiv.org/abs/2605.10039) (1,650 Claude Code 세션, 16,050 관측 대상 factorial study)는 CLAUDE.md의 크기·지시 위치·파일 구조·모순 네 변수 모두 준수율에 유의한 효과가 없음을 보였다(베이즈 팩터로 귀무 지지). 반면 세션 내 준수율은 생성 함수 1개당 약 5.6%씩 자연 감쇠했다. 즉 반드시 지켜야 할 것은 산문을 아무리 다듬어도 확보되지 않으며, 결정론적 계층(hook)에 둬야 한다.
- **최소 고신호 토큰이 최적이다.** Anthropic의 [Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)는 "right altitude" 개념을 제시한다 — 과잉 명세는 LLM을 룰 엔진처럼 취급해 취약하게 만들고, 과소 명세는 신호를 주지 못한다. 구현 방식을 못박는 대신 강한 휴리스틱만 준다.
- **신뢰 프레이밍이 공포·금지 나열보다 낫다.** [Trust Over Fear: Motivation Framing in System Prompts](https://arxiv.org/abs/2603.14373)는 신뢰 프레이밍이 숨은 이슈 발견 +59%, 탐색 스텝 +83%를 이끌어낸 반면 공포/위협 프레이밍은 모든 지표에서 유의한 개선이 없었음을 보였다. 금지 목록은 정말 위험한 것만 남긴다.
- **자율성은 선언이 아니라 규칙의 부재로 구현한다.** CLAUDE.md에 "너는 자율적이다"라고 적는 것은 에이전트가 읽고 취할 행동이 없는 메타 문장이다. 아키텍처 규칙을 애초에 쓰지 않으면 에이전트는 이미 자율적이다. 그래서 `CLAUDE.md`에는 자율성 선언이 없다 — 대신 명령어, 검증 가능한 설계 제약, 최소한의 금지 사항만 있다.
- **되돌리기 어려운 결정은 자율성의 대가로 기록한다.** 아키텍처 자율을 주는 대신, 새 의존성·저장 구조·외부 연동처럼 되돌리기 어려운 판단은 `docs/adr/`에 남긴다. 허가를 구하지 않고 결정한 뒤 기록하는 방식이다.
- **CLAUDE.md 자체는 [공식 작성 가이드](https://code.claude.com/docs/en/best-practices)를 따른다.** 짧게, 명령형(MUST/NEVER/IMPORTANT)으로, 매 세션 재설명이 필요한 사실(빌드 명령·설계 제약)만 담는다. 각 줄에 "이걸 지우면 에이전트가 실수하는가?"를 적용해 아니면 잘라낸다.

phase 분할·구체적 아키텍처 설계는 이 하네스의 범위가 아니며, 별도의 설계 세션에서 다룬다.
