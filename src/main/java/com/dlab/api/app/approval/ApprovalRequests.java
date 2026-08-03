package com.dlab.api.app.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ApprovalRequests {

    private ApprovalRequests() {
    }

    public record Reject(
            @NotBlank(message = "반려 사유는 필수입니다.")
            @Size(max = 500, message = "반려 사유는 500자를 넘을 수 없습니다.")
            String reason) {
    }
}
