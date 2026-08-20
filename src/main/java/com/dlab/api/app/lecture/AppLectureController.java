package com.dlab.api.app.lecture;

import com.dlab.api.admin.lecture.LectureResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.lecture.service.LectureService;
import com.dlab.domain.user.entity.StudentEnrollment;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 학생 앱 — 특강 목록 조회 · 신청 · 신청 내역 (A-15).
 *
 * <p><b>결제는 없다</b> — 0803 답변서가 *"신청+결제 검토 중"*이다. 금액은 안내로만 내려간다.
 *
 * <p>목록은 <b>본인 지점·올해 것만</b> 나온다. 지점을 요청 파라미터로 받지 않는 이유는
 * 클라이언트가 값을 바꿔 보내는 것만으로 다른 지점 특강이 열리기 때문이다.
 */
@Tag(name = "앱 · 특강 (A-15)")
@RestController
@RequestMapping("/api/v1/app/lectures")
@RequiredArgsConstructor
public class AppLectureController {

    private final LectureService lectureService;

    /** 신청할 수 있는 특강 목록 (A-15). 정원이 찬 것도 나오되 마감으로 표시된다. */
    @GetMapping
    public ApiResponse<List<LectureResponse.Detail>> list(@CurrentAccount AuthPrincipal me) {
        StudentEnrollment enrollment = lectureService.currentEnrollmentOf(me.accountId());
        return ApiResponse.success(
                lectureService.findVisible(enrollment.getAcademy().getId(), enrollment.getYear())
                        .stream()
                        .map(l -> LectureResponse.Detail.withHeadcount(
                                l, lectureService.headcount(l.getId())))
                        .toList());
    }

    /**
     * 신청. <b>정원이 차 있으면 대기로 들어간다</b> — 실패가 아니라 대기 상태로 응답한다.
     * 응답의 {@code status}로 확정인지 대기인지 구분한다.
     */
    @PostMapping("/{lectureId}/apply")
    public ApiResponse<LectureResponse.RosterRow> apply(@CurrentAccount AuthPrincipal me,
                                                        @PathVariable Long lectureId) {
        StudentEnrollment enrollment = lectureService.currentEnrollmentOf(me.accountId());
        return ApiResponse.success(LectureResponse.RosterRow.from(
                lectureService.apply(lectureId, enrollment.getId())));
    }

    /** 신청 내역 (A-15 "신청 내역 확인"). 취소분도 이력으로 나온다. */
    @GetMapping("/applications")
    public ApiResponse<List<LectureResponse.RosterRow>> myApplications(
            @CurrentAccount AuthPrincipal me) {
        StudentEnrollment enrollment = lectureService.currentEnrollmentOf(me.accountId());
        return ApiResponse.success(lectureService.myApplications(enrollment.getId()).stream()
                .map(LectureResponse.RosterRow::from).toList());
    }

    /** 본인 신청만 취소된다. 확정자가 빠지면 대기 1번이 자동 승격된다. */
    @DeleteMapping("/applications/{applicationId}")
    public ApiResponse<Void> cancel(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long applicationId) {
        StudentEnrollment enrollment = lectureService.currentEnrollmentOf(me.accountId());
        lectureService.cancel(applicationId, enrollment.getId());
        return ApiResponse.empty();
    }
}
