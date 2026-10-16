# 하네스 범용 파일묶음 export 계획 (v3.1 — 2차 반증 반영 · S0 결정 확정)

기준 설계: `stock-screener/docs/harness-research/08-협의-도서관-하네스-설명.md` (87d0f0bd)
누적 목록: `~/dev/harness-kit/review/누적-수정사항과-결함.md`

## v1 반증 결과 (2026-10-16, 두 관점)
- 계획 밖 렌즈: **다시 짠다** — 골격은 유지 가능하나, 계약이 동결할 것들(답변 결속·「해당 없음」 근거·브랜치 모델·실패 방향·종료 상태·버전과 이행·이관)이 열려 있거나 틀림. 결정 목록 누락 다수.
- 계획 안 렌즈: **보완 후 진행** — 추적 모집단 오류, 손으로 쓴 픽스처, 대조군 없는 헤드리스 시험, macOS cp949 재현의 한계, 「덮음」이 이름뿐인 항목.
- 두 렌즈가 독립적으로 같은 것을 지적: ① AskUserQuestion 경로 미검증 ② 「main에서만」과 개발 중 빈칸(I9)·PR 흐름의 모순 ③ 계약 전에 재야 할 미지수 ④ 추적 모집단 ⑤ cp949 시험 한계.
- 네 오류 형태가 모두 발생했다: 묶어서 판정(C9~C14와 응답 형식을 한 사유로 연기) · 소비자를 안 셈(「답」의 뜻, 「main에서만」, `state.json`) · 커버리지를 안 보고 정본을 고름(없는 E 항목, 08만 대조 기준) · 폴백 근거의 이식(헤드리스 제약을 제품 계약으로, UserPromptSubmit용 종료코드 규약을 관문 hook에).

## v3 반증 결과 (2026-10-16, 두 관점 모두 「보완 후 진행」) — 반영 내역
| 지적 | 반영 |
|---|---|
| [뼈대] 진행 중 intake가 `kn/*` 브랜치·별도 worktree에만 있어 기능 브랜치 세션의 「[작성 중]」 신호·SessionStart 이어하기·Stop 판정·`미결: INT-…` 검사가 그 intake를 못 찾음 | WP0에 **intake 위치 정본** 추가: hook·CI는 `origin/main` + `origin/kn/*`의 합을 읽는다(SessionStart가 시간 상한을 두고 `kn/*`를 fetch해 캐시, 목록 hook은 캐시만 읽음). 엔진은 세션 → intake 매핑을 `--git-common-dir`에 둔다. 스파이크 P8로 실측 |
| [뼈대] 검증된 초안 해시 결속이 rebase·리뷰 반영·사람의 충돌 해결을 모두 CI 실패로 만듦 | 계약에 `complete → draft` 재개 전이와 재검증 규칙 추가. WP4 시나리오에 「rebase·리뷰 반영 뒤 재검증」 추가 |
| D1이 「main에서만 기록」을 바꿨는데 08·A절·H4·WP2 시험·CLAUDE.md 템플릿이 옛 뜻 그대로 | WP0에서 소비자 전수 grep → 관문을 「쓰기 **대상 경로** 기준 `git -C <dir>` 브랜치가 `kn/*`이면 허용, main·기능 브랜치는 거부」로 재정의. 08과 누적 목록 갱신(추적표 「해소(대체됨)」) |
| D7 제외의 대가를 한 문장으로 묶음 | 추적 상태에 **「구현·팀 CI 연결 대기」** 추가. README에 보증표 행마다 「연결 안 함 / 연결함」 보증 수준. pre-commit은 `core.hooksPath` + 표준 라이브러리로(프레임워크 의존 없음). CODEOWNERS·PR 템플릿은 「예시(호스트별 형식 다름)」로 표기 |
| 스파이크에 대조군 없음, UserPromptSubmit·압축/재개 SessionStart 채집 누락, P4와 업그레이드 순서 충돌, P7은 미지수를 안 잼 | 스파이크 표에 대조 칸 추가, P2 범위 확대, **P4는 업그레이드 전**, P8(worktree × hook) 추가 |
| C9 「확정」 과장, D10과 시험 shim 불일치 | C9·C10·3,000장 30초 예산은 「Windows 실기 대기」. OS별 settings 출하 방식을 WP0에서 정함(`py -3` / `python3`) |
| WP4가 질문 도구 고장에도 통과 | 핵심 시나리오는 tmux 대화형으로, 판정은 hook 로그로 |
| WP6에 깨끗한 PR 음성 대조 없음 | 정상 PR 음성 사례 추가, 오탐 0을 완료 기준에 |
| 버전이 다른 브랜치·worktree가 옛 hook으로 돎 | 계약에 「hook 판 < state의 `engine_version`이면 쓰기 거부 + main 반영 안내」 |
| git 비숙련 팀의 일상 마찰 | 엔진에 `kn start / finish / clean` 명령(사람이 칠 명령을 하나로). WP7 e2e에 「README만 보고 따라 하기」 + 명령 수 측정 |
| `repair`·`park`를 AI도 실행 가능 | PreToolUse가 AI의 해당 명령 실행을 거부 |
| 팀 PC 전제를 너무 늦게 확인 | S0에 질문 1개 추가(아래 Q) |
| D3에 맞춰 「해당 없음 + 사유」 문구 수정 필요 | WP0 정합화에서 08·I6 수정 |

## 실측한 전제 (2026-10-16)
- 이 기계 Claude Code 2.1.292 (`onFailure: "block"`은 2.1.295+) · Python 3.13.5 · `python` 명령 없음(`python3`만) · PyYAML 없음.
- 헤드리스(`claude -p`)에는 AskUserQuestion이 없다. **이것은 시험 환경의 제약이지 제품 계약이 아니다** — 대화형 경로는 스파이크에서 따로 잰다.

## 단계 개요

```
S0 결정 수집 ─▶ S1 스파이크(실측) ─▶ WP0 계약 ─▶ (반증 1회) ─▶ WP1 코어 ─▶ WP2 hook ─▶ WP3 재료 ─▶ WP4 절차·검증자 ─▶ WP5 문서 ─▶ WP6 커밋·CI ─▶ WP7 통합·포장 ─▶ (반증 1회)
```
- 사용자 확인: S0(완료) · WP0 승인 · WP3(점검표 도메인 검토) · WP7.
- 반증은 계약 확정 직전과 포장 직전 두 번만 받는다. 중간 지적은 내가 재서 닫는다.

## S0 — 결정 (2026-10-16 사용자: 「권장안대로, 7번은 필요없어」)

| # | 확정 내용 |
|---|---|
| D1 | 도서관 쓰기 브랜치: 최신 origin/main에서 딴 `kn/*` 브랜치에서만 `knowledge/` 쓰기 → 작은 PR → 병합. 개발 중 빈칸은 main 기준 별도 worktree에서 intake. 근거 ID 검사는 origin/main 기준 |
| D2 | 질의응답 답 결속: AskUserQuestion이 주 경로. 일반 대화 답은 hook이 질문 ID를 결정적으로 짝지을 수 있을 때만 인정(형식은 S1-P1 결과로 확정) |
| D3 | 「해당 없음」도 사람 확인 필수(사람 턴 원문 ID) |
| D4 | 진행 중 intake는 `kn/*` 브랜치로 push(승인 대상 아님), `owner` 1인, 다른 사람 intake는 읽기만 |
| D5 | `withdrawn` 종료 단계 추가(사람 확인 필수). 기각 기록·단순 변경은 해당 점검 항목만 요구 |
| D6 | 기존 문서는 「이관 intake」(원 날짜·상대 보존)로 받고 관문은 동일. 실제 문서 2~3건으로 질문 수·시간 측정 |
| ~~D7~~ | **제외(사용자).** 내 해석: Git 호스트·CI 기능 조사는 하지 않는다. 그래서 CI 층은 **호스트 중립 스크립트**(`check`·근거 ID 검사·PR 검증자 실행 명령)로만 만들고, 특정 호스트 워크플로·branch protection 설정 절차는 묶음에 넣지 않는다. **대가:** 「서버 측 최종 방어」와 「PR 검증자」는 팀이 자기 CI에 스크립트를 연결해야 성립한다 — README에 연결 지점만 적는다 |
| D8 | 배포: 프로젝트 settings 복사 + 코어/팀 설정 파일 분리 + `bundle_version`·`schema_version`·이행 규칙. plugin 방식은 S1에서 UserPromptSubmit 실패 처리 차이만 비교해 기록 |
| D9 | Python 표준 라이브러리만 + 엄격한 frontmatter 부분 문법(문법 밖이면 크게 실패) |
| D10 | Windows는 `py -3` 런처 + `-c` 부트스트랩(스크립트 없으면 exit 1). Mac·Linux는 `python3` |
| D11 | Claude Code 최소 2.1.299. 이 기계를 업그레이드해 시험(S1 착수 시 실행) |
| D12 | 작업 원본 `~/dev/harness-kit/bundle/`(로컬 git). 계획·진행 요약은 stock-screener `docs/harness-research/`에 올려 원격 확인 |
| D13 | 범용 묶음 + `examples/wes/`(점검표·키워드 예시) 분리 |
| **Q** | **(답 대기) 팀 PC 전제:** Windows 버전, Python 설치 여부·방식(python.org 설치본 / Store / 사내 배포), Git for Windows 설치 여부, 관리자 권한 유무. WP1 전체가 기대는 전제라 S1 전에 확인 |

## S1 — 스파이크 (계약을 쓰기 전에 재는 것, 작은 샌드박스만)

| # | 재는 것 | 방법 | 계약에 주는 것 |
|---|---|---|---|
| P1 | AskUserQuestion의 실제 hook 입력(`PostToolUse` stdin), 대화상자 답에 UserPromptSubmit이 뜨는지, 「Other」 자유 입력 형태 | 이 Mac에서 tmux로 대화형 세션 1회 | D2 결속 규칙, 원문 저장 형식 |
| P2 | 이벤트별 실제 stdin(SessionStart·SubagentStart·SubagentStop·Stop·PreToolUse·PostToolUse) | stdin 덤프 hook을 걸고 헤드리스 1회 → **실 payload를 픽스처로** | 모든 hook 계약 시험의 입력 |
| P3 | deny·block·`systemMessage` 출력을 Claude Code가 실제로 따르는지 | 공용 출력 함수 + 실행 1회 | 출력 형식 |
| P4 | `onFailure` 키를 넣은 settings가 2.1.292에서 무시되는지, settings 전체가 거부되는지 | 실행 1회 | 하위 호환·최소 버전 |
| P5 | 10,000자 상한의 단위(원시 stdout인가 디코드 후인가) — ASCII 이스케이프 시 한글 약 6배 팽창 | 한글 약 9,000자 목록 + 끝 canary, 실행 1회 | 7,000/9,000 임계의 단위, 출력 인코딩 방식 |
| P6 | exec form `args`에서 `${CLAUDE_PROJECT_DIR}` 확장, 스크립트 부재 시 프롬프트가 막히지 않는지(`-c` 부트스트랩) | 실행 2회 | C3·C9 대응 확정 |
| P7 | D1 브랜치 모델 한 바퀴: `kn/*` 브랜치 → 리뷰 반영 → 병합 → 각자 pull | 로컬 bare 저장소 2개 클론으로 연습 | 브랜치 규칙·CI 기준 ref |
| P8 | **worktree × hook**: 기능 worktree 세션에서 `kn` worktree에 쓰기·엔진 실행·Stop 판정, intake 위치 캐시 | 실행 시험. 대조군: 같은 조작을 `kn` worktree 세션에서 | intake 위치 정본, 쓰기 관문의 브랜치 판정 기준 |

**대조군과 판정 수단 (모든 스파이크 공통):** P1은 같은 세션에서 직접 타이핑한 프롬프트로 UserPromptSubmit이 뜨는지 대조. P2는 UserPromptSubmit과 SessionStart의 압축·재개 변형까지 채집하고, 헤드리스에서 채집한 payload와 대화형에서 채집한 payload를 구분해 표시. P3·P5는 hook 로그와 트랜스크립트로 판정(모델의 자기 보고는 쓰지 않음). **P4는 Claude Code 업그레이드 전에 2.1.292에서 먼저 잰다.**

스파이크 산출물은 `spike/` 결과 기록만 남기고, 샌드박스는 판정 후 정리한다.

## WP0 — 계약 (`CONTRACT.md`)
반드시 정할 것 (반증 지적 반영):
- 디렉토리·ID·frontmatter 엄격 문법(키워드 파일에 `aliases`·`replaced_by` 포함) · 키워드 → 범주 → 점검표 매핑 · design skill frontmatter
- `state.json` 스키마: `schema_version`·`engine_version`·`checklist_sha`, `keywords`·`title`, `owner`·`last_session`·`awaiting_human`, 단계(… `complete`·`parked`·`withdrawn`), 항목(`answered`·`na` 모두 원문 ID 필수)
- **초안의 정의**(대상 경로 → 전체 내용 집합)와 쓰기 결속(PreToolUse가 적용 후 내용을 계산해 검증된 초안 해시와 대조, CI도 대조)
- 원문 쓰기는 hook만 · AI의 `sources/` 쓰기(새 파일 포함, Bash·PowerShell 포함) 전부 거부 · SRC ↔ 사람 턴 해시 연결
- **실패 방향 행렬**: 주입 hook은 실패 시 열고 표식을 남김 · 관문 hook은 모든 예외를 잡아 사유 붙은 명시적 deny로 닫음 · hook별 `onFailure` 표 · 사람만 쓰는 `repair`·`park` 명령(기록 남음)
- Stop은 이 세션이 다룬 intake만 판정 · 목록 저장소는 `session_id` 키(C2)
- 엔진 CLI(`next`·`report`·`check`·`repair`)의 directive·report 스키마 · 엔진 실행 permissions 규칙
- 인코딩·줄바꿈: 읽기 `utf-8-sig`, 쓰기 `utf-8`(BOM 없음)·`\n` · 출력 바이트 전부 ASCII · 저장 파일 BOM 0 검사
- 저장소 루트 결정(`git rev-parse --show-toplevel`), 「knowledge 못 찾음」은 0건과 구분해 크게 알림
- 기능별 최저 버전 표 · OS별 인터프리터 해석 규칙
- 코어 파일 / 팀 설정 파일(임계값·문구·점검표·키워드) 분리 · 업그레이드·이행 규칙
- 근거 주석 형식은 개발 요청 hook 머리말과 SubagentStart 주입으로도 전달(`rules/code.md`는 Windows `paths:` 결함 때문에 보조) · `미결: INT-…`의 INT가 완성·철회되면 CI 경고
- 「해당 없음」, 이관 intake, 철회의 처리 규칙

검증: ① 08 대조표 ② **08 내부 모순 점검**(예: 변경 판단 기준 위치 「CLAUDE.md」 vs 「skill」 — 발견된 모순은 사용자 결정으로 올림) ③ 시나리오 따라가기(기능 브랜치에서 개발 중 빈칸 · PR 리뷰 반영 · 두 팀원이 같은 키워드 · Windows 실행) ④ 추적표.
완료: 모순 0(또는 사용자 결정으로 해소) · 추적표 전 항목에 산출물·시험 배정. → **반증 1회** → 사용자 승인.

## 추적 모집단 (v1의 「59건」 정정)
- 정의 없는 E1~E4를 뺀다. **누적 A절 사용자 결정 17건 + B~I 55건 = 72건(2026-10-16 실측 — 반증자가 센 「27」은 행 수 착오)**을 센다.
- 상태는 다섯: **구현·검증** / **구현·Windows 실기 대기**(뒤에 돌릴 시험 이름 명시, Windows로만 잴 수 있는 것에만 허용) / **구현·팀 CI 연결 대기**(D7 제외로 팀이 CI에 연결해야 성립하는 것 — README 연결 지점과 1:1) / **미구현(사유)** / **해소(대체됨)**.
- S0 결정(D1~D13)과 v1·v3 반증 지적에도 추적 ID를 붙여 같은 표에서 닫힘을 확인한다.

## 구현 묶음 (WP1~WP7)

| 묶음 | 산출물 | 검증 (반증 반영) | 완료 판정 |
|---|---|---|---|
| **WP1 코어** | `kwlib`(엄격 파서·어휘·매칭·정렬·계산 상태·세션 키 목록 저장소), `intake-engine`(next/report/check/repair, 원자적 쓰기·잠금), `scripts/kw-check.py`(같은 코어 호출) | 단위 시험 + **`-X warn_default_encoding -W error::EncodingWarning`**(인코딩 없는 `open()`을 OS와 무관하게 잡음) + 정적 검사(인코딩 인자 없는 `open`·`read_text` 금지) + 출력 바이트 ASCII 단언 + CRLF·BOM·블록 목록·frontmatter 없음·하위 디렉토리 cwd·손상된 `state.json`·대체 순환 픽스처. `-X utf8` 유무를 따로 시험(방어 두 겹을 각각) | 전 시험 통과, 일부러 틀린 픽스처 전부 실패 |
| **WP2 hook·설정** | hook 스크립트, `settings.json`(exec form + 부트스트랩) | ① 계약 시험: **S1에서 채집한 실 payload**로만 ② 실행 시험은 **대조 짝**과 함께, 판정은 모델 답이 아니라 **hook 로그·트랜스크립트**로: 목록 주입(canary) · `topics/` 쓰기 강제 시도 → deny 로그 + Bash 경로 시도 후 파일 부재 · Stop: AI 몫 남음 → block 로그와 턴 지속 / 사람 답 대기 → 허용 · `state.json` 직접 편집 거부 · `sources/` 새 파일 쓰기 거부 · main 외 쓰기 거부 · 압축 후 재주입 · 세션 재개 알림 · 두 세션 엇갈림(C2) · 스크립트 부재 시 프롬프트 통과(C3) · **출하본 settings를 바이트 그대로**(PATH에 `python` shim만) · 3,000장 콜드스타트 시간(30초 예산) | 각 거부·관문 행마다 대조 짝 포함 실행 1건 이상 통과 |
| **WP3 지식 재료** | `knowledge/` 틀, 템플릿, 키워드 파일(+`aliases`), 점검표 형식, `examples/wes/`(설비 연계·CDC 점검표, 키워드 예시) | `kw-check` 통과 · 점검표가 08 예시 질의응답을 재현하는지 대조 | 검사 통과 + **사용자 도메인 검토** |
| **WP4 절차·검증자** | `record-knowledge` skill + references, 엔진 `kn start/finish/clean` 명령, intake 검증자 subagent(「원문에 없는 내용」+「해당 없음」 정당성 점검), PR 검증 프롬프트, `rules/code.md`, `design-*` skill 틀 + 쓰기 관문 | 다중 턴 시나리오 **9건**(핵심은 tmux 대화형, 판정은 hook 로그): 중량 검사(신규) · 기존 문서 충돌 · 끊고 재개 · 단순 변경 · 결정 변경 · 기각 · 설계 정본 루프 · 개발 중 빈칸 → INT(기능 worktree에서) · rebase·리뷰 반영 뒤 재검증. **부정 시나리오 2건**: 답이 아닌 답(「나중에」) → 항목 open 유지 / 다른 질문에 대한 답 → 재질문. 각본은 질문 내용에 맞춰 답함. SubagentStop 판정 기록·H6 실행 시험은 여기서 | 상태 전이가 계약표대로, 반영 문서의 `[추론]` 0 · 원문 ID 100%, 부정 시나리오에서 관문 미통과 |
| **WP5 지시문·문서** | `CLAUDE.md` 템플릿, 사용자 가이드(입력·질의응답 기대치·「상태 줄이 안 보이면 하네스 꺼짐」), 관리자 가이드(어휘·점검표·업그레이드), 온보딩 체크리스트 | 줄·자수 상한 · hook 머리말 문자열과 중복 자동 검사 · 문서 속 경로·명령 실존 검사 | 중복 0 · 깨진 참조 0 |
| **WP6 커밋·CI** | pre-commit 설정, **호스트 중립 CI 스크립트**(`check` 전체 실행 · 근거 ID 검사(origin/main 기준) · PR 검증자 실행 명령), `.gitattributes`, CODEOWNERS·PR 템플릿, README의 「CI 연결 지점」 절 | 픽스처 저장소에서 통과·실패 각각 · 의도한 실패: 도서관+코드 혼합 PR · 원문 수정 · 바뀜 문서 인용 · 완성·철회된 INT 인용 · 중복 원문(B9) · 원문 ID 없는 규칙 줄(G2) · **표시 없는 결정**(PR 검증자 섀도 시험 2~4건) + **정상 PR 음성 사례** | 의도한 실패 전부 잡힘 · 정상 PR 오탐 0 |
| **WP7 통합·포장** | README(설치·구성·한계·Windows 미검증 목록), **Windows 수용 시험 키트**(hook 계약 시험 + 자가진단 명령, 온보딩 첫 관문), `MANIFEST`·SHA256, zip | 빈 저장소에 README만 보고 설치 → 입력 1건 → 개발 요청 1건 e2e(사람이 친 명령 수 측정) · **반증 1회** · zip 해제 후 목록·해시 대조 | 추적표 72건(이후 결정 추가분 포함) 상태 확정, 반증 지적 처리 |

순서 의존: WP3의 형식(키워드 → 범주 → 점검표 매핑)과 검증자 출력 형식은 WP0에서 고정한다. 그래서 WP2·WP4가 상상한 입력으로 시험하지 않는다.

## 비범위
- Windows 실기 시험 — 대신 Windows 수용 시험 키트를 묶음에 넣어, 실제 시험을 Windows 기계에서 첫 온보딩 관문으로 돌리게 한다(대체가 아니라 시험 장소를 옮기는 것).
- `design-*`·`equip-*` skill의 실제 내용.
