package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 오늘 SEC 티커 파일로 못 푸는 미국 증권의 등록인 찾기 — 폐지 실체(D6 인입)와 파일에 없는 기존 행(등록인 묶기). 탐색
 * {@code US-PAST-LISTING-IMPL-1018.md} §2.2.
 *
 * <p>판정은 「후보(이름 · 효력일 무렵 제거 양식) → 사건 전 정기 공시 표지에 그 공시일의 기호 줄이 있는가」 하나다. 벤더 지식(색인 ·
 * 양식 · 표지)은 구현이 든다.
 */
public interface RegistrantLookup {

    /** 한 실행의 조회 — 창의 색인을 한 번 받아 둔다. */
    Session open(LocalDate from, LocalDate asOf) throws DataSourceException;

    interface Session {

        /**
         * @param symbol       그 날의 기호
         * @param before       표지를 찾을 끝(제외) — 효력일 또는 오늘
         * @param securityName 이름 대조에 쓸 증권 이름 — <b>재사용 기호면 비워 넘긴다</b>(시세 소스가 오늘 주인 이름을 준다)
         * @param renames      그 실체에 걸린 개명(그 공시일의 기호를 정하는 재료)
         */
        Result find(String symbol, LocalDate before, Optional<String> securityName, List<Rename> renames)
                throws DataSourceException;
    }

    /** {@code from}이 {@code date}에 {@code to}가 됐다. */
    record Rename(String from, String to, LocalDate date) {
    }

    enum Outcome {
        /** 등록인 하나가 확인됐고 표지 거래소가 NYSE · Nasdaq이며 효력일 무렵 제거 양식과 범주가 안 갈린다. */
        BOUND,
        /** 확인된 등록인이 없다. */
        NONE,
        /** 확인된 등록인이 둘 이상이다(모회사 + 운영 파트너십). */
        AMBIGUOUS,
        /** 확인됐는데 표지 거래소가 범주 밖이다(NYSE American 등). */
        OUTSIDE,
        /** 확인됐는데 그 기호의 표지 줄이 보통주가 아니다(워런트 · 유닛 · 권리 · 우선주 — 순위의 이름 축이 어차피 뺀다). */
        NOT_COMMON,
        /** 확인됐는데 표지와 효력일 무렵 제거 양식의 거래소 범주가 갈린다(원장 갈림 · ORLA). */
        SPLIT_LEDGER
    }

    /**
     * @param cik      확인된 등록인({@link Outcome#BOUND} · {@link Outcome#OUTSIDE} · {@link Outcome#NOT_COMMON} · {@link Outcome#SPLIT_LEDGER})
     * @param exchange {@code NYSE} · {@code NASDAQ}({@link Outcome#BOUND}만)
     * @param name     등록인 이름 + 표지 증권 이름 — 행 이름이 된다
     * @param evidence 대조한 후보와 결과(실행 기록의 귀속 재료)
     */
    record Result(Outcome outcome, Long cik, String exchange, String name, String evidence) {
    }
}
