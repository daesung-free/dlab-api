package com.dlab.api.app.approval;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ApprovalRequests {

    private ApprovalRequests() {
    }

    /**
     * 우선 승인자 선택 + 동의 (0803 답변서).
     *
     * <p><b>동의 없이는 선택할 수 없다.</b> 학부모를 고르면 "10분 내 미응답 시 직원에게
     * 승인권이 넘어간다"는 안내에 동의해야 하고, 이건 법적 성격의 동의라 기록으로 남는다.
     */
    public record ChooseApprover(
            /**
             * ★ 어느 자녀의 승인자인가. <b>학부모가 고르므로 필수</b>다 —
             * 자녀가 둘이면 <b>자녀마다 고른다</b>(디랩 1029).
             */
            @NotNull(message = "자녀를 지정해 주세요.") Long studentId,
            @NotNull(message = "우선 승인자를 선택해 주세요.")
            com.dlab.domain.approval.entity.ApproverType preferred,
            @AssertTrue(message = "안내 사항에 동의해야 선택할 수 있습니다.")
            @NotNull(message = "동의 여부는 필수입니다.") Boolean agreed) {
    }

    public record Reject(
            @NotBlank(message = "반려 사유는 필수입니다.")
            @Size(max = 500, message = "반려 사유는 500자를 넘을 수 없습니다.")
            String reason) {
    }
}
