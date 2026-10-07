package com.dlab.api.app.attendance;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.attendance.service.AttendanceQueryService;
import jakarta.validation.Valid;
import com.dlab.domain.user.service.AppScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 — 출결 · 상벌점 · 사유출결 조회 (A-18).
 *
 * <p><b>학생과 학부모가 같은 엔드포인트를 쓴다.</b> A-18 사용자가 "학생·학부모"이고
 * 보는 데이터도 같다 — 다른 건 <b>누구 것을 보느냐</b>뿐이다.
 *
 * <p><b>★ 학부모는 {@code studentId}를 넘기고, 서버가 자녀인지 매번 검증한다.</b>
 * 검증을 빠뜨리면 <b>남의 자녀 ID를 넣는 것만으로 그 학생의 출결·상벌점이 열린다.</b>
 * 지점 필터(SearchScope)와 같은 급의 규칙이다.
 *
 * <p><b>태깅은 키오스크가, 정정은 관리자 웹이 한다.</b> 앱이 쓰는 것은 사유 신청뿐이다.
 */
@Tag(name = "앱 · 출결·상벌점 조회 (A-18)")
@RestController
@RequestMapping("/api/v1/app/attendance")
@RequiredArgsConstructor
public class AppAttendanceController {

    private final AttendanceQueryService attendanceQueryService;
    private final AbsenceReasonService absenceReasonService;
    private final com.dlab.domain.attendance.repository.AbsenceReasonCategoryRepository categoryRepository;
    private final AppScopeResolver scopeResolver;
    private final java.time.Clock clock;

    /**
     * QR 화면 갱신 간격.
     *
     * <p>★ <b>보안값이 아니다</b> — 서버가 이 시각을 검증하지 않는다(D-2 미확정).
     * 동적으로 확정되면 실제 만료가 되고, 그때 값만 조정하면 된다.
     */
    private static final java.time.Duration QR_REFRESH = java.time.Duration.ofMinutes(1);

    /**
     * 기간 출결 — 월 달력·이력 화면.
     *
     * @param studentId 학부모가 자녀를 지정할 때만 쓴다. 학생 본인은 넘기지 않는다
     */
    @GetMapping
    public ApiResponse<List<AttendanceResponse.Daily>> daily(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(attendanceQueryService.daily(enrollmentId, from, to).stream()
                .map(AttendanceResponse.Daily::from).toList());
    }

    /**
     * 기간 출결 요약 — 순공·출석률·지각·결석·조퇴·외출 건수.
     *
     * <p>목록(`GET /app/attendance`)과 따로 둔다. 화면이 일별 이벤트를 받아 직접 세면
     * <b>집계 기준이 앱에 박히고</b> 월 하나를 보려고 전 기간을 받아와야 한다.
     */
    @GetMapping("/summary")
    public ApiResponse<AttendanceResponse.Summary> summary(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(AttendanceResponse.Summary.from(
                attendanceQueryService.summarize(enrollmentId, from, to)));
    }

    /**
     * 상벌점 — 누적 점수 + 내역. 벌점은 음수로 내려간다.
     *
     * <p>{@code from}/{@code to}를 주면 <b>그 기간 내역</b>과 <b>기간 증감</b>이 함께 온다.
     * 누적 점수({@code total})는 기간과 무관하게 항상 전체다 — 제적 기준이 누적이라서다.
     *
     * @param from 비우면 전체 기간. {@code to}와 함께 주어야 한다
     */
    @GetMapping("/penalties")
    public ApiResponse<AttendanceResponse.Penalties> penalties(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(AttendanceResponse.Penalties.from(
                attendanceQueryService.penalties(enrollmentId, from, to)));
    }

    /** 사유출결 — A-18의 "당월 사유출결/벌점 표". */
    @GetMapping("/absence-reasons")
    public ApiResponse<List<AttendanceResponse.AbsenceReasonRow>> absenceReasons(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(
                attendanceQueryService.absenceReasons(enrollmentId, from, to).stream()
                        .map(AttendanceResponse.AbsenceReasonRow::from).toList());
    }

    /**
     * 사유 제출 (P1-08 · S-6).
     *
     * <p><b>★ 학생 본인만 낼 수 있다.</b> 학부모는 이 신청의 <b>승인자</b>다 —
     * 학부모가 내고 학부모가 승인하면 승인 절차 자체가 무의미해진다. 그래서 조회와 달리
     * {@code studentId}를 받지 않는다.
     *
     * <p>제출하면 <b>관리자 직접 등록과 같은 승인 라우팅</b>을 탄다(승인 주체·타임아웃·
     * 에스컬레이션). 승인 로직을 여기서 다시 만들지 않는다.
     */
    @PostMapping("/absence-reasons")
    public ApiResponse<AttendanceResponse.AbsenceReasonRow> submitAbsenceReason(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody AbsenceReasonRequests.AbsenceReasonSubmit request) {

        Long enrollmentId = scopeResolver.requireStudent(me.accountId(), "사유 신청").getId();
        return ApiResponse.success(AttendanceResponse.AbsenceReasonRow.from(
                absenceReasonService.submitByStudent(enrollmentId, request.date(),
                        request.type(), request.reasonText(),
                        request.startTime(), request.endTime(), request.categoryId())));
    }

    /**
     * 사유 카테고리 목록 — 신청 화면 드롭다운.
     *
     * <p><b>켜져 있는 것만</b> 내린다. 꺼둔 항목은 관리 화면에서만 보인다.
     *
     * <p>비어 있을 수 있다 — 아직 등록된 카테고리가 없으면 앱은 사유란만 보여주면 된다.
     */
    @GetMapping("/absence-reasons/categories")
    public ApiResponse<List<AttendanceResponse.AbsenceCategory>> absenceCategories(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        var enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(categoryRepository
                .findUsable(enrollment.getAcademy().getId(), enrollment.getYear(), true)
                .stream().map(AttendanceResponse.AbsenceCategory::from).toList());
    }

    /** 사유 취소 — 승인 전까지만. 승인·반려된 건은 이력이라 관리자가 정정한다. */
    @DeleteMapping("/absence-reasons/{reasonId}")
    public ApiResponse<Void> cancelAbsenceReason(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long reasonId) {
        absenceReasonService.cancelByStudent(
                scopeResolver.requireStudent(me.accountId(), "사유 신청").getId(), reasonId);
        return ApiResponse.empty();
    }


    /**
     * 출결 QR 발급 (A-4) — <b>카드를 안 가져온 학생용 대체 수단</b>.
     *
     * <p>앱이 이 {@code payload} 를 QR 로 그리고, 키오스크가 스캔해 <b>카드와 똑같이</b>
     * {@code POST /kiosk/setAttendStd} 를 호출한다. 그래서 출결 로직에 「QR 인지 카드인지」
     * 분기가 없고 DSA 계약도 그대로다.
     *
     * <h2>⚠️ 지금은 서버가 검증하지 않는다 (D-2 미확정)</h2>
     * 요구사항은 <b>동적</b> QR 인데 토큰 검증 방식이 아직 정해지지 않았다. 지침이
     * 「확정 전까지 발급 인터페이스만 만들고 검증은 목업」이라 <b>payload 에 카드번호를
     * 그대로 담는다.</b>
     *
     * <p>★ <b>그래서 캡처해 남에게 보내면 대리출석이 된다.</b> 알려진 트레이드오프이고,
     * 동적으로 가기로 하면 <b>이 메서드의 payload 생성만 바뀐다</b> — 앱·키오스크 계약은
     * 그대로다(키오스크 단말 검증부는 당사 개발이라 고칠 수 있다).
     *
     * <h2>★ 학생 본인만 발급받는다</h2>
     * 학부모에게 열면 <b>집에서 자녀 QR 을 띄워 대리출석</b>이 된다. 출결은 본인이 그 자리에
     * 있었다는 기록이라, 조회는 학부모에게 열어도 발급은 열지 않는다.
     *
     * @return {@code expiresAt} 은 <b>화면 갱신 기준</b>이다 — 서버가 그 시각을 검증하지
     *         않으므로 보안값으로 쓰지 말 것. 동적 확정 시 실제 만료가 된다
     */
    @PostMapping("/qr-token")
    public ApiResponse<QrToken> issueQrToken(@CurrentAccount AuthPrincipal me) {
        var enrollment = scopeResolver.requireStudent(me.accountId(), "출결 QR 발급");

        String rfidNo = enrollment.getRfidNo();
        if (rfidNo == null || rfidNo.isBlank()) {
            // 카드가 발급되지 않은 학생이다. 빈 QR 을 내리면 키오스크에서 학생을 못 찾아
            // "인식이 안 된다"로 보인다 — 원인이 카드 미발급임을 여기서 알려준다
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.INVALID_REQUEST,
                    "발급된 출결 카드가 없어 QR을 만들 수 없습니다. 데스크에 문의해 주세요.");
        }
        return ApiResponse.success(new QrToken(rfidNo,
                java.time.Instant.now(clock).plus(QR_REFRESH), false));
    }

    /**
     * @param payload   QR 에 담는 값. 지금은 카드번호 그대로다
     * @param expiresAt 화면 갱신 기준(위 설명 참고)
     * @param dynamic   <b>서버가 검증하는 동적 토큰인가.</b> 지금은 {@code false} —
     *                  앱이 이 값으로 「보안 QR」 표시 여부를 고를 수 있다
     */
    public record QrToken(String payload, java.time.Instant expiresAt, boolean dynamic) {
    }
}
