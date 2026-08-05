package com.dlab.api.admin.clazz;

import com.dlab.domain.user.entity.ClassType;
import jakarta.validation.constraints.*;

public final class ClassRequests {

    private ClassRequests() {
    }

    public record Create(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "반 이름은 필수입니다.") @Size(max = 50) String name,
            @NotNull(message = "반 유형은 필수입니다.") ClassType classType,
            /** 미지정 가능 — 나중에 담임 지정 API로 붙일 수 있다. */
            Long homeroomTeacherId) {
    }

    public record AssignHomeroom(
            @NotNull(message = "선생님은 필수입니다.") Long teacherId) {
    }

    public record AssignStudent(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }
}
