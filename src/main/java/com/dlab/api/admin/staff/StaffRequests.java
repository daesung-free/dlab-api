package com.dlab.api.admin.staff;

import com.dlab.common.security.Role;
import jakarta.validation.constraints.*;

import java.util.Set;

public final class StaffRequests {

    private StaffRequests() {
    }

    /** 선생님(담당선생님·사감) 등록. 계정을 같이 만든다. */
    public record CreateTeacher(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 20) String phone,
            @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 128) String email,
            @NotBlank(message = "로그인 아이디는 필수입니다.") @Size(max = 50) String loginId,
            @NotEmpty(message = "역할은 하나 이상 필요합니다.") Set<Role> roles) {
    }

    /**
     * 외부 강사 등록 — <b>로그인 아이디·역할을 받지 않는다</b> (1016 피드백 2-13).
     *
     * <p>★ 계정을 만들지 않기 때문이다. 특강 한 건을 맡는 외부 강사에게 계정을 주면
     * <b>그 지점 학생 정보에 접근할 수 있는 계정이 외부에 생기고</b>, 특강이 끝난 뒤
     * 지우는 절차가 운영에 없다.
     */
    public record CreateExternalTeacher(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 20) String phone,
            @Size(max = 100) String affiliation) {
    }

    /** 직원(행정) 등록. */
    public record CreateEmployee(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 32) String deptName,
            @Size(max = 32) String positionName,
            @Size(max = 20) String phone,
            @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 128) String email,
            @NotBlank(message = "로그인 아이디는 필수입니다.") @Size(max = 50) String loginId,
            @NotEmpty(message = "역할은 하나 이상 필요합니다.") Set<Role> roles) {
    }

    /**
     * 인적사항 수정. {@code null}은 "변경하지 않음"이다.
     *
     * <p><b>로그인 아이디는 없다.</b> 계정 식별자라 바꾸면 감사 로그의 주체가 끊긴다.
     */
    public record UpdateTeacher(
            @Size(max = 20) String name,
            @Size(max = 20) String phone,
            @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 128) String email) {
    }

    public record UpdateEmployee(
            @Size(max = 20) String name,
            @Size(max = 32) String deptName,
            @Size(max = 32) String positionName,
            @Size(max = 20) String phone,
            @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 128) String email) {
    }

    public record ReplaceRoles(
            @NotEmpty(message = "역할은 하나 이상 필요합니다.") Set<Role> roles) {
    }
}
