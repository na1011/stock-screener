package com.stockscreener.pipeline.batch;

/**
 * 배치 실행의 상태. {@code batch_execution.status}의 값이다.
 *
 * <p>Spring Batch의 동명 타입에서 이름을 빌렸지만 뜻이 다르고, 그 의존성은 걷었다(ADR-0075).
 * 이쪽은 종목 단위
 * 부분 실패라는 이 프로젝트의 개념({@link #PARTIAL_SUCCESS})을 담아야 하고,
 * 그 개념은 Spring Batch의 상태 모델에 없다.
 */
public enum BatchStatus {

    RUNNING,

    /** 전 종목 성공. */
    SUCCESS,

    /**
     * 일부 종목이 실패했으나 배치는 끝까지 돌았다.
     *
     * <p>IMPORTANT — 이것을 FAILED로 합치면 안 된다. spec §4가 "성공/부분실패/실패"를 구분해
     * 요약하도록 요구하며, 850종목 중 두어 개 실패는 일일 갱신의 정상 범위다. 합치면
     * 매일 빨간불이 떠서 진짜 장애를 가린다.
     */
    PARTIAL_SUCCESS,

    /** 배치 자체가 실패했다. 인증 실패, DB 접속 불가 등. */
    FAILED,

    /** 외부 요인으로 중단됐다. 일일 쿼터 소진이 대표적이며, 이것은 정상적인 일시정지다. */
    ABORTED
}
