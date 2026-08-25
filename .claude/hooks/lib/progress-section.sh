#!/bin/bash
# docs/progress.md에서 **주입하지 않을 절**을 가르는 파서. 주입 훅과 낡음 검사가 함께 쓴다.
#
# 왜 lib인가 — 절 경계를 어디로 잡을지는 스칼라가 아니라 **알고리즘**이다. 두 곳에 적으면
# 갈리고, 그때 검사는 통과하는데 주입은 다른 것을 낸다. 상한(`limit=`)을 훅에서 읽어 오는
# 것과 같은 이유이고, 그쪽은 값 하나라 읽기로 충분했다.
#
# MUST NOT: 아래 절 이름을 **주석 줄에 쓰지 않는다.** check-doc-staleness.sh의 죽은 절 참조
# 검사는 제목을 `*.md`와 **`*.sh`** 양쪽에서 `^#{1,6}[[:space:]]`로 걷는다 — 셸 주석에 적으면
# 그 줄이 스스로 "제목"이 되어 자기 참조를 해소하고, 문서에서 절이 사라져도 영영 안 운다.
# 이름은 아래 대입문처럼 **코드 줄에만** 둔다.
#
# MUST NOT: awk에 `{1,3}` 같은 구간 반복을 쓰지 않는다. BSD awk가 지원하지 않는다.

PROGRESS_FILE="${PROGRESS_FILE:-docs/progress.md}"
PROGRESS_EXCLUDED_SECTION='### 바로 착수 가능한 것'

# 제외 절이 실재하는가. 0이면 있다.
progress_has_section() {
  grep -qxF "$PROGRESS_EXCLUDED_SECTION" "${1:-$PROGRESS_FILE}" 2>/dev/null
}

# 제외 절을 뺀 본문. 절이 없으면 원문을 그대로 낸다 —
# **전부 빼는 것이 무성실패다**(백로그가 조용히 사라지고 "항목 0"과 구분되지 않는다).
# 전부 내는 쪽은 주입이 커지는 것으로 드러난다.
#
# 경계는 **레벨 3 이하의 다음 제목**이다. `##`까지로 끊으면 그 뒤에 `###`를 하나 더 넣는
# 순간 그것까지 조용히 함께 빠지고, 그 손실은 어떤 검사도 잡지 않는다.
progress_kept() {
  awk -v sec="$PROGRESS_EXCLUDED_SECTION" '
    BEGIN { skip = 0 }
    {
      if ($0 == sec) { skip = 1; next }
      if (skip && ($0 ~ /^# / || $0 ~ /^## / || $0 ~ /^### /)) skip = 0
      if (!skip) print
    }' "${1:-$PROGRESS_FILE}" 2>/dev/null
}

# 제외 절 안의 항목 수. 절이 없으면 0을 낸다 —
# 부르는 쪽이 progress_has_section으로 **먼저** 갈라야 "없다"와 "비었다"가 안 섞인다.
progress_excluded_items() {
  awk -v sec="$PROGRESS_EXCLUDED_SECTION" '
    BEGIN { inside = 0; c = 0 }
    {
      if ($0 == sec) { inside = 1; next }
      if (inside && ($0 ~ /^# / || $0 ~ /^## / || $0 ~ /^### /)) inside = 0
      if (inside && $0 ~ /^- /) c++
    }
    END { print c + 0 }' "${1:-$PROGRESS_FILE}" 2>/dev/null
}
