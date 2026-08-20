package com.dlab.api.admin.master;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.master.service.MasterDataService;
import com.dlab.domain.master.service.YearlySnapshotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 기초 관리 (학과 · 계열 · 사물함 · 장학).
 *
 * <p>학과는 지점·연도 단위라 전년도 복사 대상이고, 계열은 전 지점 공통이라
 * <b>상위 관리자만</b> 수정할 수 있다.
 */
@Tag(name = "관리자 · 기초 마스터")
@RestController
@RequestMapping("/api/v1/admin/masters")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminMasterController {

    private final MasterDataService masterDataService;
    private final YearlySnapshotService yearlySnapshotService;

    // ── 전년도 복사 ──

    /**
     * 기초 데이터를 다음 연도로 복사한다 (F-4.10-1).
     *
     * <p>여러 표를 한 트랜잭션에서 만들고 되돌릴 수 없는 양의 데이터를 생성하므로
     * <b>상위 관리자만</b> 실행할 수 있게 둔다. 대상 연도에 데이터가 있으면 409로 거부된다.
     */
    @PostMapping("/yearly-copy")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<YearlySnapshotService.SnapshotResult> copyYear(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CopyYear request) {
        return ApiResponse.success(yearlySnapshotService.copy(
                request.academyId(), request.fromYear().shortValue(), request.toYear().shortValue(), me));
    }

    // ── 학과 ──

    @GetMapping("/departments")
    public ApiResponse<List<MasterResponses.Department>> departments(
            @CurrentAccount AuthPrincipal me, @RequestParam(required = false) Integer year) {
        return ApiResponse.success(masterDataService.departments(SearchScope.of(me, year)).stream()
                .map(MasterResponses.Department::from).toList());
    }

    @PostMapping("/departments")
    public ApiResponse<MasterResponses.Department> createDepartment(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CreateDepartment request) {
        return ApiResponse.success(MasterResponses.Department.from(masterDataService.createDepartment(
                request.academyId(), request.year().shortValue(), request.name(), me)));
    }

    @PutMapping("/departments/{id}")
    public ApiResponse<MasterResponses.Department> renameDepartment(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Department.from(
                masterDataService.renameDepartment(id, request.name(), me)));
    }

    @DeleteMapping("/departments/{id}")
    public ApiResponse<Void> deleteDepartment(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteDepartment(id, me);
        return ApiResponse.empty();
    }

    // ── 과정 ──

    @GetMapping("/course-types")
    public ApiResponse<List<MasterResponses.CourseType>> courseTypes(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(
                masterDataService.courseTypes(academyId, year.shortValue(), me).stream()
                        .map(MasterResponses.CourseType::from).toList());
    }

    @PostMapping("/course-types")
    public ApiResponse<MasterResponses.CourseType> createCourseType(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateNamedMaster request) {
        return ApiResponse.success(MasterResponses.CourseType.from(masterDataService.createCourseType(
                request.academyId(), request.year().shortValue(), request.name(),
                request.sortOrderOrZero(), me)));
    }

    @PutMapping("/course-types/{id}")
    public ApiResponse<MasterResponses.CourseType> renameCourseType(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.CourseType.from(
                masterDataService.renameCourseType(id, request.name(), me)));
    }

    @DeleteMapping("/course-types/{id}")
    public ApiResponse<Void> deleteCourseType(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteCourseType(id, me);
        return ApiResponse.empty();
    }


    // ── 교습비 ──

    @GetMapping("/tuitions")
    public ApiResponse<List<MasterResponses.Tuition>> tuitions(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(masterDataService.tuitions(academyId, year.shortValue(), me).stream()
                .map(MasterResponses.Tuition::from).toList());
    }

    @PostMapping("/tuitions")
    public ApiResponse<MasterResponses.Tuition> createTuition(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(masterDataService.createTuition(
                request.academyId(), request.year().shortValue(), request.name(),
                request.amount(), request.sortOrderOrZero(), me)));
    }

    @PatchMapping("/tuitions/{id}")
    public ApiResponse<MasterResponses.Tuition> updateTuition(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.UpdateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(
                masterDataService.updateTuition(id, request.name(), request.amount(), me)));
    }

    @DeleteMapping("/tuitions/{id}")
    public ApiResponse<Void> deleteTuition(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteTuition(id, me);
        return ApiResponse.empty();
    }

    // ── 커리큘럼 ──

    @GetMapping("/curriculums")
    public ApiResponse<List<MasterResponses.Curriculum>> curriculums(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(
                masterDataService.curriculums(academyId, year.shortValue(), me).stream()
                        .map(MasterResponses.Curriculum::from).toList());
    }

    @PostMapping("/curriculums")
    public ApiResponse<MasterResponses.Curriculum> createCurriculum(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateCurriculum request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.createCurriculum(request.academyId(), request.year().shortValue(),
                        request.name(), request.classId(), request.sortOrderOrZero(), me)));
    }

    @PutMapping("/curriculums/{id}")
    public ApiResponse<MasterResponses.Curriculum> renameCurriculum(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.renameCurriculum(id, request.name(), me)));
    }

    @DeleteMapping("/curriculums/{id}")
    public ApiResponse<Void> deleteCurriculum(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteCurriculum(id, me);
        return ApiResponse.empty();
    }

    // ── 계열 (전 지점 공통) ──

    @GetMapping("/tracks")
    public ApiResponse<List<MasterResponses.Track>> tracks() {
        return ApiResponse.success(masterDataService.tracks().stream()
                .map(MasterResponses.Track::from).toList());
    }

    /** 전 지점에 영향을 주므로 상위 관리자만 추가할 수 있다. */
    @PostMapping("/tracks")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<MasterResponses.Track> createTrack(
            @Valid @RequestBody MasterRequests.CreateTrack request) {
        return ApiResponse.success(MasterResponses.Track.from(
                masterDataService.createTrack(request.name())));
    }

    // ── 사물함 ──

    @GetMapping("/lockers")
    public ApiResponse<List<MasterResponses.Locker>> lockers(@CurrentAccount AuthPrincipal me,
                                                             @RequestParam Long academyId) {
        return ApiResponse.success(masterDataService.lockers(academyId, me).stream()
                .map(MasterResponses.Locker::from).toList());
    }

    @PostMapping("/lockers")
    public ApiResponse<MasterResponses.Locker> createLocker(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CreateLocker request) {
        return ApiResponse.success(MasterResponses.Locker.from(
                masterDataService.createLocker(request.academyId(), request.lockerNo(), me)));
    }

    @PutMapping("/lockers/{id}/assignment")
    public ApiResponse<MasterResponses.Locker> assignLocker(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.AssignLocker request) {
        return ApiResponse.success(MasterResponses.Locker.from(
                masterDataService.assignLocker(id, request.enrollmentId(), me)));
    }

    @DeleteMapping("/lockers/{id}/assignment")
    public ApiResponse<Void> releaseLocker(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.releaseLocker(id, me);
        return ApiResponse.empty();
    }

    // ── 장학 ──

    @GetMapping("/scholarships")
    public ApiResponse<List<MasterResponses.ScholarshipItem>> scholarships(@RequestParam Long enrollmentId) {
        return ApiResponse.success(masterDataService.scholarshipsOf(enrollmentId).stream()
                .map(MasterResponses.ScholarshipItem::from).toList());
    }

    @PostMapping("/scholarships")
    public ApiResponse<MasterResponses.ScholarshipItem> grantScholarship(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.GrantScholarship request) {
        return ApiResponse.success(MasterResponses.ScholarshipItem.from(
                masterDataService.grantScholarship(request.enrollmentId(), request.scholarshipType(),
                        request.discountRate(), me)));
    }

    @DeleteMapping("/scholarships/{id}")
    public ApiResponse<Void> revokeScholarship(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.revokeScholarship(id, me);
        return ApiResponse.empty();
    }
}
