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
