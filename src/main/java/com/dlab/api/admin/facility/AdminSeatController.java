package com.dlab.api.admin.facility;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.facility.entity.SeatPresence;
import com.dlab.domain.facility.service.SeatAssignmentService;
import com.dlab.domain.facility.service.SeatLayoutService;
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
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(seatLayoutService.areas(me, academyId));
    }

    /**
     * 좌석배치도 — 좌표 맵.
     *
     * <p><b>상태가 두 축이다.</b> {@code assignmentState}(배정됨/미배정/사용중지)와
     * {@code presence}(재실/외출/미등원/공석)를 조합해 표시한다 — 합치면
     * "배정됐지만 미등원"과 "빈자리"가 구분되지 않는다.
     *
     * <p>재실은 <b>서버가 계산해서 내린다.</b> 화면이 출결 로그와 좌석을 조합하면
     * 새로고침마다 값이 흔들린다. 학생 이름은 마스킹된 값이다.
     */
    @GetMapping("/layout")
    public ApiResponse<List<SeatCellResponse>> layout(@CurrentAccount AuthPrincipal me,
                                                      @RequestParam Long studyAreaId) {
        return ApiResponse.success(seatLayoutService.layout(me, studyAreaId).stream()
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
            String studentName) {

        public static SeatCellResponse from(SeatLayoutService.SeatCell c) {
            return new SeatCellResponse(c.seatId(), c.seatCd(), c.seatNm(),
                    c.xPos(), c.yPos(), c.assignmentState(), c.presence(),
                    c.enrollmentId(), c.studentNo(), c.studentName());
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
                                                      @Valid @RequestBody SeatRequests.Assign request) {
        return ApiResponse.success(SeatAssignmentResponse.from(
                seatAssignmentService.assign(request.seatId(), request.enrollmentId(), me)));
    }

    /** 좌석 배정 해제. 좌석 자체는 남는다. */
    @DeleteMapping("/students/{enrollmentId}")
    public ApiResponse<Void> release(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId) {
        seatAssignmentService.release(enrollmentId, me);
        return ApiResponse.empty();
    }
}
