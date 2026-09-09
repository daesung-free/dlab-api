package com.dlab.api.admin.qna;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.qna.service.QnaOfflineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 질의응답 대면(OFF) 관리 (F-4.11-7).
 *
 * <p>*"상담실 가능 타임 개설·예약 현황 관리"*가 시트 문구 그대로다.
 *
 * <p><b>온라인(ON)은 없다</b> — 멘토 배정 규칙·답변 SLA·첨부 허용·채팅 경계가 전부 미확정이다.
 */
@Tag(name = "관리자 · 질의응답 대면 (F-4.11-7)")
@RestController
@RequestMapping("/api/v1/admin/qna/offline")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminQnaOfflineController {

    private final QnaOfflineService qnaOfflineService;

    /**
     * 가능 타임 일괄 개설.
     *
     * <p>시작·종료·간격을 받아 슬롯을 여러 개 만든다. <b>이미 있는 시각은 건너뛴다</b> —
     * 오전을 열어둔 뒤 오후를 추가하는 흐름이 있는데, 중복이라고 통째로 거절하면
     * 그때마다 시각을 손으로 맞춰야 한다.
     */
    @PostMapping("/slots")
    public ApiResponse<List<QnaResponse.QnaSlot>> openSlots(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody QnaRequests.QnaOpenSlots request) {
        var created = qnaOfflineService.openSlots(
                request.academyId(), request.year().shortValue(), request.date(),
                request.from(), request.to(), request.intervalMinutes(),
                request.teacherId(), request.room(),
                request.capacity() == null ? 1 : request.capacity().shortValue(), me);
        return ApiResponse.success(created.stream()
                .map(slot -> QnaResponse.QnaSlot.from(
                        new QnaOfflineService.SlotView(slot, 0, List.of())))
                .toList());
    }

    /**
     * 슬롯 + 예약 현황. 예약자 명단이 함께 나온다.
     *
     * <p><b>기간으로도 조회한다.</b> 화면이 주간 그리드라 하루씩 부르면 5회가 매번 나간다 —
     * {@code from}·{@code to}를 주면 한 번에 받는다. {@code date} 하나만 주면 그날만 본다.
     */
    @GetMapping("/slots")
    public ApiResponse<List<QnaResponse.QnaSlot>> slots(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (date == null && from == null) {
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.INVALID_REQUEST,
                    "date 또는 from·to 중 하나는 필요합니다.");
        }
        var views = date != null
                ? qnaOfflineService.slotsWithReservations(academyId, date, me)
                : qnaOfflineService.slotsWithReservations(academyId, from,
                        to == null ? from : to, me);

        return ApiResponse.success(views.stream().map(QnaResponse.QnaSlot::from).toList());
    }

    /**
     * 마감/재개.
     *
     * <p><b>삭제가 아니다.</b> 지우면 이미 예약한 학생 기록이 사라진다 —
     * 닫으면 새 예약만 막고 기존 예약은 유효하다.
     */
    @PutMapping("/slots/{slotId}/closed")
    public ApiResponse<Void> changeClosed(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody QnaRequests.ChangeClosed request) {
        qnaOfflineService.changeClosed(slotId, request.closed(), me);
        return ApiResponse.empty();
    }

    /** 담당·상담실 배정. 슬롯만 먼저 열고 나중에 정하는 운영이 있다. */
    @PatchMapping("/slots/{slotId}")
    public ApiResponse<Void> assign(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody QnaRequests.QnaAssign request) {
        qnaOfflineService.assign(slotId, request.teacherId(), request.room(), request.memo(), me);
        return ApiResponse.empty();
    }

    /** 관리자 취소. */
    @DeleteMapping("/reservations/{reservationId}")
    public ApiResponse<Void> cancel(@PathVariable Long reservationId) {
        qnaOfflineService.cancel(reservationId, null);
        return ApiResponse.empty();
    }
}
