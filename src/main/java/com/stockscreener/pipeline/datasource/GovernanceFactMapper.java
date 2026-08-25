package com.stockscreener.pipeline.datasource;

import java.util.List;

/**
 * 원자료 행을 지표가 읽을 숫자로 옮긴다. <b>여기가 벤더 경계다</b>(ADR-0081).
 *
 * <p>경계를 어댑터가 아니라 이쪽에 두는 이유는 <b>값 집합이 개발가이드와 어긋난 자리가 넷</b>이기
 * 때문이다(dart-governance.md의 「350종목 전수 적재가 답한 것」 절). 어댑터에서 타입을 확정하면 매핑을 한 번
 * 틀릴 때마다 수천 콜을 다시 태워야
 * 하는데, 원문을 담아 두면 같은 정정이 <b>외부 호출 0</b>인 재유도로 끝난다.
 *
 * <p>인터페이스 이름과 시그니처에 벤더가 없다. 구현은 {@code datasource/kr}에 <b>package-private</b>로
 * 두어 {@code collector}가 인터페이스만 알게 한다 — ADR-0006이 그 패키지의 코드에 벤더 이름이
 * 등장하는 것을 MUST NOT으로 걸기 때문이고, 같은 패키지여야 숫자 파싱의 정본을 다시 짜지 않는다.
 */
public interface GovernanceFactMapper {

    /**
     * 한 종목의 한 엔드포인트에 쌓인 <b>행 전부</b>를 받아 유도한다.
     *
     * <p>연도별로 나눠 부르지 <b>않는다.</b> 감사인 교체는 회계연도 둘을 견줘야 나오고 그 둘이
     * 서로 다른 보고서에서 오기 때문이다 — 나눠 부르면 그 지표만 조용히 값을 못 낸다.
     *
     * @param rows 같은 {@code (종목, 엔드포인트)}의 행 전부. 데이터 없음 행이 섞여 있을 수 있다
     * @return 유도된 사실. 낼 것이 없으면 빈 목록이다
     */
    List<GovernanceFact> derive(GovernanceEndpoint endpoint, List<GovernanceReportRow> rows);
}
