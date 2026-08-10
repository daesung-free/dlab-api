package com.dlab.api.app.qna;

import com.dlab.api.admin.qna.QnaRequests;
import com.dlab.api.admin.qna.QnaResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.qna.service.QnaOfflineService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 학생 앱 — 대면 상담 예약 (A-13 OFF).
 *
 * <p>*"상담실 가능 타임 조회 → 희망 타임 예약·취소"*가 시트 문구 그대로다.
 *
 * <p><b>학생 본인만 쓴다.</b> 상담은 학생이 직접 잡는 것이라 학부모 경로를 두지 않았다 —
 * 시트도 사용자를 "학생"으로만 적었다.
 */
@RestController
@RequestMapping("/api/v1/app/qna/offline")
@RequiredArgsConstructor
public class AppQnaOfflineController {

    private final QnaOfflineService qnaOfflineService;
    private final AppScopeResolver scopeResolver;

    /**
     * 그날 가능 타임.
     *
     * <p><b>예약자 명단은 안 나온다</b> — 인원 수와 마감 여부만 준다.
     * 누가 상담을 잡았는지가 다른 학생에게 보이면 안 된다.
     */
    @GetMapping("/slots")
    public ApiResponse<List<QnaResponse.Slot>> slots(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "대면 예약");
        return ApiResponse.success(
                qnaOfflineService.availableSlots(enrollment.getAcademy().getId(), date).stream()
                        .map(QnaResponse.Slot::from).toList());
    }

    /** 예약. 정원이 차면 거절된다 — 상담 시간은 겹칠 수 없어 대기 개념이 없다. */
    @PostMapping("/slots/{slotId}/reservations")
    public ApiResponse<QnaResponse.MyReservation> reserve(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody(required = false) QnaRequests.Reserve request) {
        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "대면 예약");
        return ApiResponse.success(QnaResponse.MyReservation.from(qnaOfflineService.reserve(
                slotId, enrollment.getId(), request == null ? null : request.question())));
    }

    /** 본인 예약만 취소된다. 지난 타임은 취소할 수 없다. */
    @DeleteMapping("/reservations/{reservationId}")
    public ApiResponse<Void> cancel(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long reservationId) {
        qnaOfflineService.cancel(reservationId,
                scopeResolver.requireStudent(me.accountId(), "대면 예약").getId());
        return ApiResponse.empty();
    }

    /** 내 예약 내역. 취소분도 이력으로 나온다. */
    @GetMapping("/reservations")
    public ApiResponse<List<QnaResponse.MyReservation>> myReservations(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(
                qnaOfflineService.myReservations(
                                scopeResolver.requireStudent(me.accountId(), "대면 예약").getId(),
                                from, to).stream()
                        .map(QnaResponse.MyReservation::from).toList());
    }
}
