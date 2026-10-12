package com.dlab.api.admin.attendance;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.entity.StaffAttendance;
import com.dlab.domain.attendance.entity.StaffAttendanceEdit;
import com.dlab.domain.attendance.entity.StaffAttendanceType;
import com.dlab.domain.attendance.service.StaffAttendanceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 직원 출근 기록 — 조회·보정 (긴급출입 2-4 · 관리자 웹 12번).
 *
 * <h2>★ 권한이 두 층이다</h2>
 * <ul>
 *   <li><b>화면이 보이나</b> — {@code staff-attendance} 메뉴(계정별 노출 설정)</li>
 *   <li><b>고칠 수 있나</b> — {@code STAFF_ATTENDANCE_EDIT} 권한</li>
 * </ul>
 * 권한 없는 계정은 <b>이력은 보되 수정이 막힌다.</b> 화면에서 버튼만 감추면 주소로 직접
 * 부를 수 있으므로 <b>서버가 같은 판정으로 막는다.</b>
 *
 * <h2>⚠️ 조회 범위를 지점 관리자까지 열었다 — 확인이 필요하다</h2>
 * 기존 직원증 화면({@code /admin/staff-cards})의 근태 조회는 <b>최고관리자 전용</b>이고,
 * 그 주석은 <i>"지점 관리자에게 열면 자기 지점 직원의 근태를 보게 되는데 그건 인사
 * 정보라 범위를 따로 정해야 한다"</i>고 적어 뒀다.
 *
 * <p>그런데 이번 요구사항은 <b>지점 관리자가 자기 지점 출근을 확인하는 것</b>을 전제한다
 * (07:05 알림을 받는 사람이 그 지점 관리자다). 노출은 <b>메뉴 권한으로 계정별로 정하는
 * 것</b>이 요구사항 3-2-1 의 방식이므로 여기서는 지점 관리자까지 열고, 범위는 자기 지점으로
 * 묶었다. <b>인사 정보 열람 범위가 확정되면 이 결정을 다시 볼 것.</b>
 */
@Tag(name = "관리자 · 직원 출근 기록")
@RestController
@RequestMapping("/api/v1/admin/staff-attendance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminStaffAttendanceController {

    private final StaffAttendanceService staffAttendanceService;

    /**
     * 출근 기록 조회.
     *
     * <p>응답에 <b>{@code canEdit} 이 함께 온다</b> — 화면이 수정 버튼 노출을 그걸로 정하고,
     * 서버도 같은 값으로 막는다. 둘이 어긋나면 눌러도 403 이 나는 버튼이 남는다.
     */
    @GetMapping("/records")
    public ApiResponse<RecordPage> records(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Long enrollmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Long scoped = me.requireAcademyScope(academyId);
        var rows = staffAttendanceService.search(me, scoped, enrollmentId, from, to);
        var editedIds = staffAttendanceService.editedIdsOf(rows);
        return ApiResponse.success(new RecordPage(
                staffAttendanceService.canEdit(me),
                rows.stream()
                        .map(a -> RecordRow.of(a, editedIds.contains(a.getId())))
                        .toList()));
    }

    /**
     * 기록 보정.
     *
     * <p>★ <b>이유가 필수다.</b> 요구사항의 「누가·언제·무엇을·왜」 중 「왜」가 없으면
     * 나머지 셋이 쓸모없어진다 — 고친 값은 원본보다 신뢰도가 낮은데 그 사실이 안 남는다.
     *
     * <p>★ <b>근무일을 넘기는 수정은 거절한다.</b> 오전 출근 확인이 근무일로 세기 때문에,
     * 시각만 다른 날로 옮기면 그 행이 어느 날에도 안 잡힌다.
     */
    @PatchMapping("/records/{attendanceId}")
    public ApiResponse<RecordRow> edit(@CurrentAccount AuthPrincipal me,
                                       @PathVariable Long attendanceId,
                                       @Valid @RequestBody EditRequest request) {
        // 방금 고쳤으므로 edited 는 확실히 true 다 — 다시 조회할 이유가 없다
        return ApiResponse.success(RecordRow.of(staffAttendanceService.edit(
                me, attendanceId, request.eventType(), request.recordedAt(), request.reason()),
                true));
    }

    /** 그 기록의 수정 이력. */
    @GetMapping("/records/{attendanceId}/edits")
    public ApiResponse<List<EditRow>> edits(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long attendanceId) {
        return ApiResponse.success(staffAttendanceService.editsOf(me, attendanceId).stream()
                .map(EditRow::from).toList());
    }

    /** 지점·기간 수정 이력 — 「이력 보기」 화면. */
    @GetMapping("/edits")
    public ApiResponse<List<EditRow>> editHistory(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.success(
                staffAttendanceService.editHistory(me, academyId, from, to).stream()
                        .map(EditRow::from).toList());
    }

    /**
     * @param recordedAt 보정할 시각. <b>근무일과 같은 날이어야 한다</b>
     * @param reason     고친 이유. <b>비울 수 없다</b>
     */
    public record EditRequest(@NotNull StaffAttendanceType eventType,
                              @NotNull LocalDateTime recordedAt,
                              @NotBlank(message = "고친 이유를 적어 주세요.")
                              @Size(max = 500) String reason) {
    }

    /**
     * @param canEdit 이 계정이 고칠 수 있나. 화면이 버튼 노출에 쓴다 —
     *                <b>서버도 같은 값으로 막는다</b>
     */
    public record RecordPage(boolean canEdit, List<RecordRow> records) {
    }

    /** @param edited 한 번이라도 고쳐진 기록인가. 화면이 「보정됨」을 표시한다 */
    public record RecordRow(Long id, Long academyId, Long enrollmentId, String staffName,
                            LocalDate workDate, StaffAttendanceType eventType,
                            Instant recordedAt, boolean edited) {

        static RecordRow of(StaffAttendance a, boolean edited) {
            return new RecordRow(a.getId(), a.getAcademy().getId(),
                    a.getEnrollment().getId(), a.getEnrollment().getStudentName(),
                    a.getWorkDate(), a.getEventType(), a.getRecordedAt(), edited);
        }
    }

    /**
     * @param editedBy 고친 계정 id. 배치·시스템이면 {@code 0} 이다
     * @param reason   고친 이유. 필수로 받으므로 비어 있지 않다
     */
    public record EditRow(Long id, Long attendanceId, String staffName,
                          LocalDate workDate,
                          StaffAttendanceType beforeEventType, Instant beforeRecordedAt,
                          StaffAttendanceType afterEventType, Instant afterRecordedAt,
                          String reason, Long editedBy, Instant editedAt) {

        static EditRow from(StaffAttendanceEdit e) {
            return new EditRow(e.getId(), e.getAttendance().getId(),
                    e.getAttendance().getEnrollment().getStudentName(),
                    e.getAttendance().getWorkDate(),
                    e.getBeforeEventType(), e.getBeforeRecordedAt(),
                    e.getAfterEventType(), e.getAfterRecordedAt(),
                    e.getReason(), e.getCreatedBy(), e.getCreatedAt());
        }
    }
}
