package com.stockscreener.pipeline.batch.web;

import java.time.LocalDate;

/**
 * 기동을 접수했다는 응답(202).
 *
 * <p>새 실행의 id를 담지 않는다. 실행 행은 잡 안에서 만들어지므로 id를 돌려주려면 기동
 * 경로에서 미리 행을 만들어야 하고, 그러면 <b>기록의 시작점이 두 곳</b>이 된다. 화면은 목록을
 * 새로 고쳐 방금 뜬 실행을 본다 — 몇 백 밀리초 뒤의 일이다(ADR-0029).
 */
public record JobLaunchResponse(String jobName, LocalDate targetDate) {
}
