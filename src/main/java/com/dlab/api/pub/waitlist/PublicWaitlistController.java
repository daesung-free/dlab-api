package com.dlab.api.pub.waitlist;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.admission.entity.WaitlistAnswer;
import com.dlab.domain.audit.AuditContext;
import com.dlab.domain.share.service.PublicWaitlistShareService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기자 응답 — <b>로그인 없이 열린다.</b>
 *
 * <p>「대기자 안내」 카카오 알림톡 버튼이 {@code /waitlist/:token} 으로 들어온다.
 * 알림톡은 단방향이라 답장하면 카카오톡 1:1 채팅으로 들어가고 우리 시스템과 연결되지
 * 않는다 — <b>지금은 전화를 받아 직원이 손으로 적는다.</b> 등록 의사를 버튼으로 받아
 * 그 수기 입력을 없앤다.
 *
 * <h2>★★ 없는 토큰·만료 토큰도 HTTP 200 이다</h2>
 * 조회는 {@code available:false}, 응답은 {@code accepted:false} 다. 404 로 내리면
 * <b>토큰을 두드려 보는 쪽에 힌트</b>가 된다(도어락 공개 경로와 같은 이유).
 *
 * <h2>★★ 답변은 1회 고정이고, 그래도 페이지는 계속 열린다</h2>
 * 두 번째 응답은 거부하지만 <b>토큰을 죽이지는 않는다</b> — {@code answered}·
 * {@code answeredAt} 으로 자기가 무엇을 눌렀는지 보여야 한다. 안 그러면 「눌렀는데
 * 아무 일도 없었다」로 다시 열려 하고, 그때 페이지가 안 열려 전화가 온다.
 */
@Tag(name = "공개 · 대기자 응답")
@RestController
@RequestMapping("/api/v1/public/waitlist")
@RequiredArgsConstructor
public class PublicWaitlistController {

    private final PublicWaitlistShareService shareService;

    @GetMapping("/{token}")
    public ApiResponse<PublicWaitlistShareService.View> view(@PathVariable String token) {
        return ApiResponse.success(shareService.view(token));
    }

    /**
     * 등록 의사 응답.
     *
     * <p>★ <b>IP 를 남긴다.</b> 공개 경로라 주체가 없다 — 토큰이 새면 남이 누를 수 있고,
     * 1회 고정이라 피해가 1건으로 끝나지만 <b>그 1건이 어디서·언제 들어왔는지는 보여야
     * 한다.</b>
     */
    @PostMapping("/{token}")
    public ApiResponse<PublicWaitlistShareService.AnswerResult> answer(
            @PathVariable String token,
            @Valid @RequestBody AnswerRequest body) {

        return ApiResponse.success(
                shareService.answer(token, body.answer(), AuditContext.actorIp()));
    }

    /** @param answer {@code ACCEPT}(등록하겠다) · {@code DECLINE}(등록하지 않겠다) */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublicWaitlistAnswerRequest")
    public record AnswerRequest(@NotNull(message = "응답을 선택해 주세요.") WaitlistAnswer answer) {
    }
}
