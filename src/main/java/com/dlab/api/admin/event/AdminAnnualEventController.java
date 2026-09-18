package com.dlab.api.admin.event;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.event.entity.AnnualEvent;
import com.dlab.domain.event.entity.AnnualEventType;
import com.dlab.domain.event.service.AnnualEventService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 연간 행사 (F-4.11-10).
 *
 * <h2>★ 학습계획에 복사하지 않는다</h2>
 * {@code /for-plan} 이 기간에 걸치는 행사를 내려주고, 화면이 그 날짜 칸에 얹는다.
 * 계획 행으로 복사해 두면 행사를 고칠 때 <b>이미 복사된 학생별 행을 전부 따라다녀야
 * 한다</b> — 하나라도 놓치면 없어진 행사가 그 학생 화면에만 남는다. 지금 구조에서는
 * 수정·삭제가 그대로 반영된다.
 *
 * <h2>공휴일과 따로 등록한다</h2>
 * 공휴일({@code /admin/holidays})은 <b>쉬는 날</b>이라 급식 가능일·교습일수에 쓰이고,
 * 행사는 <b>그날 무슨 일이 있다</b>는 표시다. 개교기념일처럼 둘 다인 날은 양쪽에 넣는다 —
 * 합치면 행사를 지웠는데 급식이 열린다.
 */
@Tag(name = "관리자 · 연간 행사 (F-4.11-10)")
@RestController
@RequestMapping("/api/v1/admin/annual-events")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminAnnualEventController {

    private final AnnualEventService eventService;

    /** 그 해 행사 — <b>지점 행사와 전 지점 공통을 합쳐서</b> 내린다. */
    @GetMapping
    public ApiResponse<List<EventView>> list(@CurrentAccount AuthPrincipal me,
                                             @RequestParam(required = false) Long academyId,
                                             @RequestParam short year) {
        return ApiResponse.success(eventService.findAllOfYear(me, academyId, year)
                .stream().map(EventView::from).toList());
    }

    /**
     * 학습계획·달력에 얹을 행사.
     *
     * <p>기간에 <b>걸치는</b> 행사를 전부 내린다 — 시작일만 보면 3일짜리 행사의 2·3일차가
     * 빠져서, 주간 계획이 그 경계에 걸릴 때 행사가 사라진다.
     * {@code showInPlan=false} 인 내부 일정은 빠진다.
     */
    @GetMapping("/for-plan")
    public ApiResponse<List<EventView>> forPlan(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(eventService.findForPlan(me, academyId, from, to)
                .stream().map(EventView::from).toList());
    }

    /** 등록. <b>지점을 비우면 전 지점 공통</b>이고 그건 본사만 만들 수 있다. */
    @PostMapping
    public ApiResponse<EventView> create(@CurrentAccount AuthPrincipal me,
                                         @Valid @RequestBody SaveEvent request) {
        return ApiResponse.success(EventView.from(eventService.create(
                me, request.academyId(), request.year(), request.name(),
                request.startDate(), request.endDate(), request.eventType(),
                request.showInPlan() == null || request.showInPlan(), request.memo())));
    }

    /** 수정. 비워 보낸 항목은 바꾸지 않는다({@code memo} 는 비우면 지워진다). */
    @PatchMapping("/{eventId}")
    public ApiResponse<EventView> update(@CurrentAccount AuthPrincipal me,
                                         @PathVariable Long eventId,
                                         @RequestBody UpdateEvent request) {
        return ApiResponse.success(EventView.from(eventService.update(
                me, eventId, request.name(), request.startDate(), request.endDate(),
                request.eventType(), request.showInPlan(), request.memo())));
    }

    /**
     * 삭제.
     *
     * <p><b>지우지 않고 내린다</b> — 지난 행사가 어느 날에 있었는지가 학습계획·통계의
     * 근거로 남아야 한다. 학습계획에서는 <b>다음 조회부터 바로 빠진다.</b>
     */
    @DeleteMapping("/{eventId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long eventId) {
        eventService.delete(me, eventId);
        return ApiResponse.empty();
    }

    /**
     * @param academyId  비우면 전 지점 공통 (본사만)
     * @param endDate    하루짜리는 시작일과 같은 값을 넣는다
     * @param showInPlan 비우면 {@code true}. 내부 일정만 {@code false} 로 둔다
     */
    public record SaveEvent(Long academyId,
                            @NotNull(message = "연도는 필수입니다.") Short year,
                            @NotBlank(message = "행사명은 필수입니다.") @Size(max = 100) String name,
                            @NotNull(message = "시작일은 필수입니다.") LocalDate startDate,
                            @NotNull(message = "종료일은 필수입니다.") LocalDate endDate,
                            AnnualEventType eventType,
                            Boolean showInPlan,
                            @Size(max = 500) String memo) {
    }

    public record UpdateEvent(@Size(max = 100) String name,
                              LocalDate startDate, LocalDate endDate,
                              AnnualEventType eventType, Boolean showInPlan,
                              @Size(max = 500) String memo) {
    }

    /** @param shared 전 지점 공통인가. 화면이 지점 행사와 구분해 표시한다 */
    public record EventView(Long id, short year, Long academyId, boolean shared,
                            String name, LocalDate startDate, LocalDate endDate,
                            AnnualEventType eventType, boolean showInPlan, String memo) {

        static EventView from(AnnualEvent e) {
            return new EventView(e.getId(), e.getYear(), e.getAcademyId(), e.isShared(),
                    e.getName(), e.getStartDate(), e.getEndDate(),
                    e.getEventType(), e.isShowInPlan(), e.getMemo());
        }
    }
}
