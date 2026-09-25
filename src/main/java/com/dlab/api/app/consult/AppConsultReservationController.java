package com.dlab.api.app.consult;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.consult.service.ConsultReservationService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 상담 예약 (앱, F-4.11-4).
 *
 * <p><b>담임의 노출된 일정만 보인다.</b> 담임은 반 배정에서 자동으로 나온다 — 반 배정이
 * 없거나 그 반에 담임이 없으면 예약할 대상이 없다는 뜻이라 그대로 알려준다(빈 목록으로
 * 내리면 학생이 "일정이 아직 없나 보다"로 오해한다).
 *
 * <p><b>예약·취소는 학생 본인만 한다.</b> 조회는 학부모도 본다 — 자녀가 언제 상담을
 * 잡았는지는 알아야 한다.
 */
@Tag(name = "앱 · 상담 예약 (F-4.11-4)")
@RestController
@RequestMapping("/api/v1/app/consults")
@RequiredArgsConstructor
public class AppConsultReservationController {

    private final AppScopeResolver scopeResolver;
    private final ConsultReservationService consultService;
    private final com.dlab.domain.consult.service.ConsultPlanQueryService planQueryService;
    private final Clock clock;

    /** 예약 가능한 일정. @param from/to 비우면 오늘부터 2주 */
    /**
     * 지난 상담에서 함께 정한 계획 (시안 4.8-③).
     *
     * <p><b>상담 내용은 내리지 않는다</b> — 계획만이다. 학부모는 <b>담임이 학부모 공개로 지정한
     * 상담만</b> 본다(관리자 웹 기본값이 비공개다).
     */
    @GetMapping("/plans")
    public ApiResponse<java.util.List<com.dlab.domain.consult.service.ConsultPlanQueryService.Plan>> plans(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {
        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        boolean forParent = scopeResolver.account(me.accountId()).getGuardian() != null;
        return ApiResponse.success(planQueryService.plans(enrollment, forParent));
    }

    @GetMapping("/slots")
    public ApiResponse<List<ConsultResponse.ConsultSlotView>> slots(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate start = from == null ? LocalDate.now(clock) : from;
        LocalDate end = to == null ? start.plusWeeks(2) : to;

        return ApiResponse.success(consultService.availableSlots(enrollment, start, end)
                .stream().map(ConsultResponse.ConsultSlotView::from).toList());
    }

    /** 예약하면 담임에게 알림이 간다(문구 확정 전까지는 이력만 남는다). */
    @PostMapping("/reservations")
    public ApiResponse<ConsultResponse.ConsultReservationView> reserve(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody ConsultRequests.ConsultReserve request) {

        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "상담 예약");
        return ApiResponse.success(ConsultResponse.ConsultReservationView.from(consultService.reserve(
                enrollment, request.slotId(), request.consultType(), request.requestNote())));
    }

    /** 취소. 지난 상담은 취소할 수 없다 — 노쇼 여부를 알 수 없게 된다. */
    @DeleteMapping("/reservations/{reservationId}")
    public ApiResponse<Void> cancel(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long reservationId) {

        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "상담 예약 취소");
        consultService.cancel(reservationId, enrollment.getId());
        return ApiResponse.empty();
    }

    /** 본인 예약 내역. 취소분도 이력으로 나온다. @param from/to 비우면 최근 3개월 */
    @GetMapping("/reservations")
    public ApiResponse<List<ConsultResponse.ConsultReservationView>> myReservations(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate end = to == null ? LocalDate.now(clock).plusMonths(1) : to;
        LocalDate start = from == null ? end.minusMonths(4) : from;

        return ApiResponse.success(
                consultService.myReservations(enrollment.getId(), start, end)
                        .stream().map(ConsultResponse.ConsultReservationView::from).toList());
    }
}
