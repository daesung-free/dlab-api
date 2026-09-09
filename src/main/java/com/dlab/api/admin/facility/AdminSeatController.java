package com.dlab.api.admin.facility;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.facility.entity.SeatPresence;
import com.dlab.domain.facility.service.SeatAssignmentService;
import com.dlab.domain.facility.service.SeatLayoutService;
import com.dlab.domain.facility.service.SeatMasterAdminService;
import com.dlab.domain.facility.service.StudyAreaAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 좌석 배정.
 *
 * <p>스키마(V2)와 <b>키오스크 좌석 조회는 다른 담당자</b> 영역이다. 여기는 배정·반납만 한다
 * (도메인 서비스에서 조회는 Phase 1, 생성·수정은 Phase 2로 나누기로 합의).
 */
@Tag(name = "관리자 · 좌석·좌석배치도 (F-4.10-3)")
@RestController
@RequestMapping("/api/v1/admin/seats")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminSeatController {

    private final SeatAssignmentService seatAssignmentService;
    private final SeatLayoutService seatLayoutService;
    private final StudyAreaAdminService studyAreaAdminService;
    private final SeatMasterAdminService seatMasterAdminService;

    /** 구역별 현재 배정 현황. 좌석배치도에 뿌린다. */
    @GetMapping
    public ApiResponse<List<SeatAssignmentResponse>> byArea(@RequestParam Long studyAreaId) {
        return ApiResponse.success(seatAssignmentService.byStudyArea(studyAreaId).stream()
                .map(SeatAssignmentResponse::from).toList());
    }

    /**
     * 구역 목록. 배치도를 열려면 먼저 구역을 골라야 한다.
     *
     * @param academyId 전 지점 권한자만 지정한다
     */
    @GetMapping("/areas")
    public ApiResponse<List<SeatLayoutService.AreaSummary>> areas(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(seatLayoutService.areas(me, academyId, includeInactive));
    }

    /**
     * 구역 등록.
     *
     * <p><b>{@code areaCd}는 등록 후 바꿀 수 없다</b> — 키오스크가 이 코드로 좌석을 조회한다
     * (3.7·3.8). 지운 구역과 코드가 겹치면 그 구역이 되살아난다.
     */
    @PostMapping("/areas")
    public ApiResponse<StudyAreaResponse> createArea(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SeatRequests.StudyAreaCreate request) {
        var area = studyAreaAdminService.create(me, request.academyId(), request.areaCd(),
                request.areaNm(), request.sortOrderOrDefault());
        return ApiResponse.success(StudyAreaResponse.from(area, 0));
    }

    /** 구역 수정 — 이름·정렬·노출만. 코드는 대상이 아니다. */
    @PatchMapping("/areas/{studyAreaId}")
    public ApiResponse<StudyAreaResponse> updateArea(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long studyAreaId,
            @Valid @RequestBody SeatRequests.StudyAreaUpdate request) {
        var area = studyAreaAdminService.update(me, studyAreaId, request.areaNm(),
                request.sortOrder(), request.active());
        return ApiResponse.success(
                StudyAreaResponse.from(area, studyAreaAdminService.seatCount(studyAreaId)));
    }

    /**
     * 구역 삭제(soft).
     *
     * <p>좌석이 남아 있으면 거부한다 — 구역만 지우면 좌석이 배치도에서는 사라지는데
     * 키오스크 조회에는 계속 뜬다.
     */
    @DeleteMapping("/areas/{studyAreaId}")
    public ApiResponse<Void> deleteArea(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long studyAreaId) {
        studyAreaAdminService.delete(me, studyAreaId);
        return ApiResponse.empty();
    }

    /** 구역의 좌석 목록(좌표 포함). 배정 정보 없이 마스터만 본다. */
    @GetMapping("/masters")
    public ApiResponse<List<SeatMasterResponse>> seatMasters(@CurrentAccount AuthPrincipal me,
                                                             @RequestParam Long studyAreaId) {
        return ApiResponse.success(seatMasterAdminService.list(me, studyAreaId).stream()
                .map(SeatMasterResponse::from).toList());
    }

    /** 좌석 단건 등록. 배치도에 한 자리만 끼워 넣을 때 쓴다. */
    @PostMapping("/masters")
    public ApiResponse<SeatMasterResponse> createSeat(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SeatRequests.SeatCreate request) {
        return ApiResponse.success(SeatMasterResponse.from(seatMasterAdminService.create(
                me, request.studyAreaId(), request.seatCd(), request.seatNm(),
                request.xPos(), request.yPos())));
    }

    /**
     * 격자 일괄 등록 — <b>좌석 등록의 기본 경로</b>.
     *
     * <p>4행 × 5열처럼 행·열만 주면 좌표와 좌석번호를 서버가 만든다. 통로는 {@code skips}로
     * 빼고 번호는 그 칸을 건너뛰고 이어진다. <b>코드가 하나라도 겹치면 아무것도 만들지
     * 않고</b> 겹친 코드를 전부 모아 알려준다.
     */
    @PostMapping("/masters/grid")
    public ApiResponse<List<SeatMasterResponse>> createSeatGrid(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SeatRequests.SeatGridCreate request) {
        var spec = new SeatMasterAdminService.SeatGridSpec(
                request.studyAreaId(), request.rows(), request.columns(), request.seatCdPrefix(),
                request.startNumberOrDefault(), request.numberPaddingOrDefault(),
                request.startXOrDefault(), request.startYOrDefault(),
                request.columnMajorOrDefault(),
                request.skips() == null ? List.of() : request.skips().stream()
                        .map(c -> new SeatMasterAdminService.GridSkip(c.row(), c.column()))
                        .toList());
        return ApiResponse.success(seatMasterAdminService.createGrid(me, spec).stream()
                .map(SeatMasterResponse::from).toList());
    }

    /** 좌석 수정 — 이름·좌표만. {@code seatCd}는 대상이 아니다. */
    @PatchMapping("/masters/{seatId}")
    public ApiResponse<SeatMasterResponse> updateSeat(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long seatId,
            @Valid @RequestBody SeatRequests.SeatUpdate request) {
        return ApiResponse.success(SeatMasterResponse.from(seatMasterAdminService.update(
                me, seatId, request.seatNm(), request.xPos(), request.yPos())));
    }

    /**
     * 좌석 삭제(soft).
     *
     * <p>배정 이력이 이 행을 참조하므로 물리 삭제하지 않는다. 배정 중인 좌석은 거부한다.
     */
    @DeleteMapping("/masters/{seatId}")
    public ApiResponse<Void> deleteSeat(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long seatId) {
        seatMasterAdminService.delete(me, seatId);
        return ApiResponse.empty();
    }

    /**
     * 좌석배치도 — 좌표 맵.
     *
     * <p><b>상태가 두 축이다.</b> {@code assignmentState}(배정됨/미배정/사용중지)와
     * {@code presence}(재실/외출/미등원/공석)를 조합해 표시한다 — 합치면
     * "배정됐지만 미등원"과 "빈자리"가 구분되지 않는다.
     *
     * <p>재실은 <b>서버가 계산해서 내린다.</b> 화면이 출결 로그와 좌석을 조합하면
     * 새로고침마다 값이 흔들린다.
     *
     * <p>학생 이름은 기본이 마스킹이고 {@code unmask=true}는 <b>상위 관리자에게만</b> 먹는다.
     * 실제 적용 여부는 응답의 {@code masked}로 알려준다 — 다른 목록과 같은 규칙이다.
     */
    @GetMapping("/layout")
    public ApiResponse<List<SeatCellResponse>> layout(@CurrentAccount AuthPrincipal me,
                                                      @RequestParam Long studyAreaId,
                                                      @RequestParam(defaultValue = "false")
                                                      boolean unmask) {
        return ApiResponse.success(seatLayoutService.layout(me, studyAreaId, unmask).stream()
                .map(SeatCellResponse::from)
                .toList());
    }

    /**
     * 좌석 사용중지·해제 (고장·공사).
     *
     * <p>좌석을 지우지 않는다 — 지우면 배치도에 구멍이 생기고 과거 배정 이력이 끊긴다.
     */
    @PatchMapping("/{seatId}/usable")
    public ApiResponse<Void> changeUsable(@CurrentAccount AuthPrincipal me,
                                          @PathVariable Long seatId,
                                          @RequestParam boolean usable) {
        seatLayoutService.changeUsable(me, seatId, usable);
        return ApiResponse.empty();
    }

    /** @param assignmentState 배정 축. {@code presence}(재실 축)와 별개다 */
    public record SeatCellResponse(
            Long seatId,
            String seatCd,
            String seatNm,
            int xPos,
            int yPos,
            String assignmentState,
            SeatPresence presence,
            Long enrollmentId,
            String studentNo,
            String studentName,
            Long classId,
            String className,
            boolean masked) {

        public static SeatCellResponse from(SeatLayoutService.SeatCell c) {
            return new SeatCellResponse(c.seatId(), c.seatCd(), c.seatNm(),
                    c.xPos(), c.yPos(), c.assignmentState(), c.presence(),
                    c.enrollmentId(), c.studentNo(), c.studentName(),
                    c.classId(), c.className(), c.masked());
        }
    }

    /**
     * 좌석 배정.
     *
     * <p>이미 배정된 좌석이면 거부된다 — 한 자리에 두 명이 들어가면
     * <b>키오스크 좌석표가 실제와 어긋난다.</b>
     */
    @PostMapping
    public ApiResponse<SeatAssignmentResponse> assign(@CurrentAccount AuthPrincipal me,
                                                      @Valid @RequestBody SeatRequests.SeatAssign request) {
        return ApiResponse.success(SeatAssignmentResponse.from(
                seatAssignmentService.assign(request.seatId(), request.enrollmentId(), me)));
    }

    /**
     * 좌석 일괄 배정 — "선택 N명 일괄 배정".
     *
     * <p><b>전부-아니면-전무다.</b> 단건 API를 N번 부르면 중간에 실패했을 때 일부만 배정된 채
     * 남고 되돌릴 방법이 없다. 실패 사유는 <b>전부 모여서</b> 한 번에 돌아온다.
     */
    @PostMapping("/bulk")
    public ApiResponse<List<SeatAssignmentResponse>> assignAll(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SeatRequests.SeatBulkAssign request) {
        var items = request.items().stream()
                .map(i -> new SeatAssignmentService.SeatAssignRequest(i.seatId(), i.enrollmentId()))
                .toList();
        return ApiResponse.success(seatAssignmentService.assignAll(items, me).stream()
                .map(SeatAssignmentResponse::from).toList());
    }

    /** 좌석 배정 해제. 좌석 자체는 남는다. */
    @DeleteMapping("/students/{enrollmentId}")
    public ApiResponse<Void> release(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId) {
        seatAssignmentService.release(enrollmentId, me);
        return ApiResponse.empty();
    }
}
