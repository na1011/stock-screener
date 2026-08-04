---
id: ADR-0001
status: 채택됨
date: 2026-08-05
applies_to:
  - "build.gradle"
---

# ADR-0001. QueryDSL은 OpenFeign 포크를 쓴다

## 결정

`io.github.openfeign.querydsl` 7.5를 쓰고, mainline `com.querydsl`은 build에서 group 전체를 차단한다.

## 규칙

- MUST: `io.github.openfeign.querydsl:querydsl-jpa:7.5`를 쓴다. classifier를 **붙이지 않는다** (`:jakarta`는 404다).
- MUST: `annotationProcessor`에는 `io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta`를 쓴다. 여기는 classifier를 **붙인다**. 7.x에서 규칙이 역전되었으므로 Boot 3 예제를 복사하면 실패한다.
- MUST: `configurations.all { exclude group: 'com.querydsl' }`를 유지한다.
- MUST NOT: mainline `com.querydsl`로 되돌리지 않는다. "표준처럼 보인다"는 이유로 바꾸면 런타임에 깨진다.
- MUST: 프로브 테스트(`QuerydslProbeTest`)를 지우지 않는다. 이 회귀를 잡는 유일한 수단이다.

## 검증

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -i querydsl
```
`io.github.openfeign.querydsl` 7.5만 나오고 `com.querydsl`이 없어야 한다.

```bash
./gradlew test --tests "*QuerydslProbe*"
```
통과해야 한다. 이 테스트는 생성된 Q클래스로 실제 쿼리를 **실행**한다.

## 배경

IMPORTANT — mainline은 **컴파일·Q클래스 생성·컨텍스트 기동을 모두 통과하고 쿼리를 실행하는 순간에만** 깨진다.

mainline 최신은 5.1.0(2024-01, 이후 릴리스 없음)이고 `AbstractHibernateQuery`가 `org.hibernate.query.Query.setFlushMode(org.hibernate.FlushMode)`를 호출한다. Hibernate 7.4(Spring Boot 4.1 baseline)에서 이 오버로드가 제거되어 `NoSuchMethodError`가 난다.

함정이 깊은 이유는 Boot 4.1의 의존성 관리가 여전히 `querydsl.version=5.1.0`을 고정하고 있어서, `com.querydsl:querydsl-jpa`를 아무 마찰 없이 resolve할 수 있기 때문이다. **올바른 선택이 오히려 비표준으로 보인다.** 이 기록이 없으면 다음 세션이 "왜 포크를 쓰지?" 하며 되돌린다.

OpenFeign 포크 7.5(2026-07-21)의 POM은 `springboot.version=4.1.0` / `hibernate.version=7.4.5.Final`을 명시해 이 스택을 정면으로 겨냥한다.

## 버린 대안

- **mainline `com.querydsl` 5.1.0** — 위 이유로 런타임에 깨진다. Maven Central 메타데이터와 바이트코드로 확인했다.
- **jOOQ로 전환** — spec §6이 한국 백엔드 채용시장 표준(JPA+QueryDSL)을 근거로 이미 배제했다. 기획 결정을 뒤집을 사유가 아니다.
- **QueryDSL 없이 JPA Criteria만 사용** — 동적 조건 조합이 이 프로젝트의 핵심 난제인데 Criteria로는 표현이 장황해진다. spec §6의 채택 근거와 어긋난다.

## 결과

- 쉬워지는 것: Hibernate 7에서 QueryDSL이 정상 동작한다. `exclude`가 transitive 유입까지 막아 실수로 mainline이 섞이면 **빌드 타임 에러**가 된다(런타임 지뢰가 아니라).
- 어려워지는 것: 인터넷의 QueryDSL 예제 대부분이 mainline 기준이라 좌표를 그대로 복사하면 실패한다. 특히 classifier 규칙 역전.
- 되돌리려면: `build.gradle`의 좌표와 `exclude`를 함께 바꿔야 한다. 되돌릴 이유가 생긴다면 mainline이 Hibernate 7 지원을 재개한 경우뿐이며, 그때도 프로브 테스트로 먼저 확인한다.
