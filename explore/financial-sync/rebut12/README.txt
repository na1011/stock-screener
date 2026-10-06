1부 v7 반증 — 계획 안 렌즈 (2026-10-06 · 0콜 · DB는 SELECT·EXPLAIN만 · 변이는 git archive f9a1d4c7 사본 scratchpad/r12copy)
- mutate.py / run-mutants.sh -> mutants.out : 변이 8 + base. 판 고정 시험 셋을 따로 + 보충 시험 묶음 75(리더 · 보충 · 쓰기 계약 · 판 고정 · 어댑터)
  - S1 folder 이름에서 .xsd 버림 · S2 fetch가 고르기 입력에서 .xsd 버림 · S7 instance 빈 본문 통과 -> 두 해시 · 75 전부 초록(생존)
  - S3 저장 코덱 start 버림 -> 행동 시험 2 붉음(닫힘)
  - S5 mergeInto fp 뺌 · S6 fy trim 뺌(v6 생존 D1) -> 소스 해시만 붉음 · 출력 해시 초록
  - C0 주석만 -> 전부 초록 · C1 메시지 리터럴 안 공백 둘 -> 소스 해시 초록(접힘) · 메시지 자리 시험만 붉음
- NormalizerProbe.java -> normalizer.out : 시험의 정규화기 넷을 그대로 옮겨 경계 입력 13 + 오버로드 1
- q_pick_shapes.out : 캐시 567 색인에서 S1/S2가 바꾸는 판정 수(0)
- q_index_shapes.out : 캐시 567 색인의 item 모양 · 최소 항목 · 이름 없는 항목
- full.log : 사본 전체 시험(러너 XML로 셈: 1,810 사례 · 실패 1 = SpaFallbackTest initializationError(-PskipFrontend) · 그 클래스 9사례 -> 1,818과 맞음)
