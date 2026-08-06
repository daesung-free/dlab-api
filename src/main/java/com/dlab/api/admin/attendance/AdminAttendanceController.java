package com.dlab.api.admin.attendance;

import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.service.AttendanceBoardService;
import com.dlab.domain.attendance.service.StudyTimeRecalculationService;
import com.dlab.domain.attendance.service.AttendanceBoardService.AttendanceRow;
import com.dlab.domain.attendance.service.AttendanceBoardService.ScreenStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 웹 출결 현황 (F-4.3-1).
 *
 * <p>화면은 <b>조회 전용</b>이다 — 승인·반려는 사유신청 화면(F-4.1-6)이 맡는다.
 */
@RestController
@RequestMapping("/api/v1/admin/attendance")
@RequiredArgsConstructor
public class AdminAttendanceController {

    private final AttendanceBoardService boardService;
    private final StudyTimeRecalculationService recalculationService;

    /**
     * 일별 출결 현황.
     *
     * <p><b>재원생 전원이 나온다.</b> 태깅한 학생만 주면 결석자가 목록에서 사라져
     * "오늘 결석 몇 명"을 셀 수 없다.
     *
     * @param classId 담당 반. 담임은 자기 반만 본다
     */
    @GetMapping
    public ApiResponse<BoardResponse> board(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) List<ScreenStatus> statuses,
            @RequestParam(required = false) String keyword) {

        List<AttendanceRow> rows = boardService.board(
                me, date == null ? LocalDate.now() : date, classId);

        List<AttendanceRow> filtered = rows.stream()
                .filter(r -> statuses == null || statuses.isEmpty() || statuses.contains(r.status()))
                .filter(r -> matchesKeyword(r, keyword))
                .toList();

        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(new BoardResponse(
                filtered.stream().map(r -> RowResponse.of(r, raw)).toList(),
                summary(filtered),
                !raw));
    }

    /** 이름·학번·좌석 통합 검색. 화면 검색창이 셋을 한 칸에서 받는다. */
    private boolean matchesKeyword(AttendanceRow r, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim();
        return contains(r.name(), kw) || contains(r.studentNo(), kw) || contains(r.seatCd(), kw);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    /** 화면 상단 통계 6칸. 조회된 목록 기준이라 필터를 걸면 같이 줄어든다. */
    private Map<String, Long> summary(List<AttendanceRow> rows) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("total", (long) rows.size());
        for (ScreenStatus s : ScreenStatus.values()) {
            counts.put(s.name(), rows.stream().filter(r -> r.status() == s).count());
        }
        // EXCUSED는 상태가 아니라 플래그다 — 별도로 센다
        counts.put("EXCUSED", rows.stream().filter(AttendanceRow::excused).count());
        return counts;
    }

    /**
     * @param masked 개인정보가 마스킹됐는지. 화면이 모르면 "번호가 잘못 저장됐다"는 오인 문의가 생긴다
     */
    public record BoardResponse(List<RowResponse> rows, Map<String, Long> summary, boolean masked) {
    }

    /**
     * @param studyTime {@code "N시간 MM분"} 문자열. 화면이 그대로 찍는다
     * @param excused   사유 승인 여부. 상태와 <b>직교하는 축</b>이라 따로 내린다
     */
    public record RowResponse(
            Long enrollmentId,
            String studentNo,
            String name,
            String className,
            String seatCd,
            LocalTime checkInAt,
            LocalTime checkOutAt,
            ScreenStatus status,
            boolean excused,
            int studyMinutes,
            String studyTime,
            String guardianPhone,
            boolean unexcusedLate
    ) {
        static RowResponse of(AttendanceRow r, boolean raw) {
            return new RowResponse(
                    r.enrollmentId(), r.studentNo(),
                    raw ? r.name() : Masking.name(r.name()),
                    r.className(), r.seatCd(),
                    r.checkInAt(), r.checkOutAt(),
                    r.status(), r.excused(),
                    r.studyMinutes(), r.studyTimeLabel(),
                    raw ? r.guardianPhone() : Masking.phone(r.guardianPhone()),
                    r.unexcusedLate());
        }
    }

    /**
     * 학습시간 일괄계산 (화면 버튼).
     *
     * <p>순공시간은 배치가 매일 저장한다. 저장했기 때문에 <b>나중의 정정이 자동으로
     * 반영되지 않아</b> 관리자가 다시 돌릴 수 있어야 한다 — 출결을 수정했거나
     * 교시(급식·쉬는시간)를 바꾼 경우다.
     *
     * <p><b>오늘은 대상이 아니다.</b> 아직 하원 전이라 값이 계속 늘어나므로 저장할 시점이 아니고,
     * 조회 화면이 그날치만 즉석 계산한다.
     */
    @PostMapping("/study-time/recalculate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_ADMIN')")
    public ApiResponse<RecalculationResult> recalculate(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(
                new RecalculationResult(recalculationService.recalculate(me, from, to)));
    }

    /** @param updated 다시 계산한 행 수. 0이면 확정된 날이 없다는 뜻이다 */
    public record RecalculationResult(int updated) {
    }

    /**
     * 출결 현황 엑셀 다운로드 (화면 버튼).
     *
     * <p><b>연락처 마스킹이 기본 ON</b>이다. 화면은 토글로 볼 수 있지만 파일은
     * 회수가 안 되므로 기준을 높게 잡는다 — {@code unmask=true}는 상위 관리자에게만 먹는다.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long classId,
            @RequestParam(defaultValue = "false") boolean unmask) {

        byte[] body = boardService.export(
                me, date == null ? LocalDate.now() : date, classId, unmask);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // 한글 파일명이 깨지지 않게 RFC 5987 인코딩
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''%EC%B6%9C%EA%B2%B0_%ED%98%84%ED%99%A9.xlsx")
                .body(body);
    }
}
