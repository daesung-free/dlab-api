package com.dlab.api.admin.staff;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.AccountStatus;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.audit.service.ChangeLogService;
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
    private final ChangeLogService changeLogService;

    /**
     * 계정 목록 (F-4.10-2 사용자 관리).
     *
     * <p><b>사람 목록({@code /teachers}·{@code /employees})과 용도가 다르다.</b>
     * 저쪽은 담임 지정·승인자 선택용이라 이름·연락처만 주고, 이쪽은 <b>로그인 아이디·
     * 계정 상태·권한·잠금 여부</b>를 보여준다.
     *
     * <p><b>학생·학부모 계정은 안 나온다</b> — 가입 승인(F-4.12-1)에서 따로 다루고,
     * 섞으면 목록이 수백 건이 되어 관리자를 찾을 수 없다.
     *
     * @param academyId 전 지점 권한자만 의미가 있다. 비우면 전 지점
     * @param status 계정 상태 필터. 비우면 전체
     */
    @GetMapping("/accounts")
    public ApiResponse<List<StaffAccountService.AccountRow>> accounts(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) AccountStatus status) {
        return ApiResponse.success(staffAccountService.accounts(academyId, status, me));
    }

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

    /**
     * 계정 권한·상태 변경 이력 (사용자 관리 화면의 '권한 수정시간').
     *
     * <p>표 자체는 범용이라 나중에 <b>전 화면 변경 이력 조회(F-4.10-8)</b>가 같은 표를
     * 읽는다 — 지금 권한만 쌓아두면 그 화면이 생길 때 옮길 것이 없다.
     */
    @GetMapping("/accounts/{accountId}/history")
    public ApiResponse<List<ChangeLogRow>> accountHistory(@PathVariable Long accountId) {
        return ApiResponse.success(
                java.util.stream.Stream.concat(
                        changeLogService.historyOf(
                                ChangeLogService.TARGET_ACCOUNT_ROLE, accountId).stream(),
                        changeLogService.historyOf(
                                ChangeLogService.TARGET_ACCOUNT_STATUS, accountId).stream())
                        .sorted(java.util.Comparator.comparing(
                                com.dlab.domain.audit.entity.ChangeLog::getCreatedAt).reversed())
                        .map(ChangeLogRow::from).toList());
    }

    /** @param changedBy 행위자 계정 id. 배치·시스템 경로면 {@code 0}이다 */
    public record ChangeLogRow(Long id, String action, String beforeValue, String afterValue,
                               Long changedBy, java.time.Instant changedAt) {

        static ChangeLogRow from(com.dlab.domain.audit.entity.ChangeLog log) {
            return new ChangeLogRow(log.getId(), log.getAction(), log.getBeforeValue(),
                    log.getAfterValue(), log.getCreatedBy(), log.getCreatedAt());
        }
    }

    /**
     * 계정 승인 (승인대기 → 사용).
     *
     * <p>지점이 만든 계정은 승인 전까지 <b>로그인 자체가 막힌다</b>. 본사만 승인할 수
     * 있다 — 지점이 자기가 만든 계정을 스스로 승인하면 절차가 아무것도 막지 못한다.
     */
    @PostMapping("/accounts/{accountId}/approve")
    public ApiResponse<Void> approveAccount(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long accountId) {
        staffAccountService.approve(accountId, me);
        return ApiResponse.empty();
    }

    /**
     * 계정 탈퇴 처리.
     *
     * <p><b>계정을 지우지 않는다</b> — 지난 로그인 이력과 이 계정이 남긴 작업 기록이
     * 감사 대상이다. 되살릴 일이 있으면 승인으로 다시 올린다.
     */
    @PostMapping("/accounts/{accountId}/withdraw")
    public ApiResponse<Void> withdrawAccount(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long accountId) {
        staffAccountService.withdraw(accountId, me);
        return ApiResponse.empty();
    }
}
