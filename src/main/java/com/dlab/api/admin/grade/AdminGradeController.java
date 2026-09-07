package com.dlab.api.admin.grade;

import com.dlab.api.app.grade.GradeResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.grade.service.ExamFormService;
import com.dlab.domain.grade.service.StudentGradeService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.StudentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 성적 양식 관리 · 학생 성적 조회 (F-4.1).
 *
 * <h2>★ 양식은 해마다 넣어야 한다</h2>
 * 마이그레이션에 2026년분만 들어 있다. 연도가 바뀌면 여기서 새로 등록하지 않는 한
 * <b>그 해 가입자가 전부 성적을 못 낸다.</b> 기수 시작 전 체크리스트 항목이다.
 *
 * <h2>성적은 승인 심사 자료다</h2>
 * 가입 승인 화면에서 이 성적을 함께 본다 — 학생이 적은 값이 실제 성적표와 맞는지
 * 대조하는 것이 승인의 실질적 내용이다. <b>관리자는 수정하지 않는다</b>:
 * 고치면 학생이 무엇을 냈는지 알 수 없게 되고, 틀렸으면 반려하고 다시 받는다.
 */
@Tag(name = "관리자 · 성적 양식·조회 (F-4.1)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminGradeController {

    private final ExamFormAdminService examFormAdminService;
    private final ExamFormService examFormService;
    private final StudentGradeService gradeService;
    private final StudentService studentService;

    /**
     * 등록된 시험 회차 목록.
     *
     * @param academyId 비우면 전 지점 공통 행. 지점 관리자는 자기 지점 ID를 넣어야 한다
     */
    @GetMapping("/exam-forms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<ExamFormRequests.FormView>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId) {

        return ApiResponse.success(examFormAdminService.list(me, year, academyId).stream()
                .map(ExamFormRequests.FormView::from).toList());
    }

    /**
     * 시험 회차 + 과목 등록. <b>과목 없이는 만들 수 없다</b> —
     * 만들면 학생 화면에 제목만 있고 입력 칸이 없는 빈 표가 그려진다.
     */
    @PostMapping("/exam-forms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<ExamFormRequests.FormView> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody ExamFormRequests.ExamFormCreate request) {

        return ApiResponse.success(ExamFormRequests.FormView.from(
                examFormAdminService.create(me, request.toCommand())));
    }

    /**
     * 회차 삭제. <b>이미 낸 성적은 남는다</b> — 새 학생 양식에서만 빠진다.
     * 물리 삭제하면 과거 성적이 어느 시험이었는지 알 수 없게 된다.
     */
    @DeleteMapping("/exam-forms/{examMasterId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long examMasterId) {
        examFormAdminService.delete(me, examMasterId);
        return ApiResponse.empty();
    }

    /**
     * 학생이 낸 성적.
     *
     * <p>{@code examSkipped}가 {@code true}면 <b>"모른다"고 체크한 것</b>이지 미입력이
     * 아니다 — 사유가 함께 온다. 승인 심사에서 이 둘을 같게 취급하지 말 것.
     */
    @GetMapping("/students/{enrollmentId}/grades")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<GradeResponse.Submission> studentGrades(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {

        // 지점 검사가 여기 들어 있다 — 없으면 enrollmentId만 바꿔 남의 지점 성적이 열린다
        StudentEnrollment enrollment = studentService.get(enrollmentId, me);
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.of(enrollment), examFormService.formOf(enrollment)));
    }

    // ── 직원 수정 (0826 회신 · API_GAPS 12-1) ──────────────────

    /**
     * 내신 성적 직원 수정.
     *
     * <p>0826 회신이 <i>"처음 입력시 학생, 이후 수정시에는 직원을 통해서"</i>로 정했다.
     * 학생이 앱을 못 쓰거나 잘못 넣은 값을 고칠 경로가 없었다.
     *
     * <p><b>누가 고쳤는지 남는다</b>({@code modifiedBy}) — 이 값이 장학 취소 판정의
     * 근거라, 학생 입력값을 직원이 고쳤다면 그 사실이 드러나야 한다.
     */
    @PutMapping("/students/{enrollmentId}/grades/school-record")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<GradeResponse.Submission> updateSchoolRecord(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody com.dlab.api.app.grade.GradeRequests.SchoolRecord request) {

        StudentEnrollment enrollment = studentService.get(enrollmentId, me);
        gradeService.saveSchoolRecord(enrollment, request.mainSubjectAverage());
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.markModified(enrollment, me.accountId()),
                examFormService.formOf(enrollment)));
    }

    /**
     * 모의고사 성적 직원 수정. <b>보낸 회차만</b> 교체된다 — 앱과 같은 규칙이다.
     */
    @PutMapping("/students/{enrollmentId}/grades/exam-scores")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<GradeResponse.Submission> updateExamScores(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody com.dlab.api.app.grade.GradeRequests.ExamScores request) {

        StudentEnrollment enrollment = studentService.get(enrollmentId, me);
        var inputs = request.scores().stream()
                .map(sc -> new com.dlab.domain.grade.service.StudentGradeService.ScoreInput(
                        sc.examSubjectId(), sc.standardScore(), sc.percentile(), sc.gradeLevel()))
                .toList();

        gradeService.saveExamScores(enrollment, inputs);
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.markModified(enrollment, me.accountId()),
                examFormService.formOf(enrollment)));
    }
}
