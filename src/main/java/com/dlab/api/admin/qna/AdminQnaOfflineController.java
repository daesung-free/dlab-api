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

        var photos = qnaOfflineService.photosOf(views.stream()
                .flatMap(v -> v.reservations().stream())
                .map(com.dlab.domain.qna.entity.QnaOfflineReservation::getId)
                .toList());
        return ApiResponse.success(views.stream()
                .map(v -> QnaResponse.QnaSlot.from(v, photos)).toList());
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
    /**
     * ★★ <b>직원 대리 예약</b> (디랩 1029 8-1).
     *
     * <h2>★ 왜 필요한가 — 넣어 줄 경로가 없었다</h2>
     * 지금까지 관리자 API 는 <b>개설·마감·수정·삭제</b>뿐이었다. ⚠️ 그래서
     * <b>전화로 신청한 학생을 직원이 넣어 줄 수 없었다.</b>
     *
     * <h2>★ 하루 상한을 건너뛴다</h2>
     * 상한({@code academy.qna_daily_limit}, 기본 2)은 <b>학생 셀프 신청을 고르게
     * 나누기</b> 위한 것이고, 대리 예약은 <b>직원이 사정을 보고 넣는 것</b>이다.
     *
     * <p>⚠️ <b>막는 것은 그대로다</b> — 같은 슬롯 중복 · 정원 초과 · 마감 · 지난 슬롯 ·
     * 다른 지점. <b>한 자리에 두 번 앉을 수는 없다.</b>
     *
     * <p>★ <b>누가 넣었는지 남는다</b> — 없으면 상한을 왜 넘겼는지 설명할 수 없다.
     */
    @PostMapping("/slots/{slotId}/reservations")
    public ApiResponse<Long> reserveByStaff(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody QnaRequests.StaffReserve request) {
        return ApiResponse.success(qnaOfflineService
                .reserveByStaff(me, slotId, request.enrollmentId(), request.question())
                .getId());
    }

    @DeleteMapping("/reservations/{reservationId}")
    public ApiResponse<Void> cancel(@PathVariable Long reservationId) {
        qnaOfflineService.cancel(reservationId, null);
        return ApiResponse.empty();
    }
}
