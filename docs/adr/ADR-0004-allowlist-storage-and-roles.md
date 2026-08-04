---
id: ADR-0004
status: 채택됨
date: 2026-08-05
applies_to:
  - "src/main/java/com/stockscreener/auth/**"
  - "src/main/resources/db/migration/V4__auth.sql"
---

# ADR-0004. 허용목록을 테이블로 두고 관리자 권한은 로그인 시 복사한다

## 결정

허용 이메일 목록을 설정이 아닌 DB 테이블(`allowed_email`)로 둔다. `app_user`와 분리하고 둘 사이에 FK를 걸지 않는다. 관리자 권한은 `allowed_email`이 근거이고 `app_user.is_admin`은 로그인 시 갱신되는 사본이다.

> spec §6이 이미 "Google OAuth 소셜 로그인 + 허용 이메일 목록"을 근거와 함께 채택했다. 이 ADR은 그 결정을 옮겨 적는 것이 아니라, **저장 구조와 권한 모델**이라는 그 이후의 판단만 기록한다.

## 규칙

- MUST: 이메일을 소문자·trim으로 정규화해 비교·저장한다. DB 유니크 인덱스도 `lower(email)`에 건다.
- MUST: 허용목록에 없으면 인증 자체를 거부한다. 세션을 만들고 화면을 비우지 않는다.
- MUST: 인증 식별자는 Google `sub`(`provider_user_id`), 인가 식별자는 이메일이다.
- MUST NOT: `app_user.email`과 `allowed_email.email` 사이에 FK를 걸지 않는다.
- MUST NOT: 관리자 기능을 별도 애플리케이션으로 분리하지 않는다. 같은 앱에서 `ROLE_ADMIN`으로 분리한다.
- MUST NOT: 실제 클라이언트 ID·시크릿을 설정 파일에 넣지 않는다. 환경변수로만 주입한다.

## 검증

```bash
./gradlew test --tests "*AllowedEmailServiceTest*" --tests "*AllowlistOidcUserServiceTest*" --tests "*SecurityConfigTest*"
```
미허용 이메일 차단, 대소문자·공백 무시, 권한 회수의 로그인 시 반영, 비관리자의 관리자 엔드포인트 403을 확인한다.

```bash
grep -rn 'client-id:\|client-secret:' src/main/resources/ | grep -v '\${'
```
아무것도 반환하지 않아야 한다 — 모든 자격증명이 `${ENV_VAR:...}` 형태여야 한다.

```bash
grep -rnE 'GOCSPX|[0-9]{12}-[a-z0-9]{32}\.apps\.googleusercontent\.com' src/
```
아무것도 반환하지 않아야 한다(실제 Google 자격증명의 형태다).

## 배경

spec §4가 관리자 화면에서 "접근이 허용된 이메일 목록을 추가·삭제하고 관리자 권한 부여 여부를 설정"하는 기능을 요구한다. 설정 파일에 두면 이 기능을 구현할 수 없다(재배포 없이 바꿀 수 없다).

`allowed_email`과 `app_user`를 분리하는 이유는 **허용목록에만 있고 아직 로그인하지 않은 사람**이 정상 상태이기 때문이다. 그 사람은 Google sub도 이름도 없고, 저장된 화면이 참조할 id도 없다. 하나로 합치면 대부분 컬럼이 NULL인 행과 "실제로 로그인했는가" 불리언이 생긴다.

FK를 걸지 않는 이유는 **권한 회수가 사용자의 저장된 화면을 지워서는 안 되기** 때문이다. 다시 초대하면 화면이 남아 있어야 한다.

`is_admin`이 두 테이블에 있는 것은 의도된 중복이다. 매 인가 검사마다 `allowed_email`을 조인하면 인증 경로가 허용목록 테이블에 결합되고, "권한 회수는 다음 로그인부터 적용된다"는 성질이 암묵적이 된다. IMPORTANT — 이 규칙이 기록되지 않으면 다음 세션이 중복으로 보고 한쪽을 지운다.

## 버린 대안

- **허용목록을 `application.yaml`에 배열로** — spec §4의 관리자 기능(재배포 없는 추가·삭제)을 구현할 수 없다.
- **`allowed_email`과 `app_user` 통합** — NULL 투성이 행과 상태 플래그가 생기고, 권한 회수가 사용자 데이터 삭제와 얽힌다.
- **`role`·`user_role` 테이블** — 역할이 둘(사용자·관리자)뿐인데 조인 테이블을 두는 것은 과잉이다. 세 번째 역할이 실제로 필요해지면 `is_admin`에서 백필하는 자체 완결적 마이그레이션으로 도입한다.
- **매 요청마다 `allowed_email` 조회** — 인가 경로가 DB에 매번 의존하고, 권한 회수 시점이 명시적이지 않게 된다.
- **거부 사유를 사용자에게 노출** — 허용목록의 존재 자체가 운영 정보다. 지인이 쓰는 서비스라도 경계는 유지한다.

## 결과

- 쉬워지는 것: Google 자격증명 없이 인가 로직 전체를 구현·검증할 수 있다(합성 `OidcUser`로 테스트). 관리자 기능이 spec §4대로 테이블 CRUD가 된다.
- 어려워지는 것: 권한 회수가 즉시 반영되지 않고 다음 로그인부터 적용된다. 이 프로젝트 규모(지인 몇 명)에서는 문제가 되지 않지만, 즉시 차단이 필요해지면 세션 무효화를 별도로 구현해야 한다.
- 되돌리려면: `auth` 패키지와 V4 마이그레이션을 함께 고쳐야 한다.
