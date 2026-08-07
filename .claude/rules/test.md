---
paths:
  - "src/test/**"
---

# 테스트 판단 기준

아래는 설계 원칙이 아니라 **실제로 겪은 것**이다. 공통점은 **원인과 무관해 보이는 테스트가 깨진다**는 것이라, 증상에서 원인으로 거슬러 올라가기 어렵다.

- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이고, 커밋을 요구하는 테스트만 `@AfterEach` 정리를 쓴다.
- **테스트 DB와 운영 DB의 활성 유니버스가 정반대다.** 운영은 `KOSPI_TOP200`·`KOSDAQ_TOP150`이 활성이고 시드는 0건, 테스트 DB는 시드가 활성·전부 0건이다. 유니버스 테스트는 픽스처를 직접 심는다.
- **`ADJ_CLOSE`는 봉 하나로 값이 나오는 첫 지표다.** `DELETE FROM stock` 앞에 `indicator_value` 정리가 없으면 FK로 정리가 중단되고 **무관해 보이는 다른 테스트**가 깨진다.
- **MockMvc는 `with(csrf())`가 없으면 403이다.** CSRF 지연 로딩을 꺼둔 결과다(`.claude/rules/backend.md`).

**증상이 설정 쪽이면 `.claude/rules/config.md`를 본다.** 커넥션 풀·`out-of-order`·Flyway 체크섬·`.env` 주입 금지가 거기 있다. 그 파일을 열면 자동으로 로드되지만, **진단은 대개 그 파일을 열기 전에 시작된다.**
