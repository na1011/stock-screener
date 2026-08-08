package com.stockscreener.auth.web;

import com.stockscreener.auth.AllowedEmailService;
import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.interaction.RecordInteraction;
import com.stockscreener.web.ApiExceptions;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 허용목록 관리 (spec §4 "계정 관리").
 *
 * <p>이 화면이 생기기 전까지 지인을 초대하는 유일한 방법이 psql이었다.
 *
 * <p>IMPORTANT — 권한 변경은 <b>다음 로그인부터</b> 적용된다. {@code app_user.is_admin}이
 * 로그인 시점의 사본이기 때문이며, 그 중복은 ADR-0004가 의도적으로 둔 것이다. 화면이 그
 * 사실을 문구로 말해야 관리자가 "왜 아직 그대로인가"를 버그로 읽지 않는다.
 *
 * <p>도메인 예외를 HTTP로 옮기기만 한다. 마지막 관리자 판정은 {@link AllowedEmailService}에
 * 있다 — 컨트롤러에 두면 SQL이나 다른 호출부를 지나는 변경이 그 검사를 건너뛴다.
 */
@RestController
@RequestMapping("/api/admin/allowed-emails")
public class AdminAllowlistController {

    private final AllowedEmailService allowedEmails;

    public AdminAllowlistController(AllowedEmailService allowedEmails) {
        this.allowedEmails = allowedEmails;
    }

    @GetMapping
    public List<AllowedEmailResponse> list() {
        return allowedEmails.list().stream().map(AllowedEmailResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RecordInteraction(InteractionAction.ADMIN_ALLOWLIST_ADD)
    public AllowedEmailResponse add(@Valid @RequestBody AllowedEmailRequest request) {
        try {
            return AllowedEmailResponse.of(
                    allowedEmails.add(request.email(), request.admin(), request.note()));
        } catch (AllowedEmailService.DuplicateAllowedEmailException e) {
            throw new ApiExceptions.DuplicateAllowedEmailException(e.email(), e);
        }
    }

    /** 이메일은 바꿀 수 없다 — 다른 주소를 넣는 것은 다른 사람을 초대하는 것이다. */
    @PutMapping("/{id}")
    @RecordInteraction(InteractionAction.ADMIN_ALLOWLIST_UPDATE)
    public AllowedEmailResponse update(@PathVariable long id,
                                       @Valid @RequestBody AllowedEmailUpdateRequest request) {
        try {
            return AllowedEmailResponse.of(
                    allowedEmails.update(id, request.admin(), request.note()));
        } catch (AllowedEmailService.AllowedEmailNotFoundException e) {
            throw new ApiExceptions.AllowedEmailNotFoundException(e.id(), e);
        } catch (AllowedEmailService.LastAdminException e) {
            throw new ApiExceptions.LastAdminException(e);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RecordInteraction(InteractionAction.ADMIN_ALLOWLIST_REMOVE)
    public void remove(@PathVariable long id) {
        try {
            allowedEmails.remove(id);
        } catch (AllowedEmailService.AllowedEmailNotFoundException e) {
            throw new ApiExceptions.AllowedEmailNotFoundException(e.id(), e);
        } catch (AllowedEmailService.LastAdminException e) {
            throw new ApiExceptions.LastAdminException(e);
        }
    }
}
