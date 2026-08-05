package com.dlab.api.admin.student;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

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

    /**
     * 정보 수정. <b>보내지 않은(= {@code null}) 필드는 변경하지 않는다.</b>
     *
     * <p>학번은 여기 없다 — 서버가 채번하고 유니크 제약으로 지키는 값이라 임의 수정을 열면
     * 중복·건너뜀이 생긴다.
     */
    public record Update(
            @Size(max = 20) String name,
            @Size(max = 20) String phone,
            LocalDate birthDate,
            @Size(max = 1) String gender,
            @Size(max = 64) String schoolName,
            @Size(max = 200) String address,
            GradeType grade,
            TrackType track,
            EnrollmentStatus status) {
    }

    /**
     * 재적 상태 변경.
     *
     * <p>사유는 필수가 아니지만 <b>제적처럼 다툼이 생길 수 있는 전이</b>에는 남겨야 한다.
     */
    public record ChangeStatus(
            @NotNull(message = "변경할 상태는 필수입니다.") EnrollmentStatus status,
            @Size(max = 200) String reason) {
    }

    /** 검색조건 저장. {@code conditions}는 화면이 만든 JSON 문자열 그대로 — 서버가 파싱하지 않는다. */
    public record SaveSearch(
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 50) String name,
            @NotBlank(message = "검색조건은 필수입니다.") @Size(max = 4000) String conditions) {
    }

    /** 재등록 — 같은 사람에 등록 건만 추가한다. */
    public record ReEnroll(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotNull(message = "학년 구분은 필수입니다.") GradeType grade,
            TrackType track) {
    }
}
