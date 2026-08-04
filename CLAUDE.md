# 프로젝트 개요

- 코스피·코스닥·S&P500 종목 스크리너 프로젝트. 상세 기획서는 `docs/stock-screener-project-spec.md` 참고.
- Spring Boot 4.1 / Java 21 / Gradle / Vue3.

## 명령어

- 빌드: `./gradlew build`
- 테스트: `./gradlew test`
- 단일 테스트: `./gradlew test --tests "패턴"`
- 실행: `./gradlew bootRun`

## ADR

되돌리기 어려운 결정(새 의존성, 저장 구조, 외부 연동, 인프라 구성)은 `docs/adr/`에 `0000-template.md` 형식으로 기록한다. 허가를 구하지 말고 결정하고 기록하라. 클래스 분리·메서드 시그니처는 기록 대상이 아니다.

## 금지

- IMPORTANT: 시크릿·API 키를 코드·커밋·로그에 포함하지 않는다. `.env.example`에 플레이스홀더로만.
- `docs/stock-screener-project-spec.md`를 수정하지 않는다. 변경이 필요하면 제안하라.
- 확인 없이 파일·디렉토리를 삭제하지 않는다.
- force push를 실행하지 않는다.
