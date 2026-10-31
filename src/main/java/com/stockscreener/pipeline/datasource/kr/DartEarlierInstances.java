package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import java.util.List;

/**
 * 그 라벨의 <b>저장된 이른 판의 XBRL 인스턴스</b>(국내 관문 켬 · 재고 「얼린 비최신 판」). DART 재무 API는 보고서마다 가장 늦은 판만 주므로
 * 이른 판의 전체 재무제표는 그 판 자신의 인스턴스(디스크 캐시 · 0콜)로만 다시 지을 수 있다.
 */
public interface DartEarlierInstances {

    /** 인스턴스 하나 — 접수번호 · 압축을 푼 바이트 · 그 판의 한국어 이름표 링크베이스(아직 안 받았으면 {@code null}). */
    record Instance(String rceptNo, byte[] xbrl, byte[] labelsKo) {

        Instance(String rceptNo, byte[] xbrl) {
            this(rceptNo, xbrl, null);
        }
    }

    /**
     * 그 (사업연도, 보고서 코드)의 저장 판 중 {@code latestRceptNo}와 다른 날의 소스 판(복사본 · 유도 판 아님) 각각의 인스턴스 — 판독이 끝난
     * ({@code OK}) 것만 · 캐시에 없으면 뺀다(받기는 {@code kr-xbrl-collect}의 일이다).
     */
    List<Instance> of(long stockId, int year, String reprtCode, String latestRceptNo) throws DataSourceException;
}
