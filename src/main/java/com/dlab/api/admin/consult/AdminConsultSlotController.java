package com.dlab.api.admin.consult;

import com.dlab.api.app.consult.ConsultRequests;
import com.dlab.api.app.consult.ConsultResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.consult.service.ConsultReservationService;
import com.dlab.domain.user.entity.Teacher;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 상담 가능 일정 관리 (F-4.11-4).
 *
 * <p><b>담임 본인 일정만 다룬다.</b> 남의 일정을 열고 닫으면 그 담임이 모르는 사이에 예약이
 * 들어오거나 끊긴다. 그래서 대상 담임을 파라미터로 받지 않고 <b>로그인한 계정에서 꺼낸다</b> —
 * 파라미터로 받으면 다른 담임 ID를 넣는 것만으로 통과한다.
 *
 * <p><b>행정({@code employee})은 상담 일정을 열 수 없다.</b> 상담은 담당선생님이 한다.
 *
 * <p><b>개설과 노출이 두 단계다.</b> 만들면 꺼진 상태이고, 명시적으로 켜야 학생에게 보인다 —
 * 일정을 짜는 중간 상태가 그대로 노출되지 않게 한다.
 */
@Tag(name = "관리자 · 상담 가능 일정 (F-4.11-4)")
@RestController
@RequestMapping("/api/v1/admin/consults/slots")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminConsultSlotController {

    private final ConsultReservationService consultService;
    private final Clock clock;

    /** 내 일정 + 예약자 명단. 노출 전 슬롯도 나온다. @param from/to 비우면 오늘부터 2주 */
    @GetMapping
    public ApiResponse<List<ConsultResponse.Slot>> mySlots(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Teacher teacher = consultService.requireTeacher(me.accountId());
        LocalDate start = from == null ? LocalDate.now(clock) : from;
        LocalDate end = to == null ? start.plusWeeks(2) : to;

        return ApiResponse.success(consultService.mySlots(teacher, start, end)
                .stream().map(ConsultResponse.Slot::from).toList());
    }

    /** 일괄 개설. 이미 있는 시각은 건너뛴다 — 오전을 연 뒤 오후를 추가하는 흐름이 있다. */
    @PostMapping
    public ApiResponse<List<ConsultResponse.Slot>> open(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @Valid @RequestBody ConsultRequests.OpenSlots request) {

        Teacher teacher = consultService.requireTeacher(me.accountId());
        return ApiResponse.success(consultService.openSlots(teacher, year, request.date(),
                        request.from(), request.to(), request.intervalMinutes(),
                        request.capacity(), request.place())
                .stream()
                .map(slot -> ConsultResponse.Slot.from(
                        new ConsultReservationService.SlotView(slot, 0, List.of())))
                .toList());
    }

    /** 노출 켜기/끄기. 내려도 이미 잡힌 예약은 유효하다. */
    @PatchMapping("/{slotId}/publish")
    public ApiResponse<ConsultResponse.Slot> publish(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody ConsultRequests.Publish request) {

        Teacher teacher = consultService.requireTeacher(me.accountId());
        return ApiResponse.success(ConsultResponse.Slot.from(
                new ConsultReservationService.SlotView(
                        consultService.changePublished(slotId, teacher, request.published()),
                        0, List.of())));
    }

    /** 상담 가능 일정 수정. <b>이미 예약된 인원보다 적은 정원으로는 줄일 수 없다.</b> */
    @PatchMapping("/{slotId}")
    public ApiResponse<ConsultResponse.Slot> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long slotId,
            @Valid @RequestBody ConsultRequests.UpdateSlot request) {

        Teacher teacher = consultService.requireTeacher(me.accountId());
        return ApiResponse.success(ConsultResponse.Slot.from(
                new ConsultReservationService.SlotView(
                        consultService.update(slotId, teacher, request.place(), request.memo()),
                        0, List.of())));
    }

    /** 담임이 대신 취소. 학생 사정으로 담임이 정리하는 경우가 있다. */
    @DeleteMapping("/reservations/{reservationId}")
    public ApiResponse<Void> cancel(@PathVariable Long reservationId) {
        consultService.cancel(reservationId, null);
        return ApiResponse.empty();
    }

    /** 상담 일지를 예약에 잇는다. 이어야 노쇼(일지 없는 예약)가 구분된다. */
    @PatchMapping("/reservations/{reservationId}/log")
    public ApiResponse<Void> linkLog(@PathVariable Long reservationId,
                                     @RequestParam Long consultLogId) {
        consultService.linkLog(reservationId, consultLogId);
        return ApiResponse.empty();
    }
}
