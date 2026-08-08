package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.AdminJobLauncher;

/**
 * 화면에서 띄울 수 있는 잡 하나.
 *
 * <p>목록을 서버가 주는 이유는 프론트가 잡 이름을 하드코딩하지 않게 하기 위해서다
 * ({@code .claude/rules/frontend.md} — 서버가 준 것을 화면이 되짚지 않는다). 잡이 늘거나
 * 빠질 때 고칠 곳이 하나여야 한다.
 *
 * @param parameters           화면이 날짜 칸을 몇 개 그릴지 정한다.
 * @param externalCallEstimate 이 버튼이 태우는 외부 API 콜 수의 어림값. 화면이 그대로 보여준다 —
 *                             누르는 순간 쿼터가 나가므로 그 사실이 버튼 옆에 있어야 한다.
 * @param running              지금 도는 중인가. 참이면 화면이 버튼을 잠근다.
 */
public record JobResponse(
        String name,
        String label,
        String description,
        AdminJobLauncher.Parameters parameters,
        int externalCallEstimate,
        boolean running) {

    public static JobResponse of(AdminJobLauncher.Descriptor descriptor, boolean running) {
        return new JobResponse(
                descriptor.name(),
                descriptor.label(),
                descriptor.description(),
                descriptor.parameters(),
                descriptor.externalCallEstimate(),
                running);
    }
}
