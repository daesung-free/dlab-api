package com.dlab.api.admin.notification;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.notification.entity.ManualSendScope;
import com.dlab.domain.notification.service.ManualNotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 관리자 웹 — 직원 수동 발송 (디랩 1016 문구 3장 「직원 발송」 4종).
 *
 * <h2>★★ 2단계다. 미리보기를 거치지 않으면 발송이 거부된다</h2>
 * 발송 요청은 {@code previewId} 하나만 받는다 — 조건({@code scope}·{@code enrollmentIds})을
 * 발송 본문에 다시 싣게 하면 화면이 미리보기 뒤에 대상을 바꿔도 서버는 모르고,
 * <b>담당자가 본 것과 다른 사람에게 나간다.</b>
 *
 * <p>★ <b>취소 창을 두지 않았다.</b> 알림톡은 보내는 즉시 나가고 되돌릴 수 없다.
 * 미리보기 게이트가 그 역할이다.
 *
 * <p>★ <b>예약 발송이 없다.</b> 「마감 3일 전」은 수동 발송이 아니라 배치가 할 일이고
 * 디랩 확인 대기다 — 얹으면 {@code previewId} 를 들고 기다리는 구조가 되어 유효기간과
 * 충돌한다.
 *
 * <p>⚠️ <b>지점 범위를 반드시 건다.</b> 안 걸면 <b>다른 지점 학생에게 발송</b>할 수 있다.
 * 요청의 {@code academyId} 를 그냥 믿지 않고 {@link AuthPrincipal#requireAcademyScope}
 * 로 검증한다 — 지점 관리자가 남의 지점을 넣으면 거절되고, 본사는 지점을 골라야 한다
 * (전 지점에 한꺼번에 뿌리는 버튼이 되면 안 된다).
 */
@Tag(name = "관리자 · 직원 수동 발송 (F-4.4)")
@RestController
@RequestMapping("/api/v1/admin/notifications/manual")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminManualNotificationController {

    private final ManualNotificationService manualNotificationService;

    /**
     * 발송 대상·문구 미리보기. <b>아무것도 보내지 않는다.</b>
     *
     * @param request {@code scope} 는 {@code BRANCH}(지점 전체) · {@code CLASS}(반) ·
     *                {@code SELECTED}(고른 학생). 대기자는 아직 범위에 없다 —
     *                입학 대기자 도메인이 없어 고를 대상이 없다
     */
    @PostMapping("/preview")
    public ApiResponse<ManualNotificationService.PreviewResult> preview(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody PreviewRequest request) {

        return ApiResponse.success(manualNotificationService.preview(
                new ManualNotificationService.PreviewCommand(
                        request.templateId(),
                        // ⚠️ 요청 값을 그대로 쓰지 않는다. 남의 지점이면 403,
                        //    본사가 지점을 안 골랐으면 400 이다
                        me.requireAcademyScope(request.academyId()),
                        request.scope(), request.classId(), request.enrollmentIds(),
                        request.variables())));
    }

    /**
     * 미리보기에서 확인한 명단에 발송.
     *
     * <p>★ <b>{@code previewId} 는 한 번만 먹는다.</b> 두 번째 호출은 409 다 —
     * 버튼이 두 번 눌리거나 새로고침되면 같은 학부모에게 두 통이 가고, 알림톡은
     * <b>건당 과금</b>이다. 코드가 아니라 DB 유니크 제약이 막는다.
     */
    @PostMapping("/send")
    public ApiResponse<ManualNotificationService.SendResult> send(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SendRequest request) {

        // 지점 관리자는 자기 지점으로 고정되고, 본사는 null(= 미리보기의 지점을 그대로 쓴다)
        return ApiResponse.success(manualNotificationService.send(
                request.previewId(), me.academyScopeFilter()));
    }

    /**
     * @param variables 직원이 채우는 변수. 디랩 문구가 한글 변수명이라
     *                  ({@code {월}} · {@code {기간}} · {@code {날짜}}) 키도 한글이다.
     *                  {@code studentName} 은 <b>넣지 않는다</b> — 수신자마다 달라
     *                  서버가 채운다
     */
    public record PreviewRequest(
            @NotNull Long templateId,
            Long academyId,
            @NotNull ManualSendScope scope,
            Long classId,
            List<Long> enrollmentIds,
            Map<String, String> variables) {
    }

    public record SendRequest(@NotBlank String previewId) {
    }
}
