package com.dlab.api.app.grade;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.grade.service.ExamFormService;
import com.dlab.domain.grade.service.StudentGradeService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 성적 입력·조회 (앱 A-2).
 *
 * <h2>가입과 분리된 별도 흐름이다</h2>
 * 가입 요청에 성적을 싣지 않는다 — 시험 3회차 × 과목 6개까지 되는 긴 입력이라
 * 중간에 실패하면 <b>휴대폰 인증부터 다시</b> 해야 한다. 가입을 끝내고 여기로 이어진다.
 *
 * <h2>입력은 학생 본인만 한다</h2>
 * 조회는 학부모도 하지만 제출은 본인이다. 성적은 상담·반배정의 기초자료라
 * 학부모가 대신 적으면 학생이 모르는 값으로 상담이 진행된다.
 *
 * <h2>모의고사는 자동조회가 아니다</h2>
 * 더프리미엄 API 연동은 <b>재원 중 모의고사 성적</b>을 가져오는 것이고, 여기서 받는 건
 * <b>입학 전</b> 성적이라 우리 시스템에 응시 기록이 없다. 두 경로를 합치지 말 것.
 */
@Tag(name = "앱 · 성적 입력 (A-2)")
@RestController
@RequestMapping("/api/v1/app/grades")
@RequiredArgsConstructor
public class AppGradeController {

    private final AppScopeResolver scopeResolver;
    private final ExamFormService examFormService;
    private final StudentGradeService gradeService;

    /**
     * 성적 입력 양식.
     *
     * <p><b>입력 화면을 열기 전에 반드시 받는다.</b> 학년에 따라 시험 회차와 과목이 통째로
     * 다르다 — 예비고2·예비고3은 6·9·10월 학력평가(통합사회·통합과학), N수·현고3은
     * 6·9월 평가원 + 전년도 수능(탐구1·탐구2)이다. <b>앱이 이 구성을 자체 판정하지 말 것.</b>
     */
    @GetMapping("/form")
    public ApiResponse<List<GradeResponse.Form>> form(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(examFormService.formOf(enrollment).stream()
                .map(GradeResponse.Form::from).toList());
    }

    /** 내가 낸 성적. 아직 안 냈으면 빈 값으로 내려온다 — 앱이 분기하지 않게. */
    @GetMapping
    public ApiResponse<GradeResponse.Submission> mine(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.mine(enrollment), examFormService.formOf(enrollment)));
    }

    /**
     * 내신 저장 — 주요교과평균 한 칸.
     *
     * <p>등급으로 적는 학생과 원점수로 적는 학생이 섞이므로 <b>서버는 단위를 판정하지
     * 않는다.</b> 신상기록부에도 단위 표기가 없고, 해석은 상담 교사가 한다.
     */
    @PutMapping("/school-record")
    public ApiResponse<GradeResponse.Submission> saveSchoolRecord(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody GradeRequests.SchoolRecord request) {

        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "성적 입력");
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.saveSchoolRecord(enrollment, request.mainSubjectAverage()),
                examFormService.formOf(enrollment)));
    }

    /**
     * 모의고사 성적 저장. <b>보낸 회차만</b> 교체되고 나머지는 그대로 남는다.
     *
     * <p>회차별로 나눠 낼 수 있어야 한다 — 6월만 아는 학생이 9월·10월까지 채워야
     * 저장되는 구조면 아무것도 못 낸다.
     *
     * <p>양식에 없는 칸(한국사 표준점수 등)은 <b>조용히 버린다.</b> 오류로 막으면
     * 앱이 빈 칸을 함께 보내는 흔한 구현에서 저장 자체가 실패한다.
     */
    @PutMapping("/exam-scores")
    public ApiResponse<GradeResponse.Submission> saveExamScores(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody GradeRequests.ExamScores request) {

        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "성적 입력");
        List<StudentGradeService.ScoreInput> inputs = request.scores().stream()
                .map(s -> new StudentGradeService.ScoreInput(
                        s.examSubjectId(), s.standardScore(), s.percentile(), s.gradeLevel()))
                .toList();

        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.saveExamScores(enrollment, inputs),
                examFormService.formOf(enrollment)));
    }

    /**
     * 모의고사 성적을 모른다고 체크.
     *
     * <p>가입 시점에 성적표가 없는 학생이 실제로 있다(자퇴·검정고시·분실). 필수로 막으면
     * 가입 자체를 못 하고, 0을 채우게 두면 <b>통계에서 진짜 0점과 구분되지 않는다.</b>
     *
     * <p>이미 넣어둔 점수는 <b>지워진다</b> — "모른다고 했는데 점수가 있는" 상태를 남기면
     * 상담 화면이 무엇을 믿어야 할지 정해지지 않는다.
     */
    @PostMapping("/exam-scores/skip")
    public ApiResponse<GradeResponse.Submission> skipExams(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody GradeRequests.SkipExams request) {

        StudentEnrollment enrollment = scopeResolver.requireStudent(me.accountId(), "성적 입력");
        return ApiResponse.success(GradeResponse.Submission.from(
                gradeService.skipExams(enrollment, request.reason()),
                examFormService.formOf(enrollment)));
    }
}
