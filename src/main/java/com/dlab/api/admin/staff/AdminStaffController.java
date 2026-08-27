package com.dlab.api.admin.staff;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.service.StaffAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 직원·선생님 계정과 권한 관리 (F-4.10-2).
 *
 * <p>선생님과 직원을 경로부터 나눈다 — 담임·승인 에스컬레이션 자격이 선생님에게만 있고,
 * 그 구분이 API 표면에서도 드러나야 잘못 등록하는 일이 줄어든다.
 */
@Tag(name = "관리자 · 직원·강사")
@RestController
@RequestMapping("/api/v1/admin/staff")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminStaffController {

    private final StaffAccountService staffAccountService;

    /** 담당선생님(사감) 목록. 반 담임·승인 에스컬레이션 대상이 여기서 나온다. */
    @GetMapping("/teachers")
    public ApiResponse<List<StaffResponse>> teachers(@CurrentAccount AuthPrincipal me,
                                                     @RequestParam Long academyId) {
        return ApiResponse.success(staffAccountService.teachers(academyId, me).stream()
                .map(StaffResponse::from).toList());
    }

    /** 행정선생님 목록. 학생 가입 승인·전체공지 작성이 이쪽이다. */
    @GetMapping("/employees")
    public ApiResponse<List<StaffResponse>> employees(@CurrentAccount AuthPrincipal me,
                                                      @RequestParam Long academyId) {
        return ApiResponse.success(staffAccountService.employees(academyId, me).stream()
                .map(StaffResponse::from).toList());
    }

    /**
     * 담당선생님 등록.
     *
     * <p>행정선생님과 <b>테이블이 나뉘어 있다</b> — 그래서 반 담임·승인자 FK가
     * 곧 "담당선생님 보장"이 된다. 합치면 배정할 때마다 역할을 검사해야 한다.
     */
    @PostMapping("/teachers")
    public ApiResponse<StaffResponse> createTeacher(@CurrentAccount AuthPrincipal me,
                                                    @Valid @RequestBody StaffRequests.CreateTeacher request) {
        return ApiResponse.success(StaffResponse.from(staffAccountService.createTeacher(
                request.academyId(), request.name(), request.phone(), request.email(),
                request.loginId(), request.password(), request.roles(), me)));
    }

    /** 행정선생님 등록. */
    @PostMapping("/employees")
    public ApiResponse<StaffResponse> createEmployee(@CurrentAccount AuthPrincipal me,
                                                     @Valid @RequestBody StaffRequests.CreateEmployee request) {
        return ApiResponse.success(StaffResponse.from(staffAccountService.createEmployee(
                request.academyId(), request.name(), request.deptName(), request.positionName(),
                request.phone(), request.email(), request.loginId(), request.password(),
                request.roles(), me)));
    }

    /**
     * 역할 재설정.
     *
     * <p>이미 발급된 Access Token에는 옛 역할이 남아 있다 — 만료되거나 재발급받아야 반영된다.
     */
    @PutMapping("/accounts/{accountId}/roles")
    public ApiResponse<Set<String>> replaceRoles(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long accountId,
                                                 @Valid @RequestBody StaffRequests.ReplaceRoles request) {
        staffAccountService.replaceRoles(accountId, request.roles(), me);
        return ApiResponse.success(staffAccountService.rolesOf(accountId));
    }
}
