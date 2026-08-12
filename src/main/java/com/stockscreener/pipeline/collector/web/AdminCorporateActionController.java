package com.stockscreener.pipeline.collector.web;

import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.interaction.RecordInteraction;
import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.collector.CorporateActionReader;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.web.ApiExceptions;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 기업 행위 원장을 <b>사람이 판정하는</b> 경로 (ADR-0049 · ADR-0050).
 *
 * <p>화면은 만들지 않는다(ADR-0049 §버린 대안). 판정은 연 수 건이고 API와 원장만으로 성립하며,
 * 프론트 작업을 이 phase에 들이면 phase가 커진다.
 *
 * <p>{@code /api/admin/**} 아래이므로 {@code SecurityConfig}가 이미 {@code ROLE_ADMIN}을
 * 강제한다. 여기에 권한 검사를 다시 쓰지 않는다 — 두 곳에 두면 한쪽만 고쳐지는 날이 온다.
 *
 * <p>도메인 예외를 HTTP로 옮기기만 한다. 원장의 불변식 판정은 {@link CorporateActionLedger}에
 * 있다 — 컨트롤러에 두면 다른 호출부(수집 잡·재구축)를 지나는 변경이 그 검사를 건너뛴다.
 *
 * <p><b>승인해도 계수는 안 바뀐다.</b> {@code APPROVED}는 "적용 대상"이고 {@code APPLIED}가
 * "반영됨"이다. 재구축을 부르는 코드가 아직 없는 것이 정상이며(ADR-0049 — 첫 호출 전에
 * {@code pg_dump}가 필요하다), 상태 어휘가 그 구분을 이미 담고 있다.
 */
@RestController
@RequestMapping("/api/admin/corporate-actions")
public class AdminCorporateActionController {

    private final CorporateActionReader reader;
    private final CorporateActionLedger ledger;

    public AdminCorporateActionController(CorporateActionReader reader,
                                          CorporateActionLedger ledger) {
        this.reader = reader;
        this.ledger = ledger;
    }

    /**
     * 판정 대기열.
     *
     * <p>{@code type} 필터가 있는 이유는 사람이 할 일이 그 축으로 갈리기 때문이다 —
     * {@code type≠UNKNOWN}은 예·아니오만 답하면 되고 {@code UNKNOWN}은 종류와 비율을 정해야
     * 한다. 착수 시점 실측으로 후자가 46건이다.
     */
    @GetMapping
    public List<CorporateActionResponse> list(
            @RequestParam(required = false) CorporateActionStatus status,
            @RequestParam(required = false) CorporateAction.Type type,
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit) {

        return reader.list(status, type, ticker, from, to, limit).stream()
                .map(CorporateActionResponse::of)
                .toList();
    }

    /** 상태별 집계. ADR-0049가 "이 수가 이 phase의 성공을 가른다"고 적은 값이다. */
    @GetMapping("/summary")
    public List<CorporateActionSummaryResponse> summary() {
        return reader.summary().stream().map(CorporateActionSummaryResponse::of).toList();
    }

    /** 원장 행 하나와 판정에 필요한 증거. */
    @GetMapping("/{id}")
    public CorporateActionDetailResponse detail(@PathVariable long id) {
        return reader.findById(id)
                .map(CorporateActionDetailResponse::of)
                .orElseThrow(() -> new ApiExceptions.CorporateActionNotFoundException(id, null));
    }

    /**
     * 판정을 적는다. 갱신된 행과 증거를 그대로 돌려준다.
     *
     * <p>증거를 다시 실어 보내는 이유는 판정이 <b>맞았는지 사람이 그 자리에서 볼 수 있어야</b>
     * 하기 때문이다. 정한 {@code ratio}가 어느 점프와도 맞지 않으면
     * {@code shareJumps[].matchesPrice}가 전부 거짓으로 남는다.
     */
    @PutMapping("/{id}")
    @RecordInteraction(InteractionAction.ADMIN_CORPORATE_ACTION_JUDGE)
    public CorporateActionDetailResponse judge(@PathVariable long id,
                                               @RequestBody CorporateActionJudgementRequest request) {
        try {
            ledger.judge(id, request.toJudgement());
        } catch (CorporateActionLedger.CorporateActionNotFoundException e) {
            throw new ApiExceptions.CorporateActionNotFoundException(e.id(), e);
        } catch (CorporateActionLedger.InvalidJudgementException e) {
            throw ApiExceptions.invalidField(e.field(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // 부호·양수 판정은 CorporateAction의 컴팩트 생성자가 한다. 그것을 여기서 다시 쓰면
            // 부호를 정하는 곳이 둘이 되고, 두 번 뒤집힌 값은 원래 값과 같아 보인다(ADR-0049).
            throw ApiExceptions.invalidField("ratio", e.getMessage());
        }
        return detail(id);
    }
}
