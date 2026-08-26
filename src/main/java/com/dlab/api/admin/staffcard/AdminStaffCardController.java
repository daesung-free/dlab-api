package com.dlab.api.admin.staffcard;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.entity.StaffAttendance;
import com.dlab.domain.attendance.service.StaffAttendanceService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.StaffEnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 직원 카드·근태 관리 — 키오스크 출퇴근용.
 *
 * <p><b>SUPER_ADMIN 전용이다.</b> 근태는 인사 정보라 지점 관리자에게 열려면 범위를 따로
 * 정해야 하는데, 그게 정해진 바 없다.
 *
 * <p><b>학생 등록과 별도 화면이다</b> — 학년·계열이 직원에게 없고, 승인·온보딩·학부모
 * 연결이 전부 무의미하다. 직원은 학생 목록에도 나오지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/staff-cards")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminStaffCardController {

    private final StaffEnrollmentService staffEnrollmentService;
    private final StaffAttendanceService staffAttendanceService;

    /** 그 지점 직원 목록. */
    @GetMapping
    public ApiResponse<List<StaffRow>> list(@CurrentAccount AuthPrincipal me,
                                            @RequestParam Long academyId) {
        return ApiResponse.success(staffEnrollmentService.list(me, academyId)
                .stream().map(StaffRow::from).toList());
    }

    /**
     * 직원 등록. 학번은 <b>9000번대</b>로 채번된다 — 4자리 키패드에서 학생과 구분하려는 것이다.
     */
    @PostMapping
    public ApiResponse<StaffRow> register(@CurrentAccount AuthPrincipal me,
                                          @Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(StaffRow.from(staffEnrollmentService.register(
                me, request.academyId(), request.name(), request.phone(), request.rfidNo())));
    }

    /** 카드 교체·분실 재발급. */
    @PatchMapping("/{enrollmentId}/card")
    public ApiResponse<StaffRow> changeCard(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long enrollmentId,
                                            @Valid @RequestBody CardRequest request) {
        return ApiResponse.success(StaffRow.from(
                staffEnrollmentService.changeCard(me, enrollmentId, request.rfidNo())));
    }

    /** 퇴사. 행은 남기고 현재 등록만 내린다 — 지우면 근태 이력의 주인을 알 수 없다. */
    @DeleteMapping("/{enrollmentId}")
    public ApiResponse<Void> retire(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long enrollmentId) {
        staffEnrollmentService.retire(me, enrollmentId);
        return ApiResponse.empty();
    }

    /**
     * 근태 조회.
     *
     * <p>출근·퇴근 기록을 시각 순으로 그대로 내린다. <b>근무시간·지각 판정은 하지 않는다</b> —
     * 근무시간 마스터가 없고, 근태 판정은 노동법 영역이라 임의로 넣으면 나중에 바꾸기 어렵다.
     */
    @GetMapping("/attendances")
    public ApiResponse<List<AttendanceRow>> attendances(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam(required = false) Long enrollmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.success(
                staffAttendanceService.search(me, academyId, enrollmentId, from, to)
                        .stream().map(AttendanceRow::from).toList());
    }

    public record RegisterRequest(Long academyId,
                                  @NotBlank @Size(max = 20) String name,
                                  @Size(max = 20) String phone,
                                  @Size(max = 10) String rfidNo) {
    }

    public record CardRequest(@Size(max = 10) String rfidNo) {
    }

    /** @param studentNo 키오스크 키패드에 찍는 번호. 뒤 4자리가 9000번대다 */
    public record StaffRow(Long enrollmentId, String name, String phone,
                           String studentNo, String rfidNo) {

        static StaffRow from(StudentEnrollment e) {
            return new StaffRow(e.getId(), e.getStudent().getName(),
                    e.getStudent().getPhone(), e.getStudentNo(), e.getRfidNo());
        }
    }

    /** @param eventType {@code IN} 출근 / {@code OUT} 퇴근 */
    public record AttendanceRow(Long enrollmentId, String name, String studentNo,
                                LocalDate workDate, String eventType, Instant recordedAt) {

        static AttendanceRow from(StaffAttendance a) {
            return new AttendanceRow(a.getEnrollment().getId(),
                    a.getEnrollment().getStudent().getName(),
                    a.getEnrollment().getStudentNo(),
                    a.getWorkDate(), a.getEventType().name(), a.getRecordedAt());
        }
    }
}
