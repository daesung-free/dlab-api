package com.dlab.api.admin.lecture;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.service.LectureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 웹 — 특강 기초 설정 · 명단 · 출석부 (F-4.10-4 · F-4.7).
 *
 * <p>결제는 붙어 있지 않다 — 0803 답변서가 *"신청+결제 검토 중"*이고 {@code payment} 도메인이 없다.
 */
@RestController
@RequestMapping("/api/v1/admin/lectures")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminLectureController {

    private final LectureService lectureService;

    // ── 기초 설정 (F-4.10-4) ─────────────────────────────────────

    @GetMapping
    public ApiResponse<List<LectureResponse.Detail>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam Integer year,
            @RequestParam(required = false) LectureStatus status) {
        return ApiResponse.success(
                lectureService.findAll(academyId, year.shortValue(), status, me).stream()
                        .map(l -> LectureResponse.Detail.withHeadcount(
                                l, lectureService.headcount(l.getId())))
                        .toList());
    }

    @PostMapping
    public ApiResponse<LectureResponse.Detail> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody LectureRequests.Create request) {
        return ApiResponse.success(LectureResponse.Detail.from(lectureService.create(
                request.academyId(), request.year().shortValue(),
                request.lectureType(), request.name(), me)));
    }

    @PatchMapping("/{lectureId}")
    public ApiResponse<LectureResponse.Detail> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.Update request) {
        return ApiResponse.success(LectureResponse.Detail.from(lectureService.update(
                lectureId, request.name(), request.description(), request.capacity(),
                request.applyFrom(), request.applyTo(), request.startDate(), request.endDate(),
                request.fee(), me)));
    }

    @PutMapping("/{lectureId}/status")
    public ApiResponse<LectureResponse.Detail> changeStatus(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.ChangeStatus request) {
        return ApiResponse.success(LectureResponse.Detail.from(
                lectureService.changeStatus(lectureId, request.status(), me)));
    }

    /** 앱 노출 전환 (0803 "개설 시에만 노출"). 상태와 별개 축이다. */
    @PutMapping("/{lectureId}/visible")
    public ApiResponse<LectureResponse.Detail> changeVisible(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.ChangeVisible request) {
        return ApiResponse.success(LectureResponse.Detail.from(
                lectureService.changeVisible(lectureId, request.visible(), me)));
    }

    // ── 회차 ─────────────────────────────────────────────────────

    @GetMapping("/{lectureId}/sessions")
    public ApiResponse<List<LectureResponse.Session>> sessions(
            @CurrentAccount AuthPrincipal me, @PathVariable Long lectureId) {
        return ApiResponse.success(lectureService.sessions(lectureId, me).stream()
                .map(LectureResponse.Session::from).toList());
    }

    @PostMapping("/{lectureId}/sessions")
    public ApiResponse<LectureResponse.Session> addSession(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.AddSession request) {
        return ApiResponse.success(LectureResponse.Session.from(lectureService.addSession(
                lectureId, request.sessionDate(), request.startTime(), request.endTime(),
                request.room(), me)));
    }

    // ── 명단 (F-4.7) ─────────────────────────────────────────────

    /** 신청자·대기자 명단. 대기 순번은 <b>목록 순서</b>다. */
    @GetMapping("/{lectureId}/applications")
    public ApiResponse<List<LectureResponse.RosterRow>> roster(
            @CurrentAccount AuthPrincipal me, @PathVariable Long lectureId) {
        return ApiResponse.success(lectureService.roster(lectureId, me).stream()
                .map(LectureResponse.RosterRow::from).toList());
    }

    /** 대기 → 확정 수동 승격. 정원을 넘겨도 관리자 판단을 존중한다. */
    @PostMapping("/applications/{applicationId}/promote")
    public ApiResponse<LectureResponse.RosterRow> promote(
            @CurrentAccount AuthPrincipal me, @PathVariable Long applicationId) {
        return ApiResponse.success(LectureResponse.RosterRow.from(
                lectureService.promote(applicationId, me)));
    }

    /** 관리자 취소. 확정자가 빠지면 대기 1번이 자동 승격된다. */
    @DeleteMapping("/applications/{applicationId}")
    public ApiResponse<Void> cancel(@PathVariable Long applicationId) {
        lectureService.cancel(applicationId, null);
        return ApiResponse.empty();
    }

    // ── 출석부 (F-4.7) ───────────────────────────────────────────

    /** 출석 대상 — 확정자만. 대기·취소자는 나오지 않는다. */
    @GetMapping("/{lectureId}/attendance-targets")
    public ApiResponse<List<LectureResponse.RosterRow>> attendanceTargets(
            @CurrentAccount AuthPrincipal me, @PathVariable Long lectureId) {
        return ApiResponse.success(lectureService.attendanceTargets(lectureId, me).stream()
                .map(LectureResponse.RosterRow::from).toList());
    }

    @GetMapping("/sessions/{sessionId}/attendances")
    public ApiResponse<List<LectureResponse.Attendance>> attendances(@PathVariable Long sessionId) {
        return ApiResponse.success(lectureService.attendances(sessionId).stream()
                .map(LectureResponse.Attendance::from).toList());
    }

    /** 출석 체크. 같은 회차·같은 신청이면 덮어쓴다 — 정정이 잦은 값이다. */
    @PutMapping("/sessions/{sessionId}/attendances")
    public ApiResponse<LectureResponse.Attendance> markAttendance(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long sessionId,
            @Valid @RequestBody LectureRequests.MarkAttendance request) {
        return ApiResponse.success(LectureResponse.Attendance.from(
                lectureService.markAttendance(sessionId, request.applicationId(),
                        request.status(), request.memo(), me)));
    }
}
