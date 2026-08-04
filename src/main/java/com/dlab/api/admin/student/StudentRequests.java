package com.dlab.api.admin.student;

import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import jakarta.validation.constraints.*;

public final class StudentRequests {

    private StudentRequests() {
    }

    /** 신규 접수. 학번은 서버가 채번하므로 받지 않는다. */
    public record Admit(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 20) String phone,
            @NotNull(message = "학년 구분은 필수입니다.") GradeType grade,
            TrackType track) {
    }

    /** 재등록 — 같은 사람에 등록 건만 추가한다. */
    public record ReEnroll(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotNull(message = "학년 구분은 필수입니다.") GradeType grade,
            TrackType track) {
    }
}
