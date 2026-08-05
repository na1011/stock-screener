package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 기준일 시점의 지수 편입 종목 <b>전체</b> 스냅샷.
 *
 * <p>IMPORTANT — 부분 목록이 아니라 전체여야 한다. 리밸런싱은 "이 목록에 없으면 제외"로
 * 동작하므로, 부분 목록을 전체로 오인하면 대량 오탈락이 일어난다. 그 결과는 스크리닝에서
 * 종목이 사라지는 형태로 나타나는데, 원인을 유니버스에서 찾기까지 시간이 걸린다.
 * 소스가 불완전한 응답을 줬다면 어댑터가 {@link DataSourceException}을 던져야 하며,
 * 이 타입으로 만들어 반환해서는 안 된다.
 *
 * @param asOf       이 구성이 유효한 기준일. {@code universe_membership.effective_from}이 된다.
 * @param sourceName 어느 소스가 줬는가. 시드와 스크래핑을 구분해야 폴백이 일어났음을 알 수 있다.
 */
public record UniverseSnapshot(
        String universeCode,
        LocalDate asOf,
        List<String> tickers,
        String sourceName
) {

    public UniverseSnapshot {
        if (universeCode == null || universeCode.isBlank()) {
            throw new IllegalArgumentException("universeCode가 비어 있다.");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf가 null이다. universeCode=" + universeCode);
        }
        if (tickers == null || tickers.isEmpty()) {
            throw new IllegalArgumentException(
                    "빈 구성종목 목록이다: " + universeCode
                            + " — 빈 스냅샷은 '전 종목 제외'를 뜻하므로 허용하지 않는다.");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName이 비어 있다. universeCode=" + universeCode);
        }
        List<String> copy = List.copyOf(tickers);
        Set<String> distinct = Set.copyOf(copy);
        if (distinct.size() != copy.size()) {
            throw new IllegalArgumentException(
                    "구성종목에 중복 티커가 있다: " + universeCode
                            + " — 중복은 소스 파싱 오류의 신호이며, 그대로 두면"
                            + " universe_membership의 EXCLUDE 제약에서 뒤늦게 터진다.");
        }
        tickers = copy;
    }

    public int size() {
        return tickers.size();
    }
}
