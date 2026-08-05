package com.dlab.api.admin.clazz;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.service.ClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 웹 — 반 관리.
 *
 * <p><b>담임을 지정하면 그 반 학생들의 승인 에스컬레이션 대상이 자동으로 정해진다.</b>
 * 학생별 승인자 지정 화면을 따로 만들지 않는 이유다(CLAUDE.md §3).
 *
 * <p>지점 스코프는 {@link SearchScope}가 인증 주체에서 뽑는다 — 요청 파라미터로 받으면
 * 값을 바꿔 보내는 것만으로 다른 지점 데이터가 새어나간다.
 */
@RestController
@RequestMapping("/api/v1/admin/classes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminClassController {

    private final ClassService classService;

    @GetMapping
    public ApiResponse<List<ClassResponse>> search(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Integer year) {
        return ApiResponse.success(classService.search(SearchScope.of(me, year)).stream()
                .map(ClassResponse::from)
                .toList());
    }

    @GetMapping("/{classId}/students")
    public ApiResponse<List<ClassResponse.Member>> students(@CurrentAccount AuthPrincipal me,
                                                            @PathVariable Long classId) {
        return ApiResponse.success(classService.studentsOf(classId, me).stream()
                .map(ClassResponse.Member::from)
                .toList());
    }

    @PostMapping
    public ApiResponse<ClassResponse> create(@CurrentAccount AuthPrincipal me,
                                             @Valid @RequestBody ClassRequests.Create request) {
        return ApiResponse.success(ClassResponse.from(classService.create(
                request.academyId(), request.year().shortValue(), request.name(),
                request.classType(), request.homeroomTeacherId(), me)));
    }

    /** 담임 지정·변경. 이미 처리된 승인 건은 스냅샷이라 영향받지 않는다. */
    @PutMapping("/{classId}/homeroom")
    public ApiResponse<ClassResponse> assignHomeroom(@CurrentAccount AuthPrincipal me,
                                                     @PathVariable Long classId,
                                                     @Valid @RequestBody ClassRequests.AssignHomeroom request) {
        return ApiResponse.success(
                ClassResponse.from(classService.assignHomeroom(classId, request.teacherId(), me)));
    }

    /** 학생 배정. 기존 배정은 비활성으로 내려가고 이력이 남는다. */
    @PostMapping("/{classId}/students")
    public ApiResponse<ClassResponse.Member> assignStudent(@CurrentAccount AuthPrincipal me,
                                                           @PathVariable Long classId,
                                                           @Valid @RequestBody ClassRequests.AssignStudent request) {
        return ApiResponse.success(ClassResponse.Member.from(
                classService.assignStudent(classId, request.enrollmentId(), me)));
    }
}
