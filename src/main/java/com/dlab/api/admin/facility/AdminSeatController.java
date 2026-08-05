package com.dlab.api.admin.facility;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.facility.service.SeatAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 웹 — 좌석 배정.
 *
 * <p>스키마(V2)와 <b>키오스크 좌석 조회는 다른 담당자</b> 영역이다. 여기는 배정·반납만 한다
 * (도메인 서비스에서 조회는 Phase 1, 생성·수정은 Phase 2로 나누기로 합의).
 */
@RestController
@RequestMapping("/api/v1/admin/seats")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminSeatController {

    private final SeatAssignmentService seatAssignmentService;

    /** 구역별 현재 배정 현황. 좌석배치도에 뿌린다. */
    @GetMapping
    public ApiResponse<List<SeatAssignmentResponse>> byArea(@RequestParam Long studyAreaId) {
        return ApiResponse.success(seatAssignmentService.byStudyArea(studyAreaId).stream()
                .map(SeatAssignmentResponse::from).toList());
    }

    @PostMapping
    public ApiResponse<SeatAssignmentResponse> assign(@CurrentAccount AuthPrincipal me,
                                                      @Valid @RequestBody SeatRequests.Assign request) {
        return ApiResponse.success(SeatAssignmentResponse.from(
                seatAssignmentService.assign(request.seatId(), request.enrollmentId(), me)));
    }

    @DeleteMapping("/students/{enrollmentId}")
    public ApiResponse<Void> release(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId) {
        seatAssignmentService.release(enrollmentId, me);
        return ApiResponse.empty();
    }
}
