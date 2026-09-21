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
    private final com.dlab.domain.grade.service.MockExamUploadService mockExamUploadService;
    private final com.dlab.domain.grade.service.ExamItemService examItemService;
    private final com.dlab.domain.grade.service.ItemResponseService itemResponseService;
    private final com.dlab.domain.grade.service.AcademyExamQueryService academyExamQueryService;
    private final com.dlab.domain.grade.service.ScoringQueryService scoringQueryService;

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

        var forms = examFormAdminService.list(me, year, academyId);
        // 문항 수는 한 번에 센다 — 회차마다 부르면 쿼리가 회차 수만큼 나간다
        var items = examFormAdminService.itemStats(
                forms.stream().map(com.dlab.domain.grade.entity.ExamMaster::getId).toList());
        return ApiResponse.success(forms.stream()
                .map(f -> ExamFormRequests.FormView.from(f, items.get(f.getId())))
                .toList());
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
     * 학년별 기본 과목 구성.
     *
     * <p>디랩 시험 회차를 만들 때 과목을 비우면 이걸로 채워진다. 화면은 회차 등록 폼에
     * 미리 채워 두는 데 써도 된다.
     *
     * @param academyId 비우면 공통본. 지점을 넣으면 <b>그 지점에 실제로 쓰일</b> 구성이다
     * @param gradeType 비우면 전 학년
     */
    @GetMapping("/exam-forms/subject-presets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<ExamFormRequests.PresetView>> presets(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) com.dlab.domain.user.entity.GradeType gradeType,
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(examFormAdminService.presets(me, year, gradeType, academyId)
                .stream().map(ExamFormRequests.PresetView::from).toList());
    }

    /**
     * 한 학년의 기본 과목 구성을 통째로 바꾼다. <b>이미 만든 회차는 안 바뀐다</b> —
     * 다음에 만드는 회차부터 적용된다.
     */
    @PutMapping("/exam-forms/subject-presets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<List<ExamFormRequests.PresetView>> replacePresets(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody ExamFormRequests.PresetReplace request) {
        return ApiResponse.success(examFormAdminService.replacePresets(me, request.academyId(),
                        request.year(), request.gradeType(),
                        request.subjects().stream()
                                .map(ExamFormRequests.ExamFormSubject::toInput).toList())
                .stream().map(ExamFormRequests.PresetView::from).toList());
    }

    /**
     * 연도 롤오버 — 전년도 입학 전 성적 양식과 기본 과목 구성을 새 해로 복사한다.
     *
     * <p>이미 있는 것은 건너뛰어 두 번 불러도 된다. 디랩 시험 회차는 복사하지 않는다(시행일이
     * 붙은 한 번뿐인 시험이다). 시험 이름의 연도는 올려 주지만 제도 변경(9평 → 8평 등)은
     * 반영하지 않는다 — 복사 후 확인할 것.
     */
    @PostMapping("/exam-forms/rollover")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<ExamFormAdminService.RolloverResult> rollover(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody ExamFormRequests.Rollover request) {
        return ApiResponse.success(examFormAdminService.rollover(me, request.academyId(),
                request.fromYear(), request.toYear()));
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
    /**
     * 모의고사 성적 엑셀 미리보기.
     *
     * <p><b>저장하지 않는다.</b> 605명짜리 파일을 바로 반영하면 매칭이 어긋났을 때 무엇이
     * 잘못 들어갔는지 모른 채 전교생 성적이 바뀐다. 누가 매칭됐고 누가 안 됐는지 먼저 본다.
     *
     * <p>양식은 <b>대성전산이 쓰던 담임용 파일 그대로</b>다 — 우리가 새로 만들지 않는다.
     * 더프리미엄과 평가원이 같은 양식이라 파일 종류를 구분해 올리지 않아도 된다.
     *
     * @param academyId    업로드할 지점. ★ 파일의 학교코드를 지점과 잇는 매핑이 아직 없어
     *                     <b>관리자가 고른다</b>(§4)
     * @param examMasterId 어느 회차 성적인지. 파일에는 회차 정보가 없다
     */
    @PostMapping("/grades/exam-scores/upload/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<com.dlab.domain.grade.service.MockExamUploadService.Preview> previewUpload(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam Long examMasterId,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        return ApiResponse.success(
                mockExamUploadService.preview(me, academyId, examMasterId, file.getInputStream()));
    }

    /**
     * 모의고사 성적 엑셀 반영.
     *
     * <p><b>매칭된 학생만 저장한다.</b> 못 찾은 행 때문에 전체를 되돌리면 한 명 때문에
     * 604명을 다시 올려야 한다 — 못 찾은 행은 응답에 사유와 함께 남는다.
     *
     * <p>같은 회차를 다시 올리면 그 회차 점수가 <b>교체</b>된다. 다른 회차는 건드리지 않는다.
     */
    @PostMapping("/grades/exam-scores/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<com.dlab.domain.grade.service.MockExamUploadService.Preview> upload(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam Long examMasterId,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        return ApiResponse.success(
                mockExamUploadService.apply(me, academyId, examMasterId, file.getInputStream()));
    }

    /**
     * 동명이인 등으로 멈춘 행을 학생에 연결한다.
     *
     * <p>★ <b>한 번 정하면 다음 회차부터 자동이다.</b> 그러지 않으면 같은 학생이 회차마다
     * 미매칭으로 빠지고, 매번 사람이 같은 판단을 다시 해야 한다.
     *
     * <p>⚠️ 반이 바뀌면 파일의 번호 앞자리가 바뀌어 이 연결이 맞지 않게 된다. 그때는
     * 다시 이름 매칭으로 떨어진다 — <b>틀린 학생에게 들어가는 게 아니라 다시 물어본다.</b>
     *
     * @param year 회차 연도. 학번·반이 해마다 초기화되므로 연도까지 묶는다
     */
    @PostMapping("/grades/exam-scores/upload/links")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<MockExamLinkView> linkUploadRow(
            @CurrentAccount AuthPrincipal me,
            @jakarta.validation.Valid @RequestBody MockExamLink request) {
        return ApiResponse.success(MockExamLinkView.from(mockExamUploadService.link(
                me, request.academyId(), request.year(), request.schoolCode(),
                request.classNo(), request.studentNo(), request.enrollmentId())));
    }

    /** 사람이 정해둔 연결 목록. */
    @GetMapping("/grades/exam-scores/upload/links")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<java.util.List<MockExamLinkView>> uploadLinks(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year) {
        return ApiResponse.success(mockExamUploadService.links(me, academyId, year)
                .stream().map(MockExamLinkView::from).toList());
    }

    /** 잘못 이었으면 해제한다. 지우면 다시 이름으로 찾는다. */
    @DeleteMapping("/grades/exam-scores/upload/links/{linkId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<Void> unlinkUploadRow(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long linkId) {
        mockExamUploadService.unlink(me, linkId);
        return ApiResponse.empty();
    }

    /**
     * @param schoolCode 파일의 학교코드(분당 {@code 99700}). 미리보기 응답에 함께 온다
     */
    public record MockExamLink(
            Long academyId,
            @jakarta.validation.constraints.NotNull(message = "연도는 필수입니다.") Short year,
            @jakarta.validation.constraints.NotBlank(message = "학교코드는 필수입니다.")
            @jakarta.validation.constraints.Size(max = 20) String schoolCode,
            @jakarta.validation.constraints.NotBlank(message = "반은 필수입니다.")
            @jakarta.validation.constraints.Size(max = 20) String classNo,
            @jakarta.validation.constraints.NotBlank(message = "번호는 필수입니다.")
            @jakarta.validation.constraints.Size(max = 20) String studentNo,
            @jakarta.validation.constraints.NotNull(message = "학생은 필수입니다.") Long enrollmentId) {
    }

    /**
     * @param fileStudentNo 파일의 번호("반 번호 + 3자리 순번")
     * @param studentNo     우리 학번. 둘은 서로 다른 체계라 같은 칸에 두면 헷갈린다
     */
    public record MockExamLinkView(Long id, short year, String schoolCode, String classNo,
                                   String fileStudentNo, Long enrollmentId, String studentNo,
                                   String studentName) {

        static MockExamLinkView from(com.dlab.domain.grade.entity.MockExamStudentKey k) {
            return new MockExamLinkView(k.getId(), k.getYear(), k.getSchoolCode(),
                    k.getClassNo(), k.getStudentNo(), k.getEnrollment().getId(),
                    k.getEnrollment().getStudentNo(), k.getEnrollment().getStudent().getName());
        }
    }

    /**
     * 회차 문항 정보 반영 — 문항분석표 + 정답률 (채점 탭의 근거).
     *
     * <p><b>디랩에서 본 시험 회차에만</b> 올린다. 다시 올리면 그 회차 문항이 통째로 교체된다.
     *
     * <p>{@code unmatchedRates} 가 비어 있지 않으면 <b>경고할 것</b> — 과목명 표기가 달라져
     * 그 문항의 전국 정답률이 붙지 않았다는 뜻이다.
     *
     * @param rates 정답률 파일. 없어도 된다 — 문항분석표만 먼저 올릴 수 있다
     */
    @PostMapping("/grades/exam-items/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<com.dlab.domain.grade.service.ExamItemService.Result> uploadExamItems(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long examMasterId,
            @RequestPart("analysis") org.springframework.web.multipart.MultipartFile analysis,
            @RequestPart(value = "rates", required = false)
            org.springframework.web.multipart.MultipartFile rates) throws java.io.IOException {
        return ApiResponse.success(examItemService.upload(me, examMasterId,
                analysis.getInputStream(), rates == null ? null : rates.getInputStream()));
    }

    /**
     * 학생 정오·답안 반영 — 정오표(필수) + 답안표(선택). 채점 탭의 근거.
     *
     * <p>★ <b>문항 정보({@code /grades/exam-items/upload})를 먼저 올려야 한다</b> — 국어·수학의
     * 공통·선택 경계를 거기서 안다.
     *
     * <p>학생 매칭은 성적 업로드와 같은 규칙이다(외부생 제외 → 연결 키 → 이름). {@code unknownSubjects}
     * 가 비어 있지 않으면 <b>그 과목 채점이 빠졌다</b> — 경고할 것.
     */
    @PostMapping("/grades/exam-responses/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<com.dlab.domain.grade.service.ItemResponseService.Result> uploadExamResponses(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam Long examMasterId,
            @RequestPart("results") org.springframework.web.multipart.MultipartFile results,
            @RequestPart(value = "answers", required = false)
            org.springframework.web.multipart.MultipartFile answers) throws java.io.IOException {
        return ApiResponse.success(itemResponseService.upload(me, academyId, examMasterId,
                results.getInputStream(), answers == null ? null : answers.getInputStream()));
    }

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

    /**
     * 디랩에서 본 시험 목록 — 성적 업로드로 반영된 회차, 최근순.
     *
     * <p>앱 {@code GET /app/grades/exams} 와 같은 모양이다. 입학 전 성적은 여기 없다 —
     * {@code GET /students/{id}/grades} 가 따로 내린다.
     */
    @GetMapping("/students/{enrollmentId}/grades/exams")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<com.dlab.domain.grade.service.AcademyExamQueryService.ExamSummary>> studentAcademyExams(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {
        // 지점 검사는 studentService.get 에 있다
        return ApiResponse.success(academyExamQueryService.exams(studentService.get(enrollmentId, me)));
    }

    /** 한 회차 — 과목별 성적 + 지망대학 진단. 앱 {@code GET /app/grades/exams/{id}} 와 같은 모양. */
    @GetMapping("/students/{enrollmentId}/grades/exams/{examMasterId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<com.dlab.domain.grade.service.AcademyExamQueryService.ExamDetail> studentAcademyExam(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @PathVariable Long examMasterId) {
        return ApiResponse.success(academyExamQueryService.exam(
                studentService.get(enrollmentId, me), examMasterId));
    }

    /**
     * 채점 — 영역별 맞은 수·틀린 문항·단원별 정답률(전국 대비).
     * 앱 {@code GET /app/grades/exams/{id}/scoring} 과 같은 모양이다.
     */
    @GetMapping("/students/{enrollmentId}/grades/exams/{examMasterId}/scoring")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<com.dlab.domain.grade.service.ScoringQueryService.Area>> studentScoring(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @PathVariable Long examMasterId) {
        return ApiResponse.success(scoringQueryService.scoring(
                studentService.get(enrollmentId, me), examMasterId));
    }

    /** 성적 변화 — 회차별 과목 등급·백분위, 오래된 순. 앱 {@code GET /app/grades/trend} 와 같은 모양. */
    @GetMapping("/students/{enrollmentId}/grades/trend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<com.dlab.domain.grade.service.AcademyExamQueryService.TrendPoint>> studentAcademyTrend(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {
        return ApiResponse.success(academyExamQueryService.trend(studentService.get(enrollmentId, me)));
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
