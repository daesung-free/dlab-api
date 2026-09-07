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
    private final com.dlab.domain.master.service.ScholarshipMasterService scholarshipMasterService;

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
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(masterDataService
                .departments(SearchScope.of(me, year, academyId), active).stream()
                .map(MasterResponses.Department::from).toList());
    }

    /** 학과 등록. 지점·연도 단위라 <b>전년도 복사 대상</b>이다. */
    @PostMapping("/departments")
    public ApiResponse<MasterResponses.Department> createDepartment(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody MasterRequests.CreateDepartment request) {
        return ApiResponse.success(MasterResponses.Department.from(masterDataService.createDepartment(
                request.academyId(), request.year().shortValue(), request.name(),
                request.code(), request.memo(), me)));
    }

    /** 학과 이름·코드·비고 변경. 학생 배정은 그대로 유지된다. */
    @PutMapping("/departments/{id}")
    public ApiResponse<MasterResponses.Department> renameDepartment(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Department.from(masterDataService
                .renameDepartment(id, request.name(), request.code(), request.memo(), me)));
    }

    /**
     * 학과 사용/중지.
     *
     * <p><b>삭제와 다르다</b> — 중지는 "새로 고를 수 없다"는 뜻이고 이미 그 학과인
     * 학생은 그대로 남는다.
     */
    @PatchMapping("/departments/{id}/active")
    public ApiResponse<MasterResponses.Department> changeDepartmentActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.Department.from(
                masterDataService.changeDepartmentActive(id, request.active(), me)));
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
            @RequestParam Long academyId, @RequestParam Integer year,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(
                masterDataService.courseTypes(academyId, year.shortValue(), active, me).stream()
                        .map(MasterResponses.CourseType::from).toList());
    }

    /** 과정 등록. 학과와 마찬가지로 지점·연도 단위다. */
    @PostMapping("/course-types")
    public ApiResponse<MasterResponses.CourseType> createCourseType(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateNamedMaster request) {
        return ApiResponse.success(MasterResponses.CourseType.from(masterDataService.createCourseType(
                request.academyId(), request.year().shortValue(), request.name(),
                request.code(), request.memo(), request.sortOrderOrZero(), me)));
    }

    /** 과정 이름·코드·비고 변경. */
    @PutMapping("/course-types/{id}")
    public ApiResponse<MasterResponses.CourseType> renameCourseType(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.CourseType.from(masterDataService
                .renameCourseType(id, request.name(), request.code(), request.memo(), me)));
    }

    /** 과정 사용/중지. 이 과정을 쓰는 반은 그대로 남는다. */
    @PatchMapping("/course-types/{id}/active")
    public ApiResponse<MasterResponses.CourseType> changeCourseTypeActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.CourseType.from(
                masterDataService.changeCourseTypeActive(id, request.active(), me)));
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
            @RequestParam Long academyId, @RequestParam Integer year,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(
                masterDataService.tuitions(academyId, year.shortValue(), active, me).stream()
                        .map(MasterResponses.Tuition::from).toList());
    }

    /** 청구기준 등록. */
    @PostMapping("/tuitions")
    public ApiResponse<MasterResponses.Tuition> createTuition(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(masterDataService.createTuition(
                request.academyId(), request.year().shortValue(), request.name(),
                request.amount(), request.code(), request.memo(), request.sortOrderOrZero(), me)));
    }

    /**
         * 청구기준 수정. <b>이미 발행된 청구액은 바뀌지 않는다</b> —
         * {@code billing}이 발행 시점 금액을 저장해 둔다.
         */
    @PatchMapping("/tuitions/{id}")
    public ApiResponse<MasterResponses.Tuition> updateTuition(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.UpdateTuition request) {
        return ApiResponse.success(MasterResponses.Tuition.from(masterDataService
                .updateTuition(id, request.name(), request.amount(),
                        request.code(), request.memo(), me)));
    }

    /** 청구기준 사용/중지. 이미 발행된 청구는 그대로 남는다. */
    @PatchMapping("/tuitions/{id}/active")
    public ApiResponse<MasterResponses.Tuition> changeTuitionActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.Tuition.from(
                masterDataService.changeTuitionActive(id, request.active(), me)));
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
            @RequestParam Long academyId, @RequestParam Integer year,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(
                masterDataService.curriculums(academyId, year.shortValue(), active, me).stream()
                        .map(MasterResponses.Curriculum::from).toList());
    }

    /** 커리큘럼 등록. 전년도 복사 대상이다. */
    @PostMapping("/curriculums")
    public ApiResponse<MasterResponses.Curriculum> createCurriculum(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateCurriculum request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.createCurriculum(request.academyId(), request.year().shortValue(),
                        request.name(), request.classId(), request.code(), request.memo(),
                        request.sortOrderOrZero(), me)));
    }

    /** 커리큘럼 이름 변경. */
    @PutMapping("/curriculums/{id}")
    public ApiResponse<MasterResponses.Curriculum> renameCurriculum(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.Rename request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(masterDataService
                .renameCurriculum(id, request.name(), request.code(), request.memo(), me)));
    }

    /**
     * 커리큘럼 사용/중지.
     *
     * <p>서비스에는 있었는데 <b>엔드포인트만 안 뚫려 있었다</b> — 응답에 {@code active}가
     * 내려가니 화면은 토글을 그렸는데 누르면 부를 곳이 없었다.
     */
    @PatchMapping("/curriculums/{id}/active")
    public ApiResponse<MasterResponses.Curriculum> changeCurriculumActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.Curriculum.from(
                masterDataService.changeCurriculumActive(id, request.active(), me)));
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
         *
         * @param active 상태 필터. 비우면 사용중 + 중지 전부
         */
    @GetMapping("/tracks")
    public ApiResponse<List<MasterResponses.Track>> tracks(
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(masterDataService.tracks(active).stream()
                .map(MasterResponses.Track::from).toList());
    }

    /** 전 지점에 영향을 주므로 상위 관리자만 추가할 수 있다. */
    @PostMapping("/tracks")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<MasterResponses.Track> createTrack(
            @Valid @RequestBody MasterRequests.CreateTrack request) {
        return ApiResponse.success(MasterResponses.Track.from(masterDataService.createTrack(
                request.name(), request.code(), request.memo())));
    }

    /** 계열 이름·코드·비고 변경. 전 지점에 걸리므로 상위 관리자만 가능하다. */
    @PutMapping("/tracks/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<MasterResponses.Track> renameTrack(
            @PathVariable Long id, @Valid @RequestBody MasterRequests.RenameTrack request) {
        return ApiResponse.success(MasterResponses.Track.from(masterDataService
                .renameTrack(id, request.name(), request.code(), request.memo())));
    }

    /**
     * 계열 사용/중지.
     *
     * <p><b>삭제와 다르다</b> — 중지는 "새로 고를 수 없다"는 뜻이고 그 계열이었던
     * 데이터는 그대로 남는다.
     */
    @PatchMapping("/tracks/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<MasterResponses.Track> changeTrackActive(
            @PathVariable Long id, @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.Track.from(
                masterDataService.changeTrackActive(id, request.active())));
    }

    /**
     * 계열 삭제(soft).
     *
     * <p>이게 없어서 <b>한번 만든 계열을 화면에서 지울 방법이 없었다</b>. 대부분의
     * 경우는 삭제가 아니라 중지가 맞고, 이건 오타로 만든 행을 치우는 용도다.
     */
    @DeleteMapping("/tracks/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> deleteTrack(@PathVariable Long id) {
        masterDataService.deleteTrack(id);
        return ApiResponse.empty();
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
     * 사물함 블록 일괄 등록 — {@code L-} + 1~120 → {@code L-001 … L-120}.
     *
     * <p>사물함은 블록 단위로 들어온다. 한 칸씩 120번 등록하게 두면 아무도 안 쓴다.
     *
     * <p><b>이미 있는 번호는 건너뛰고 계속한다.</b> 응답의 {@code skipped}에 그 번호가
     * 담기니 화면이 "몇 개는 이미 있었다"를 알려야 한다.
     */
    @PostMapping("/lockers/bulk")
    public ApiResponse<MasterResponses.LockerBlock> createLockerBlock(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateLockerBlock request) {
        return ApiResponse.success(MasterResponses.LockerBlock.from(
                masterDataService.createLockerBlock(request.academyId(), request.prefixOrEmpty(),
                        request.startNo(), request.endNo(), request.digitsOrDefault(), me)));
    }

    /** 사물함 번호 변경. <b>배정은 그대로 유지된다</b> — 칸 이름표만 바꾸는 것이다. */
    @PutMapping("/lockers/{id}")
    public ApiResponse<MasterResponses.Locker> renameLocker(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.RenameLocker request) {
        return ApiResponse.success(MasterResponses.Locker.from(
                masterDataService.renameLocker(id, request.lockerNo(), me)));
    }

    /**
     * 사물함 삭제(soft).
     *
     * <p><b>배정된 사물함은 409다</b> — 지우면 그 학생 배정이 붕 뜬다. 해제가 먼저다.
     */
    @DeleteMapping("/lockers/{id}")
    public ApiResponse<Void> deleteLocker(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteLocker(id, me);
        return ApiResponse.empty();
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

    // ── 강의실 ──

    /**
     * 강의실 목록 (F-4.10-1 기초관리).
     *
     * <p><b>자습 구역({@code /admin/seats} 쪽)과 다르다</b> — 자습 구역은 좌석이 속하는
     * 단위이고 키오스크 계약에 걸려 있다. 여기는 수업 공간이라 키오스크와 무관하다.
     *
     * @param active 상태 필터. 비우면 사용중 + 중지 전부
     */
    @GetMapping("/rooms")
    public ApiResponse<List<MasterResponses.Room>> rooms(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.success(masterDataService.rooms(academyId, active, me).stream()
                .map(MasterResponses.Room::from).toList());
    }

    @PostMapping("/rooms")
    public ApiResponse<MasterResponses.Room> createRoom(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateRoom request) {
        return ApiResponse.success(MasterResponses.Room.from(masterDataService.createRoom(
                request.academyId(), request.roomNo(), request.name(), request.capacity(),
                request.memo(), me)));
    }

    @PutMapping("/rooms/{id}")
    public ApiResponse<MasterResponses.Room> updateRoom(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.UpdateRoom request) {
        return ApiResponse.success(MasterResponses.Room.from(masterDataService.updateRoom(
                id, request.roomNo(), request.name(), request.capacity(), request.memo(), me)));
    }

    /**
     * 강의실 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * <p>위 {@code PUT}은 전체 교체라 이름 하나 바꾸려 해도 나머지를 다 실어 보내야 하고,
     * 그 사이 남이 바꾼 값을 덮어쓴다. 화면은 이쪽을 쓰는 게 안전하다.
     */
    @PatchMapping("/rooms/{id}")
    public ApiResponse<MasterResponses.Room> patchRoom(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.PatchRoom request) {
        return ApiResponse.success(MasterResponses.Room.from(masterDataService.patchRoom(
                id, request.roomNo(), request.name(), request.capacity(),
                request.clearsCapacity(), request.memo(), me)));
    }

    /**
     * 강의실 사용/중지.
     *
     * <p>공사·용도변경으로 한동안 못 쓰는 방을 <b>삭제하면 그 방에서 진행됐던 기록의
     * 근거가 끊긴다</b>. 중지가 맞다.
     */
    @PatchMapping("/rooms/{id}/active")
    public ApiResponse<MasterResponses.Room> changeRoomActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeActive request) {
        return ApiResponse.success(MasterResponses.Room.from(
                masterDataService.changeRoomActive(id, request.active(), me)));
    }

    /** 강의실 삭제(soft). 대부분은 삭제가 아니라 중지가 맞다. */
    @DeleteMapping("/rooms/{id}")
    public ApiResponse<Void> deleteRoom(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        masterDataService.deleteRoom(id, me);
        return ApiResponse.empty();
    }

    // ── 장학 종류 마스터 ──

    /**
     * 장학 종류 목록 — 관리 화면. <b>중지된 것도 나온다.</b>
     *
     * <p>{@code code}가 취소 규칙({@code /admin/scholarship/rules})과 이어지는 값이라
     * 화면에 반드시 노출해야 한다. 이름만 보이면 규칙이 왜 안 걸리는지 알 수 없다.
     *
     * @param academyId 비우면 전 지점 공통 행
     */
    @GetMapping("/scholarship-masters")
    public ApiResponse<List<MasterResponses.ScholarshipMasterItem>> scholarshipMasters(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(scholarshipMasterService.list(me, year, academyId).stream()
                .map(MasterResponses.ScholarshipMasterItem::from).toList());
    }

    /**
     * 그 지점에서 <b>고를 수 있는</b> 장학 — 부여 화면 드롭다운.
     *
     * <p>위 목록과 축이 다르다. 지점 행이 공통본을 덮고 중지된 것은 빠진다.
     */
    @GetMapping("/scholarship-masters/selectable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<MasterResponses.ScholarshipMasterItem>> selectableScholarships(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId) {
        // 요청 값을 그냥 믿으면 남의 지점 장학이 보인다
        Long scope = me.resolveAcademyScope(academyId);
        return ApiResponse.success(scholarshipMasterService.selectable(year, scope).stream()
                .map(MasterResponses.ScholarshipMasterItem::from).toList());
    }

    @PostMapping("/scholarship-masters")
    public ApiResponse<MasterResponses.ScholarshipMasterItem> createScholarshipMaster(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateScholarshipMaster request) {
        return ApiResponse.success(MasterResponses.ScholarshipMasterItem.from(
                scholarshipMasterService.create(me, request.academyId(), request.year(),
                        request.code(), request.name(), request.discountRate(),
                        request.sortOrderOrZero(), request.memo())));
    }

    /**
     * 장학 종류 수정.
     *
     * <p><b>{@code code}는 못 바꾼다</b> — 이미 부여된 장학과 취소 규칙이 그 값으로
     * 이어져 있어, 바꾸면 그 학생들이 규칙에서 통째로 빠진다. 바꿀 일이면 새로 만든다.
     *
     * <p>할인율 변경은 <b>앞으로 부여될 건에만</b> 적용된다.
     */
    @PutMapping("/scholarship-masters/{id}")
    public ApiResponse<MasterResponses.ScholarshipMasterItem> updateScholarshipMaster(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.UpdateScholarshipMaster request) {
        return ApiResponse.success(MasterResponses.ScholarshipMasterItem.from(
                scholarshipMasterService.update(me, id, request.name(), request.discountRate(),
                        request.sortOrderOrZero(), request.memo())));
    }

    /**
     * 장학 종류 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * <p>위 {@code PUT}은 이름만 바꾸려 해도 할인율을 실어 보내야 해서, 그 사이 남이
     * 바꾼 할인율을 되돌려 놓는다. {@code code}는 여기서도 못 바꾼다.
     */
    @PatchMapping("/scholarship-masters/{id}")
    public ApiResponse<MasterResponses.ScholarshipMasterItem> patchScholarshipMaster(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.PatchScholarshipMaster request) {
        return ApiResponse.success(MasterResponses.ScholarshipMasterItem.from(
                scholarshipMasterService.patch(me, id, request.name(), request.discountRate(),
                        request.sortOrder(), request.memo())));
    }

    /** 사용/중지. 중지하면 새로 부여할 수 없고, 이미 부여된 건은 그대로 남는다. */
    @PatchMapping("/scholarship-masters/{id}/active")
    public ApiResponse<MasterResponses.ScholarshipMasterItem> changeScholarshipMasterActive(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id,
            @Valid @RequestBody MasterRequests.ChangeScholarshipMasterActive request) {
        return ApiResponse.success(MasterResponses.ScholarshipMasterItem.from(
                scholarshipMasterService.changeActive(me, id, request.active())));
    }

    /** 삭제 — soft delete. 대부분은 삭제가 아니라 중지가 맞다. */
    @DeleteMapping("/scholarship-masters/{id}")
    public ApiResponse<Void> deleteScholarshipMaster(
            @CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        scholarshipMasterService.delete(me, id);
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
