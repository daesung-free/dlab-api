package com.dlab.api.admin.admission;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.user.entity.GradeType;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 입학예약 관리 = 대기자 관리 (F-4.2-1).
 *
 * <h2>신청은 이미 들어온다</h2>
 * 홈페이지 수신({@code POST /dlab/setStdInfo})은 규격서대로 동작한다. 없던 것은
 * <b>관리자가 보는 쪽</b>이라 여기만 새로 붙인다.
 *
 * <h2>★ 전환은 「입학확정」에서만 한다</h2>
 * 상담 상태 6종과 재적 상태는 다른 축이고 <b>둘이 만나는 지점이 입학확정 하나</b>다.
 * 아무 상태에서나 되게 두면 상담취소된 신청자에게 학번이 나가고, 학번은 회수되지 않는다.
 *
 * <h2>지점은 파라미터로 받지 않는다</h2>
 * 권한에서 판단한다(§7). 전 지점 권한자면 전부 보이고, 아니면 자기 지점만 보인다.
 */
@Tag(name = "관리자 · 입학예약(대기자) 관리 (F-4.2-1)")
@RestController
@RequestMapping("/api/v1/admin/admission-reservations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminAdmissionReservationController {

    private final AdmissionReservationAdminService adminService;

    /** 목록. 상태·이름·연락처로 좁힌다. */
    @GetMapping
    public ApiResponse<List<ReservationView>> list(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam short year,
                                                   @RequestParam(required = false) ConsultStatus status,
                                                   @RequestParam(required = false) String keyword) {
        return ApiResponse.success(adminService.search(me, year, status, keyword)
                .stream().map(ReservationView::from).toList());
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationView> detail(@CurrentAccount AuthPrincipal me,
                                               @PathVariable Long reservationId) {
        return ApiResponse.success(ReservationView.from(adminService.get(me, reservationId)));
    }

    /** 상태 변경. <b>이력이 남는다</b> — 없으면 "왜 미등록으로 바뀌었나" 에 답할 수 없다. */
    @PostMapping("/{reservationId}/status")
    public ApiResponse<ReservationView> changeStatus(@CurrentAccount AuthPrincipal me,
                                                     @PathVariable Long reservationId,
                                                     @Valid @RequestBody ChangeStatus request) {
        return ApiResponse.success(ReservationView.from(adminService.changeStatus(
                me, reservationId, request.status(), request.reason())));
    }

    @GetMapping("/{reservationId}/status-logs")
    public ApiResponse<List<StatusLogView>> statusLogs(@CurrentAccount AuthPrincipal me,
                                                       @PathVariable Long reservationId) {
        return ApiResponse.success(adminService.statusLogs(me, reservationId)
                .stream().map(StatusLogView::from).toList());
    }

    /** 메모. <b>지점 메모는 그 지점만, 본사는 전체</b>를 본다. */
    @GetMapping("/{reservationId}/memos")
    public ApiResponse<List<MemoView>> memos(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long reservationId) {
        return ApiResponse.success(adminService.memos(me, reservationId)
                .stream().map(MemoView::from).toList());
    }

    @PostMapping("/{reservationId}/memos")
    public ApiResponse<MemoView> addMemo(@CurrentAccount AuthPrincipal me,
                                         @PathVariable Long reservationId,
                                         @Valid @RequestBody SaveMemo request) {
        return ApiResponse.success(MemoView.from(
                adminService.addMemo(me, reservationId, request.content())));
    }

    @DeleteMapping("/memos/{memoId}")
    public ApiResponse<Void> deleteMemo(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long memoId) {
        adminService.deleteMemo(me, memoId);
        return ApiResponse.empty();
    }

    /**
     * 정식 접수 전환 — 신청자를 학생으로 만든다 (F-4.1-4).
     *
     * <p>★ <b>「입학확정」 상태에서만</b> 되고, <b>한 번만</b> 된다. 학번 채번은 기존 신규
     * 접수 경로를 그대로 탄다.
     *
     * @param grade 신청서의 학년 표기(1·2·3·N)로 판단되지 않을 때만 지정한다
     */
    @PostMapping("/{reservationId}/convert")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<ConvertedView> convert(@CurrentAccount AuthPrincipal me,
                                              @PathVariable Long reservationId,
                                              @RequestBody(required = false) Convert request) {
        var enrollment = adminService.convert(me, reservationId,
                request == null ? null : request.grade(),
                request == null ? null : request.admissionDate());
        return ApiResponse.success(new ConvertedView(enrollment.getId(),
                enrollment.getStudentNo(), enrollment.getStudent().getName()));
    }

    public record ChangeStatus(@NotNull(message = "상태는 필수입니다.") ConsultStatus status,
                               @Size(max = 500) String reason) {
    }

    public record SaveMemo(@NotBlank(message = "내용은 필수입니다.")
                           @Size(max = 2000) String content) {
    }

    public record Convert(GradeType grade, LocalDate admissionDate) {
    }

    /**
     * @param statusName 화면에 그대로 쓰는 표기. 하드코딩하면 화면마다 갈린다
     * @param converted  이미 학생으로 전환됐는가. 버튼을 감추는 근거다
     */
    public record ReservationView(Long id, String rsvCd, short year, Long academyId,
                                  String studentName, String studentTel, String parentTel,
                                  String stdGrade, String schoolName,
                                  ConsultStatus status, String statusName,
                                  boolean converted, Long enrollmentId, String studentNo,
                                  Instant createdAt) {

        static ReservationView from(AdmissionReservation r) {
            return new ReservationView(r.getId(), r.getRsvCd(), r.getYear(),
                    r.getAcademy().getId(), r.getStudentName(), r.getStudentTel(),
                    r.getParentTel(), r.getStdGrade(), r.getSchNmHigh(),
                    r.getConsultStatus(), r.getConsultStatus().displayName(),
                    r.converted(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getId(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getStudentNo(),
                    r.getCreatedAt());
        }
    }

    public record StatusLogView(Long id, ConsultStatus fromStatus, ConsultStatus toStatus,
                                String reason, Long changedBy, Instant changedAt) {

        static StatusLogView from(AdmissionReservationStatusLog l) {
            return new StatusLogView(l.getId(), l.getFromStatus(), l.getToStatus(),
                    l.getReason(), l.getCreatedBy(), l.getCreatedAt());
        }
    }

    public record MemoView(Long id, String content, Long writerId, Instant createdAt) {

        static MemoView from(AdmissionReservationMemo m) {
            return new MemoView(m.getId(), m.getContent(), m.getCreatedBy(), m.getCreatedAt());
        }
    }

    public record ConvertedView(Long enrollmentId, String studentNo, String studentName) {
    }
}
