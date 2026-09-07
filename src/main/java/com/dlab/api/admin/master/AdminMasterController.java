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

    /** 학과 목록. 지점·연도 범위가 자동으로 걸린다({@code SearchScope}). */
    @GetMapping("/departments")
    public ApiResponse<List<MasterResponses.Department>> departments(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Integer year) {
        return ApiResponse.success(masterDataService
                .departments(SearchScope.of(me, year, academyId)).stream()
                .map(MasterResponses.Department::from).toList());
    }

    /** 학과 등록. 지점·연도 단위라 <b>전년도 복사 대상</b>이다. */
    @PostMapping("/departments")
    public ApiResponse<MasterResponses.Department> createDepartment(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CreateDepartment request) {
        return ApiResponse.success(MasterResponses.Department.from(masterDataService.createDepartment(
                request.academyId(), request.year().shortValue(), request.name(), me)));
    }

    /** 학과 이름 변경. 학생 배정은 그대로 유지된다. */
    @PutMapping("/departments/{id}")
    public ApiResponse<MasterResponses.Department> renameDepartment(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Department.from(
                masterDataService.renameDepartment(id, request.name(), me)));
    }

    /**
         * 학과 삭제(soft).
         *
         * <p><b>물리 삭제하지 않는다</b> — 과거 학생이 어느 학과였는지가 이력으로 남아야 한다.
         */
    @DeleteMapping("/departments/{id}")
    public ApiResponse<Void> deleteDepartment(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteDepartment(id, me);
        return ApiResponse.empty();
    }

    // ── 과정 ──

    /** 과정 목록(정규·단과 등). */
    @GetMapping("/course-types")
    public ApiResponse<List<MasterResponses.CourseType>> courseTypes(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(
                masterDataService.courseTypes(academyId, year.shortValue(), me).stream()
                        .map(MasterResponses.CourseType::from).toList());
    }

    /** 과정 등록. 학과와 마찬가지로 지점·연도 단위다. */
    @PostMapping("/course-types")
    public ApiResponse<MasterResponses.CourseType> createCourseType(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateNamedMaster request) {
        return ApiResponse.success(MasterResponses.CourseType.from(masterDataService.createCourseType(
                request.academyId(), request.year().shortValue(), request.name(),
                request.sortOrderOrZero(), me)));
    }

    /** 과정 이름 변경. */
    @PutMapping("/course-types/{id}")
    public ApiResponse<MasterResponses.CourseType> renameCourseType(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.CourseType.from(
                masterDataService.renameCourseType(id, request.name(), me)));
    }

    /** 과정 삭제(soft). 과거 등록 건이 참조하므로 지우지 않는다. */
    @DeleteMapping("/course-types/{id}")
    public ApiResponse<Void> deleteCourseType(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteCourseType(id, me);
        return ApiResponse.empty();
    }


    // ── 교습비 ──

    /**
         * 청구기준(교습비 항목) 목록.
         *
         * <p>0820 규정으로 들어온 <b>실제 가격표는 {@code /api/v1/admin/tuition/prices}</b>에 있다.
         * 여기는 기초관리 쪽 청구 항목이라 축이 다르다 — 합치지 말 것.
         */
    @GetMapping("/tuitions")
    public ApiResponse<List<MasterResponses.Tuition>> tuitions(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(masterDataService.tuitions(academyId, year.shortValue(), me).stream()
                .map(MasterResponses.Tuition::from).toList());
    }

    /** 청구기준 등록. */
    @PostMapping("/tuitions")
    public ApiResponse<MasterResponses.Tuition> createTuition(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(masterDataService.createTuition(
                request.academyId(), request.year().shortValue(), request.name(),
                request.amount(), request.sortOrderOrZero(), me)));
    }

    /**
         * 청구기준 수정. <b>이미 발행된 청구액은 바뀌지 않는다</b> —
         * {@code billing}이 발행 시점 금액을 저장해 둔다.
         */
    @PatchMapping("/tuitions/{id}")
    public ApiResponse<MasterResponses.Tuition> updateTuition(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.UpdateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(
                masterDataService.updateTuition(id, request.name(), request.amount(), me)));
    }

    /** 청구기준 삭제(soft). */
    @DeleteMapping("/tuitions/{id}")
    public ApiResponse<Void> deleteTuition(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteTuition(id, me);
        return ApiResponse.empty();
    }

    // ── 커리큘럼 ──

    /** 커리큘럼 목록. */
    @GetMapping("/curriculums")
    public ApiResponse<List<MasterResponses.Curriculum>> curriculums(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId, @RequestParam Integer year) {
        return ApiResponse.success(
                masterDataService.curriculums(academyId, year.shortValue(), me).stream()
                        .map(MasterResponses.Curriculum::from).toList());
    }

    /** 커리큘럼 등록. 전년도 복사 대상이다. */
    @PostMapping("/curriculums")
    public ApiResponse<MasterResponses.Curriculum> createCurriculum(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateCurriculum request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.createCurriculum(request.academyId(), request.year().shortValue(),
                        request.name(), request.classId(), request.sortOrderOrZero(), me)));
    }

    /** 커리큘럼 이름 변경. */
    @PutMapping("/curriculums/{id}")
    public ApiResponse<MasterResponses.Curriculum> renameCurriculum(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.renameCurriculum(id, request.name(), me)));
    }

    /** 커리큘럼 삭제(soft). */
    @DeleteMapping("/curriculums/{id}")
    public ApiResponse<Void> deleteCurriculum(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteCurriculum(id, me);
        return ApiResponse.empty();
    }

    // ── 계열 (전 지점 공통) ──

    /**
         * 계열 목록(인문·자연·예체능).
         *
         * <p><b>전 지점 공통</b>이라 지점 범위가 걸리지 않는다.
         */
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

    /** 사물함 목록. 배정 여부가 함께 내려온다. */
    @GetMapping("/lockers")
    public ApiResponse<List<MasterResponses.Locker>> lockers(@CurrentAccount AuthPrincipal me,
                                                             @RequestParam Long academyId) {
        return ApiResponse.success(masterDataService.lockers(academyId, me).stream()
                .map(MasterResponses.Locker::from).toList());
    }

    /** 사물함 등록. */
    @PostMapping("/lockers")
    public ApiResponse<MasterResponses.Locker> createLocker(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CreateLocker request) {
        return ApiResponse.success(MasterResponses.Locker.from(
                masterDataService.createLocker(request.academyId(), request.lockerNo(), me)));
    }

    /**
         * 사물함 배정.
         *
         * <p>이미 배정된 사물함이면 409로 거부된다 — 한 칸에 두 명이 들어가면
         * <b>둘 중 누가 실제로 쓰는지 시스템이 모른다.</b>
         */
    @PutMapping("/lockers/{id}/assignment")
    public ApiResponse<MasterResponses.Locker> assignLocker(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.AssignLocker request) {
        return ApiResponse.success(MasterResponses.Locker.from(
                masterDataService.assignLocker(id, request.enrollmentId(), me)));
    }

    /** 사물함 배정 해제. 사물함 자체는 남는다 — 다음 학생이 쓴다. */
    @DeleteMapping("/lockers/{id}/assignment")
    public ApiResponse<Void> releaseLocker(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.releaseLocker(id, me);
        return ApiResponse.empty();
    }

    // ── 장학 ──

    /**
         * 그 학생의 장학 내역.
         *
         * <p>취소 <b>판정</b>은 여기가 아니라 {@code /api/v1/admin/scholarship-reviews}다 —
         * 자동 판정 결과를 사람이 확정하는 흐름이라 축이 다르다.
         */
    @GetMapping("/scholarships")
    public ApiResponse<List<MasterResponses.ScholarshipItem>> scholarships(@RequestParam Long enrollmentId) {
        return ApiResponse.success(masterDataService.scholarshipsOf(enrollmentId).stream()
                .map(MasterResponses.ScholarshipItem::from).toList());
    }

    /**
         * 장학 부여.
         *
         * <p>할인율이 여기서 정해지고, <b>퇴원 시 소급 재결제가 이 값을 되받는다</b>
         * (0820 규정 — 할인 기간 안에서 퇴원하면 정상가로 재결제).
         */
    @PostMapping("/scholarships")
    public ApiResponse<MasterResponses.ScholarshipItem> grantScholarship(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.GrantScholarship request) {
        return ApiResponse.success(MasterResponses.ScholarshipItem.from(
                masterDataService.grantScholarship(request.enrollmentId(), request.scholarshipType(),
                        request.discountRate(), me)));
    }

    /**
         * 장학 해제.
         *
         * <p><b>이미 발행된 청구의 할인은 되돌아가지 않는다</b> — 그건 퇴원 정산에서
         * 소급 재결제로 처리한다. 여기는 "앞으로 할인을 주지 않는다"는 뜻이다.
         */
    @DeleteMapping("/scholarships/{id}")
    public ApiResponse<Void> revokeScholarship(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.revokeScholarship(id, me);
        return ApiResponse.empty();
    }
}
