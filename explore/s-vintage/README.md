# [S] 판 조립 탐색 — 버릴 수 있는 브랜치

main에 들어가지 않는다. `SecEdgarFacts.revisionsOf`의 이어받기 변형을 환경변수로 켜고
`SecEdgarFactsSweepTest`(0콜, 45초)로 캐시 전량을 찍어 `inv.py`로 기준선과 견준다.

    REPLAY_SWEEP_DIR=$PWD/.factcache REPLAY_SWEEP_OUT=<out>.tsv [S_COHERENCE=group|anchor] [S_IDENT=1] \
      ./gradlew test --tests "*SecEdgarFactsSweepTest*" -x frontendTest --rerun-tasks
    python3 explore/s-vintage/inv.py base.tsv cur.txt --diff <out>.tsv

`cur.txt`는 현 편입 티커 목록(`ticker|current` 한 줄씩). 검사기 술어는 DB 펜스와 맞췄다:
현 편입·같은 술어로 B = 436행·143종목(ADR-0158 펜스 339 대차대조표), L = 124칸·76종목(SQL 실측과 동일).

- `S_COHERENCE=group` — 묶음(손익·현금흐름·잔액·주식수)을 재작성한 공시가 안 실은 컬럼은 이어받지 않는다.
- `S_COHERENCE=anchor` — 위와 같되, 묶음의 기준 컬럼(잔액: 자산+자본 · 손익: 매출+순이익)을 안 실은
  부분 공시는 그 묶음을 아예 안 바꾼다.
- `S_IDENT=1` — 손익을 재작성한 공시가 매출·총이익만 실으면 원가 = 매출 − 총이익(반대도).
