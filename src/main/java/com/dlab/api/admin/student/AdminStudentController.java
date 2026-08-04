package com.dlab.api.admin.student;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 웹 — 학생 검색·신규 접수.
 *
 * <p>지점 스코프는 {@link SearchScope}가 인증 주체에서 뽑는다. 요청 파라미터로 받으면
 * 값을 바꿔 보내는 것만으로 다른 지점 학생이 조회된다(CLAUDE.md §7).
 */
@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminStudentController {

    private final StudentService studentService;

    @GetMapping
    public ApiResponse<Page<StudentResponse>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) GradeType grade,
            @RequestParam(required = false) TrackType track,
            @RequestParam(required = false) EnrollmentStatus status,
            @RequestParam(required = false) Long classId,
            @PageableDefault(size = 20) Pageable pageable) {

        return ApiResponse.success(studentService
                .search(SearchScope.of(me, year), keyword, grade, track, status, classId, pageable)
                .map(StudentResponse::from));
    }

    @GetMapping("/{enrollmentId}")
    public ApiResponse<StudentResponse> get(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long enrollmentId) {
        return ApiResponse.success(StudentResponse.from(studentService.get(enrollmentId, me)));
    }

    /** 신규 접수. 학번은 서버가 채번한다. */
    @PostMapping
    public ApiResponse<StudentResponse> admit(@CurrentAccount AuthPrincipal me,
                                              @Valid @RequestBody StudentRequests.Admit request) {
        return ApiResponse.success(StudentResponse.from(studentService.admit(
                request.academyId(), request.year().shortValue(), request.name(),
                request.phone(), request.grade(), request.track(), me)));
    }

    /** 재등록 — 같은 사람에 등록 건만 추가한다(상담 이력이 이어져야 하므로). */
    @PostMapping("/{studentId}/re-enroll")
    public ApiResponse<StudentResponse> reEnroll(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long studentId,
                                                 @Valid @RequestBody StudentRequests.ReEnroll request) {
        return ApiResponse.success(StudentResponse.from(studentService.reEnroll(
                studentId, request.academyId(), request.year().shortValue(),
                request.grade(), request.track(), me)));
    }
}
