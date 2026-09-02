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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 반 관리.
 *
 * <p><b>담임을 지정하면 그 반 학생들의 승인 에스컬레이션 대상이 자동으로 정해진다.</b>
 * 학생별 승인자 지정 화면을 따로 만들지 않는 이유다(CLAUDE.md §3).
 *
 * <p>지점 스코프는 {@link SearchScope}가 인증 주체에서 뽑는다 — 요청 파라미터로 받으면
 * 값을 바꿔 보내는 것만으로 다른 지점 데이터가 새어나간다.
 */
@Tag(name = "관리자 · 반 관리")
@RestController
@RequestMapping("/api/v1/admin/classes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminClassController {

    private final ClassService classService;

    /**
     * 반 목록 — 정원·현재 인원 포함 (F-4.1-5 "고정반목록(계열/학과/담임/정원/원생수)").
     *
     * <p>인원수는 <b>집계 한 번</b>으로 붙는다. 반마다 명단을 부르면 쿼리가 반 개수만큼
     * 나가므로 화면이 그렇게 하지 않아도 되게 여기서 실어 보낸다.
     */
    @GetMapping
    public ApiResponse<List<ClassResponse>> search(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Integer year) {
        return ApiResponse.success(classService.searchWithMemberCount(SearchScope.of(me, year)).stream()
                .map(ClassResponse::from)
                .toList());
    }

    /** 그 반 학생 명단. */
    @GetMapping("/{classId}/students")
    public ApiResponse<List<ClassResponse.Member>> students(@CurrentAccount AuthPrincipal me,
                                                            @PathVariable Long classId) {
        return ApiResponse.success(classService.studentsOf(classId, me).stream()
                .map(ClassResponse.Member::from)
                .toList());
    }

    /** 반 생성. */
    @PostMapping
    public ApiResponse<ClassResponse> create(@CurrentAccount AuthPrincipal me,
                                             @Valid @RequestBody ClassRequests.ClassCreate request) {
        return ApiResponse.success(ClassResponse.from(classService.create(
                request.academyId(), request.year().shortValue(), request.name(),
                request.classType(), request.homeroomTeacherId(), request.capacity(), me)));
    }

    /** 반 기본정보 수정(이름·정원). 담임은 아래 {@code /homeroom}이 담당한다. */
    @PutMapping("/{classId}")
    public ApiResponse<ClassResponse> update(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long classId,
                                             @Valid @RequestBody ClassRequests.ClassUpdate request) {
        return ApiResponse.success(ClassResponse.from(classService.update(
                classId, request.name(), request.capacity(), request.clearsCapacity(), me)));
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

    /**
     * 학생 일괄 배정 — <b>건별 결과</b>를 돌려준다.
     *
     * <p>한 명이 틀렸다고 전부 되돌리면 운영자는 <b>누가 문제였는지 모른 채</b> 처음부터
     * 다시 골라야 한다. 실패 사유가 전부 그 학생 한 명에 대한 검증이라 부분 성공이
     * 다른 학생의 결과를 흐리지 않는다.
     *
     * <p><b>정원을 넘겨도 배정된다</b> — 정원 초과가 필요한 운영이 실제로 있다.
     * 대신 응답의 {@code overCapacity}로 알린다.
     */
    @PostMapping("/{classId}/students/bulk")
    public ApiResponse<ClassResponse.BulkAssign> assignStudents(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long classId,
            @Valid @RequestBody ClassRequests.AssignStudents request) {
        return ApiResponse.success(ClassResponse.BulkAssign.from(
                classService.assignStudents(classId, request.enrollmentIds(), me)));
    }

    /**
     * 반 배정 해제 (좌석 {@code DELETE /seats/students/{enrollmentId}}와 같은 결).
     *
     * <p>행을 지우지 않고 비활성으로 내린다 — 배정은 이력이다.
     * 반을 경로에 함께 받는 이유는 학생 하나에 고정반·이동수업반이 동시에 있을 수 있어서다.
     */
    @DeleteMapping("/{classId}/students/{enrollmentId}")
    public ApiResponse<Void> releaseStudent(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long classId,
                                            @PathVariable Long enrollmentId) {
        classService.releaseStudent(classId, enrollmentId, me);
        return ApiResponse.empty();
    }
}
