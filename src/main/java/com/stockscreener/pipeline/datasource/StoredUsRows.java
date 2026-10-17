package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;

/**
 * <b>우리가 이미 저장한</b> 미국 행의 신원 — 과거 날짜 순위(D4)의 후보 재료다. 외부 소스가 아니다({@link StoredShareCounts}와 같은 자리 ·
 * 어댑터가 DB에 직접 닿지 않게 포트로 둔다).
 *
 * <p>과거 날짜의 후보는 오늘 목록의 기호가 아니라 <b>행</b>이다(탐색 {@code US-PAST-LISTING-PLAN-1017.md} D4) — 닫힌 행 · 들인 폐지 실체 행도
 * 그 기준일이 시대 안이면 후보다. 봉은 닻(기호 · 날짜)으로 묻는다 — 기호 문자열은 오늘 다른 실체일 수 있다(★19).
 */
public interface StoredUsRows {

    /** 닻이 있는 미국 행 전부(열린 · 닫힌). */
    List<Row> rows();

    /**
     * @param ticker 표지 — 닫힌 행은 비운 꼴({@code T~날짜})
     * @param name   저장된 이름(목록에서 만든 행은 시세 소스의 증권 이름 · 들인 행은 등록인 이름 + 표지 증권 이름) — 이름 축 재료
     */
    record Row(long id, String ticker, String name, String anchorSymbol, LocalDate anchorDate, Long secCik,
               LocalDate eraFrom, LocalDate eraTo) {

        /** 그 행이 마지막으로 쓴 기호 — 비운 표지면 그 앞 글자. */
        public String symbol() {
            int cut = ticker.indexOf(SecurityRef.VACATED);
            return cut < 0 ? ticker : ticker.substring(0, cut);
        }

        public boolean open() {
            return eraTo == null;
        }
    }
}
