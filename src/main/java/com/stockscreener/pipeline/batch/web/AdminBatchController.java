package com.stockscreener.pipeline.batch.web;

import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.interaction.RecordInteraction;
import com.stockscreener.pipeline.batch.AdminJobLauncher;
import com.stockscreener.pipeline.batch.BatchExecutionReader;
import com.stockscreener.pipeline.batch.BatchStatus;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.ApiProblemType;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 배치 실행 이력 조회와 수동 개입 (spec §4, §9의 다섯째 수용 기준).
 *
 * <p>{@code /api/admin/**} 아래이므로 {@code SecurityConfig}가 이미 {@code ROLE_ADMIN}을
 * 강제한다. 여기에 권한 검사를 다시 쓰지 않는다 — 두 곳에 두면 한쪽만 고쳐지는 날이 온다.
 *
 * <p>기동과 재실행이 <b>202</b>인 이유는 850종목 수집이 HTTP 응답을 기다리지 않기 때문이다.
 * 새 실행의 id를 돌려주지 않는 것은 그 행이 잡 안에서 만들어지기 때문이고, 화면은 목록을
 * 새로 고쳐 방금 뜬 실행을 본다(ADR-0029).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminBatchController {

    private final BatchExecutionReader reader;
    private final AdminJobLauncher launcher;

    public AdminBatchController(BatchExecutionReader reader, AdminJobLauncher launcher) {
        this.reader = reader;
        this.launcher = launcher;
    }

    @GetMapping("/batches")
    public List<BatchExecutionResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) BatchStatus status,
            @RequestParam(required = false) Integer limit) {

        return reader.list(from, to, jobName, status, limit).stream()
                .map(BatchExecutionResponse::of)
                .toList();
    }

    /** 운영 현황 요약 — 잡별 최근 실행과 미해결 실패 건수. */
    @GetMapping("/batches/summary")
    public BatchSummaryResponse summary() {
        BatchExecutionReader.Summary summary = reader.summary();
        return new BatchSummaryResponse(
                summary.latestByJob().stream().map(BatchExecutionResponse::of).toList(),
                summary.unresolvedFailureCount(),
                launcher.runningJobs().stream().sorted().toList());
    }

    @GetMapping("/batches/{id}")
    public BatchExecutionDetailResponse detail(@PathVariable long id) {
        BatchExecutionReader.Execution execution = reader.findById(id)
                .orElseThrow(() -> new ApiExceptions.BatchExecutionNotFoundException(id));

        return new BatchExecutionDetailResponse(
                BatchExecutionResponse.of(execution),
                reader.phasesOf(id).stream().map(BatchPhaseResponse::of).toList(),
                reader.failureBreakdown(id).stream().map(BatchFailureGroupResponse::of).toList(),
                reader.failuresOf(id).stream().map(BatchFailureResponse::of).toList(),
                AdminJobLauncher.supportsRetry(execution.jobName()));
    }

    /**
     * 실패한 부분만 다시 돌린다.
     *
     * <p>재실행은 원래 실행과 {@code retried_from_id}로 연결되어 남는다. 연결이 없으면
     * "재시도했는데 또 실패"가 화면에서 보이지 않는다.
     */
    @PostMapping("/batches/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RecordInteraction(InteractionAction.ADMIN_BATCH_RETRY)
    public JobLaunchResponse retry(@PathVariable long id) {
        BatchExecutionReader.Execution execution = reader.findById(id)
                .orElseThrow(() -> new ApiExceptions.BatchExecutionNotFoundException(id));

        if (!AdminJobLauncher.supportsRetry(execution.jobName())) {
            // 실패 단위가 종목이 아닌 잡이다. 조용히 아무것도 하지 않으면 관리자는
            // 재실행이 돌고 있다고 믿고 결과를 기다린다.
            throw ApiExceptions.of(HttpStatus.CONFLICT, ApiProblemType.CONFLICT,
                    "이 잡은 부분 재실행을 지원하지 않는다: " + execution.jobName());
        }
        if (execution.unresolvedFailureCount() == 0) {
            // 그대로 태우면 대상 0건짜리 실행 행만 하나 더 생긴다. 이력이 지저분해지고
            // "재실행했는데 아무 일도 없었다"로 보인다.
            throw ApiExceptions.of(HttpStatus.CONFLICT, ApiProblemType.CONFLICT,
                    "재실행할 미해결 실패가 없다.");
        }

        if (!launcher.relaunchFailures(id, execution.targetDate())) {
            throw new ApiExceptions.JobAlreadyRunningException(execution.jobName());
        }
        return new JobLaunchResponse(execution.jobName(), execution.targetDate());
    }

    /** 화면에서 띄울 수 있는 잡. 프론트가 이름을 하드코딩하지 않게 서버가 준다. */
    @GetMapping("/jobs")
    public List<JobResponse> jobs() {
        return launcher.catalog().stream()
                .map(descriptor -> JobResponse.of(descriptor, launcher.isRunning(descriptor.name())))
                .toList();
    }

    @PostMapping("/jobs/{jobName}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RecordInteraction(InteractionAction.ADMIN_JOB_RUN)
    public JobLaunchResponse run(@PathVariable String jobName,
                                 @RequestBody(required = false) JobRunRequest request) {

        if (!launcher.isLaunchable(jobName)) {
            throw new ApiExceptions.JobNotFoundException(jobName);
        }
        // 뒤집힌 창은 잡마다 다르게 실패한다 — 지표 백필은 조용한 SUCCESS로, 기업 행위 수집은
        // 예외로 끝난다. 여기서 거절하면 그 차이와 무관하게 사용자가 무엇이 잘못됐는지 안다.
        // 잡 쪽 방어를 대신하지는 못한다 — to를 비우면 런처가 기본값을 채우므로 이 가드를 지나간다.
        if (request != null && request.from() != null && request.to() != null
                && request.from().isAfter(request.to())) {
            throw ApiExceptions.invalidField("from",
                    "조회 창의 시작이 끝보다 늦다: " + request.from() + " > " + request.to());
        }

        AdminJobLauncher.JobRequest parameters = request == null
                ? AdminJobLauncher.JobRequest.empty()
                : new AdminJobLauncher.JobRequest(request.targetDate(), request.from(), request.to());

        if (!launcher.launch(jobName, parameters)) {
            throw new ApiExceptions.JobAlreadyRunningException(jobName);
        }
        return new JobLaunchResponse(jobName, request == null ? null : request.targetDate());
    }
}
