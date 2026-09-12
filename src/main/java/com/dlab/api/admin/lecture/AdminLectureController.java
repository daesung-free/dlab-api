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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 특강 기초 설정 · 명단 · 출석부 (F-4.10-4 · F-4.7).
 *
 * <p>결제는 붙어 있지 않다 — 0803 답변서가 *"신청+결제 검토 중"*이고 {@code payment} 도메인이 없다.
 */
@Tag(name = "관리자 · 특강 (F-4.7)")
@RestController
@RequestMapping("/api/v1/admin/lectures")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminLectureController {

    private final LectureService lectureService;

    // ── 기초 설정 (F-4.10-4) ─────────────────────────────────────

    /** 특강 목록. 지점·연도 범위가 걸린다. */
    @GetMapping
    public ApiResponse<List<LectureResponse.LectureDetail>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam Integer year,
            @RequestParam(required = false) LectureStatus status) {
        return ApiResponse.success(
                lectureService.findAll(academyId, year.shortValue(), status, me).stream()
                        .map(l -> LectureResponse.LectureDetail.withHeadcount(
                                l, lectureService.headcount(l.getId())))
                        .toList());
    }

    /** 특강 등록. 회차는 따로 추가한다 — 회차 없는 특강은 신청을 받을 수 없다. */
    @PostMapping
    public ApiResponse<LectureResponse.LectureDetail> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody LectureRequests.LectureCreate request) {
        return ApiResponse.success(LectureResponse.LectureDetail.from(lectureService.create(
                request.academyId(), request.year().shortValue(),
                request.lectureType(), request.name(), request.categoryId(), me)));
    }

    /** 특강 수정. 비운 항목은 변경하지 않는다. */
    @PatchMapping("/{lectureId}")
    public ApiResponse<LectureResponse.LectureDetail> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.LectureUpdate request) {
        return ApiResponse.success(LectureResponse.LectureDetail.from(lectureService.update(
                lectureId, request.name(), request.code(),
                request.description(), request.capacity(),
                request.applyFrom(), request.applyTo(), request.startDate(), request.endDate(),
                request.fee(), request.teacherId(), request.categoryId(), me)));
    }

    /**
     * 접수 상태 변경.
     *
     * <p>닫으면 앱에서 신청이 막힌다. <b>이미 신청한 건은 그대로 남는다</b> —
     * 상태는 "지금 받는가"이지 "누가 신청했는가"가 아니다.
     */
    @PutMapping("/{lectureId}/status")
    public ApiResponse<LectureResponse.LectureDetail> changeStatus(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.LectureChangeStatus request) {
        return ApiResponse.success(LectureResponse.LectureDetail.from(
                lectureService.changeStatus(lectureId, request.status(), me)));
    }

    /** 앱 노출 전환 (0803 "개설 시에만 노출"). 상태와 별개 축이다. */
    @PutMapping("/{lectureId}/visible")
    public ApiResponse<LectureResponse.LectureDetail> changeVisible(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.ChangeVisible request) {
        return ApiResponse.success(LectureResponse.LectureDetail.from(
                lectureService.changeVisible(lectureId, request.visible(), me)));
    }

    // ── 회차 ─────────────────────────────────────────────────────

    /** 회차 목록. */
    @GetMapping("/{lectureId}/sessions")
    public ApiResponse<List<LectureResponse.Session>> sessions(
            @CurrentAccount AuthPrincipal me, @PathVariable Long lectureId) {
        return ApiResponse.success(lectureService.sessions(lectureId, me).stream()
                .map(LectureResponse.Session::from).toList());
    }

    /** 회차 추가. 출석부가 회차 단위로 만들어진다. */
    @PostMapping("/{lectureId}/sessions")
    public ApiResponse<LectureResponse.Session> addSession(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureRequests.AddSession request) {
        return ApiResponse.success(LectureResponse.Session.from(lectureService.addSession(
                lectureId, request.sessionDate(), request.startTime(), request.endTime(),
                request.room(), me)));
    }

    /**
     * 회차 삭제.
     *
     * <p>개설 폼이 기간·요일로 회차를 한 번에 만드는 구조라 날짜를 잘못 넣으면
     * 그만큼 쌓이는데, <b>되돌릴 방법이 없었다.</b>
     *
     * <p>출결이 찍힌 회차는 400 이다 — 지우면 그날 누가 왔는지가 사라진다.
     * 번호는 다시 매기지 않는다(이미 안내된 "3회차" 가 다른 날을 가리키게 된다).
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long sessionId) {
        lectureService.deleteSession(sessionId, me);
        return ApiResponse.empty();
    }

    /**
     * 특강 삭제.
     *
     * <p><b>신청자가 있으면 400 이다</b> — 지우면 그 학생의 신청 이력이 사라진다.
     * 그때는 상태를 {@code CANCELED} 로 두면 목록에 남되 신청이 막힌다.
     *
     * <p>지울 수 있는 것은 <b>만들어만 두고 아무도 신청하지 않은 것</b>뿐이다.
     * 딸린 회차도 함께 지운다 — 특강만 지우면 회차가 유령으로 남는다.
     */
    @DeleteMapping("/{lectureId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long lectureId) {
        lectureService.delete(lectureId, me);
        return ApiResponse.empty();
    }

    // ── 명단 (F-4.7) ─────────────────────────────────────────────

    /**
     * 신청자·대기자 명단. 대기 순번은 <b>목록 순서</b>다.
     *
     * <p>연락처는 기본이 마스킹이고 {@code unmask=true}는 <b>상위 관리자에게만</b> 먹는다 —
     * 다른 목록과 같은 규칙이다.
     *
     * <p>⚠️ <b>수납 여부는 아직 못 내린다.</b> 특강비 청구를 만드는 경로가 없어
     * ({@code BillingType.LECTURE}를 쓰는 곳이 하나도 없다) 실을 값 자체가 없다.
     * 청구 발행이 붙을 때 같이 나온다.
     */
    @GetMapping("/{lectureId}/applications")
    public ApiResponse<List<LectureResponse.RosterRow>> roster(
            @CurrentAccount AuthPrincipal me, @PathVariable Long lectureId,
            @RequestParam(defaultValue = "false") boolean unmask) {
        boolean raw = unmask && com.dlab.common.privacy.PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(lectureService.rosterDetailed(lectureId, me).stream()
                .map(e -> LectureResponse.RosterRow.of(e, raw)).toList());
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

    /** 회차 출석부. */
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
