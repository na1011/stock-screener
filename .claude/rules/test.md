---
paths:
  - "src/test/**"
---

# 테스트 판단 기준

아래는 설계 원칙이 아니라 **실제로 겪은 것**이다(2026-08, `docs/progress.md`에서 옮겨 왔다 — 주입 상한을 넘겨 조건부 로드로 돌렸다). 공통점은 **원인과 무관해 보이는 테스트가 깨진다**는 것이라, 증상에서 원인으로 거슬러 올라가기 어렵다.

- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이고, 커밋을 요구하는 테스트만 `@AfterEach` 정리를 쓴다.
- **커넥션 풀은 2로 제한한다.** 기본값 10이면 무관한 테스트가 "too many clients"로 깨진다(실제 14건).
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 운영 마이그레이션보다 위에 있다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다.
- **테스트 DB와 운영 DB의 활성 유니버스가 정반대다.** 운영은 `KOSPI_TOP200`·`KOSDAQ_TOP150`이 활성이고 시드는 0건, 테스트 DB는 시드가 활성·전부 0건이다. 유니버스 테스트는 픽스처를 직접 심는다.
- **`ADJ_CLOSE`는 봉 하나로 값이 나오는 첫 지표다.** `DELETE FROM stock` 앞에 `indicator_value` 정리가 없으면 FK로 정리가 중단되고 **무관해 보이는 다른 테스트**가 깨진다.
- **MockMvc는 `with(csrf())`가 없으면 403이다.** CSRF 지연 로딩을 꺼둔 결과다(`.claude/rules/backend.md`).
- **MUST NOT: `.env`를 `test` 태스크에 주입하지 않는다.** 실키가 있으면 `@ConditionalOnApiKey` 어댑터가 등록돼 **빈 구성 자체가 바뀐다.** 단위 테스트는 외부 호출 0건이 전제다. 실제 외부 호출은 `@Tag("live")` + `./gradlew liveTest`에만 둔다.
