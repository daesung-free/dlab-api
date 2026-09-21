package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의고사 성적 엑셀 업로드 (F-4.11 · §2 0907 확정).
 *
 * <h2>미리보기 → 반영 두 단계다</h2>
 * 605명짜리 파일을 바로 저장하면, 매칭이 어긋났을 때 <b>무엇이 잘못 들어갔는지 모른 채</b>
 * 전교생 성적이 바뀐다. 먼저 "누가 매칭됐고 누가 안 됐는지" 를 돌려주고, 확인한 뒤 반영한다.
 *
 * <h2>★ 학생 매칭은 이름으로 한다 — 확정된 키가 없다</h2>
 * 파일의 식별자는 <b>학교코드·반·번호</b>인데 둘 다 지금 쓸 수 없다.
 * <ul>
 *   <li>학교코드({@code 99700} = 분당)를 지점과 잇는 매핑이 <b>우리 DB 에 없다.</b>
 *       9개 지점만 알려져 있고 대전·대구 코드는 아직 모른다(§4)</li>
 *   <li>번호가 <b>"반 번호 + 3자리 순번"</b> 구조라 반이 바뀌면 앞자리가 바뀐다.
 *       고정 키로 쓰면 반 이동 학생의 이전 회차가 끊긴다(§2)</li>
 * </ul>
 * 그래서 <b>업로드할 지점을 관리자가 고르고</b>, 그 지점 재원생 중 이름으로 찾는다.
 * <b>동명이인은 매칭하지 않는다</b> — 둘 중 하나를 고르면 남의 성적이 들어간다.
 * 매칭이 안 된 행은 사유와 함께 돌려주고, 사람이 판단한다.
 *
 * <h2>과목은 이름으로 잇는다</h2>
 * 파일의 영역명(국어·수학·탐구영역-선택1과목)과 회차 양식의 과목명(국어·탐구1)이 다르다.
 * 그 대응을 여기서 갖는다 — 양식은 학년·연도마다 바뀌므로 코드에 박지 않고
 * <b>회차에 실제로 있는 과목만</b> 대상으로 삼는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockExamUploadService {


    /** 파일 영역명 → 회차 과목명 후보. 앞에서부터 찾아 첫 번째로 맞는 것을 쓴다. */
    private static final Map<String, List<String>> SUBJECT_ALIASES = Map.of(
            "국어", List.of("국어"),
            "수학", List.of("수학"),
            "영어", List.of("영어"),
            "한국사", List.of("한국사"),
            "탐구영역-선택1과목", List.of("탐구1", "통합사회"),
            "탐구영역-선택2과목", List.of("탐구2", "통합과학"),
            "제2외국어/한문", List.of("제2외국어", "한문"));

    private final MockExamExcelParser parser;
    private final ExamMasterRepository examMasterRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudentGradeService gradeService;
    private final com.dlab.domain.grade.repository.MockExamStudentKeyRepository keyRepository;
    private final MockExamStudentMatcher matcher;
    private final com.dlab.domain.grade.repository.ExamUniversityChoiceRepository choiceRepository;

    /** 저장하지 않고 결과만 본다. */
    @Transactional(readOnly = true)
    public Preview preview(AuthPrincipal me, Long academyId, Long examMasterId,
                           InputStream file) {
        return process(me, academyId, examMasterId, file, false);
    }

    /**
     * 반영한다.
     *
     * <p><b>매칭된 학생만</b> 저장한다 — 못 찾은 행 때문에 전체를 되돌리면, 한 명 때문에
     * 604명을 다시 올려야 한다. 못 찾은 행은 결과에 그대로 남아 사람이 처리한다.
     */
    @Transactional
    public Preview apply(AuthPrincipal me, Long academyId, Long examMasterId, InputStream file) {
        return process(me, academyId, examMasterId, file, true);
    }

    private Preview process(AuthPrincipal me, Long requestedAcademyId, Long examMasterId,
                            InputStream file, boolean save) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        ExamMaster exam = examMasterRepository.findById(examMasterId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXAM_FORM_NOT_FOUND,
                        "회차를 찾을 수 없습니다."));
        // ★★ 디랩에서 본 시험 양식에만 올린다. 입학 전 성적 양식에 올리면 업로드가 그
        //    회차 점수를 교체하므로, 학생이 가입 때 넣고 선생님이 대조까지 끝낸 입학
        //    성적이 흔적 없이 지워진다 — 예전에는 둘이 같은 행이라 실제로 가능했다
        if (!exam.isAcademyExam()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "입학 전 성적 양식에는 업로드할 수 없습니다. 디랩에서 본 시험 회차를 골라 주세요.");
        }
        // ★ 회차는 전 지점 공통일 수 있다(academy 가 null). 그때는 지점을 따지지 않는다 —
        //   공통 회차에 지점 검사를 걸면 어느 지점도 쓸 수 없다.
        if (exam.getAcademy() != null && !exam.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        MockExamStudentMatcher.Table table = matcher.prepare(academyId, exam.getYear());
        MockExamExcelParser.Result parsed = parser.parse(file);

        List<Matched> matched = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        int skippedExternal = 0;

        for (MockExamExcelParser.StudentRow row : parsed.students()) {
            MockExamStudentMatcher.Match match =
                    table.match(row.schoolCode(), row.classNo(), row.studentNo(), row.name());
            if (match.external()) {
                skippedExternal++;
                continue;
            }
            if (match.enrollment() == null) {
                unmatched.add(new Unmatched(row.rowNumber(), row.schoolCode(), row.name(),
                        row.classNo(), row.studentNo(), match.reason()));
                continue;
            }
            record(exam, row, match.enrollment(), save, matched, unmatched);
        }

        if (save) {
            log.info("모의고사 성적 업로드: examMasterId={}, 반영={}명, 미매칭={}건",
                    examMasterId, matched.size(), unmatched.size());
        }
        return new Preview(exam.getExamName(), parsed.students().size(),
                skippedExternal, matched, unmatched);
    }

    /** 찾은 학생에 점수를 넣는다. 회차 양식과 겹치는 과목이 하나도 없으면 미매칭이다. */
    private void record(ExamMaster exam, MockExamExcelParser.StudentRow row,
                        StudentEnrollment enrollment, boolean save,
                        List<Matched> matched, List<Unmatched> unmatched) {
        List<StudentGradeService.ScoreInput> inputs = scoreInputs(exam, row);
        if (inputs.isEmpty()) {
            unmatched.add(new Unmatched(row.rowNumber(), row.schoolCode(), row.name(), row.classNo(),
                    row.studentNo(), "이 회차 양식과 맞는 과목 점수가 없습니다."));
            return;
        }
        if (save) {
            // 업로드 전용 경로 — 입학 성적의 제출·"모른다" 상태를 건드리지 않는다
            gradeService.saveAcademyScores(enrollment, exam, inputs);
            saveChoices(enrollment, exam, row.choices());
        }
        matched.add(new Matched(row.rowNumber(), enrollment.getId(),
                enrollment.getStudentNo(), row.name(), inputs.size()));
    }

    /**
     * 지망대학 진단을 교체한다 — 같은 회차를 다시 올리면 점수와 함께 바뀐다.
     *
     * <p>★ <b>숫자로 못 읽는 칸은 비워 둔다.</b> 오류로 막으면 기준점수 한 칸 때문에 그 학생
     * 성적 전체가 안 들어간다. 대학명만 있으면 담는다.
     */
    private void saveChoices(StudentEnrollment enrollment, ExamMaster exam,
                             List<MockExamExcelParser.UniversityChoice> choices) {
        choiceRepository.softDeleteOf(enrollment.getId(), exam.getId());
        for (MockExamExcelParser.UniversityChoice c : choices) {
            choiceRepository.save(new com.dlab.domain.grade.entity.ExamUniversityChoice(
                    enrollment, exam, (short) c.rank(), c.universityName(),
                    blankToNull(c.departmentName()),
                    integer(c.quota()), integer(c.applicantCount()), integer(c.applicantRank()),
                    blankToNull(c.appliedAreas()),
                    decimal(c.expectedScore()), decimal(c.cutoffScore()),
                    blankToNull(c.diagnosis())));
        }
    }

    private static Integer integer(String v) {
        try {
            return isBlank(v) ? null : new java.math.BigDecimal(v.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static java.math.BigDecimal decimal(String v) {
        try {
            return isBlank(v) ? null : new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return isBlank(v) ? null : v.trim();
    }

    /**
     * 파일 식별자 한 덩어리.
     *
     * <p>세 값을 다 쓴다 — 번호만으로는 반이 다른 학생과 겹치고, 학교코드를 빼면 지점이
     * 섞인다. 값이 비면 키를 만들지 않는다(빈 문자열끼리 맞아 엉뚱한 학생에게 붙는다).
     */
    private static String fileKey(String schoolCode, String classNo, String studentNo) {
        if (isBlank(schoolCode) || isBlank(classNo) || isBlank(studentNo)) {
            return null;
        }
        return schoolCode.trim() + "|" + classNo.trim() + "|" + studentNo.trim();
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    /**
     * 사람이 정한 연결을 남긴다.
     *
     * <p>동명이인이라 매칭이 멈춘 행을 화면에서 지정하면 여기로 온다. <b>다음 회차부터는
     * 이름을 보지 않고 이 연결을 쓴다</b> — 그러지 않으면 같은 학생이 회차마다 빠진다.
     *
     * <p>같은 칸에 행을 하나 더 만들지 않고 대상을 바꾼다. 둘이면 어느 학생 성적인지가
     * 정해지지 않는다.
     */
    @Transactional
    public com.dlab.domain.grade.entity.MockExamStudentKey link(
            AuthPrincipal me, Long requestedAcademyId, short year,
            String schoolCode, String classNo, String studentNo, Long enrollmentId) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        if (fileKey(schoolCode, classNo, studentNo) == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "학교코드·반·번호가 모두 있어야 연결할 수 있습니다.");
        }

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        // 지점을 확인하지 않으면 enrollmentId 만 바꿔 남의 지점 학생에게 성적을 붙일 수 있다
        if (!enrollment.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        var academy = enrollment.getAcademy();
        return keyRepository.findByKey(academyId, year, schoolCode, classNo, studentNo)
                .map(existing -> {
                    existing.relink(enrollment);
                    return existing;
                })
                .orElseGet(() -> keyRepository.save(
                        new com.dlab.domain.grade.entity.MockExamStudentKey(
                                academy, year, schoolCode, classNo, studentNo, enrollment)));
    }

    /** 연결 해제. 잘못 이었으면 지우고 다시 이름 매칭으로 돌아간다. */
    @Transactional
    public void unlink(AuthPrincipal me, Long keyId) {
        var key = keyRepository.findById(keyId)
                .filter(k -> !k.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        me.requireAcademyScope(key.getAcademy().getId());
        key.markDeleted();
    }

    /** 이 지점·연도에 사람이 정해둔 연결. 화면에서 확인·해제한다. */
    @Transactional(readOnly = true)
    public List<com.dlab.domain.grade.entity.MockExamStudentKey> links(
            AuthPrincipal me, Long requestedAcademyId, short year) {
        return keyRepository.findAllByScope(me.requireAcademyScope(requestedAcademyId), year);
    }

    /**
     * 회차 양식에 있는 과목만 값으로 만든다.
     *
     * <p>파일에는 회차와 무관한 영역까지 들어 있다(한국사·제2외국어). <b>양식에 없는 과목을
     * 저장하려 하면 거절된다</b> — 그래서 여기서 먼저 거른다.
     */
    private List<StudentGradeService.ScoreInput> scoreInputs(ExamMaster exam,
                                                             MockExamExcelParser.StudentRow row) {
        List<StudentGradeService.ScoreInput> inputs = new ArrayList<>();
        row.subjects().forEach((block, score) -> subjectOf(exam, block).ifPresent(subject -> {
            Short standard = number(score.standardScore());
            Short percentile = number(score.percentile());
            Short gradeLevel = number(score.gradeLevel());
            // ★ 파서는 원점수를 원래 읽고 있었는데 여기서 버리고 있었다 — 영어·한국사 칸이 빈 이유
            Short raw = number(score.rawScore());
            if (standard != null || percentile != null || gradeLevel != null || raw != null) {
                inputs.add(new StudentGradeService.ScoreInput(
                        subject.getId(), standard, percentile, gradeLevel, raw));
            }
        }));
        return inputs;
    }

    private Optional<ExamSubject> subjectOf(ExamMaster exam, String block) {
        for (String candidate : SUBJECT_ALIASES.getOrDefault(block, List.of(block))) {
            Optional<ExamSubject> found = exam.getSubjects().stream()
                    .filter(s -> !s.isDeleted())
                    .filter(s -> normalize(s.getSubjectName()).equals(normalize(candidate)))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 숫자로 바꾼다. <b>못 바꾸면 비운다</b> — 파일에 {@code "-"}·{@code "결시"} 같은 표기가
     * 섞여 있고, 그것 때문에 605명짜리 업로드가 통째로 실패하면 안 된다.
     */
    private Short number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (short) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @param totalRows 파일에서 읽은 학생 행 수
     * @param skippedExternal 외부생(99709)이라 건너뛴 행. <b>조용히 버리면 "왜 인원이
     *                        다르냐" 가 된다</b>
     * @param matched   찾은 학생. {@code apply} 면 이미 저장됐다
     * @param unmatched 못 찾은 행. <b>사유가 함께 온다</b> — 화면이 그대로 보여주면 된다
     */
    public record Preview(String examName, int totalRows, int skippedExternal,
                          List<Matched> matched, List<Unmatched> unmatched) {
    }

    /** @param rowNumber 엑셀 기준 행 번호 */
    public record Matched(int rowNumber, Long enrollmentId, String studentNo,
                          String name, int subjectCount) {
    }

    /**
     * @param schoolCode 연결 등록에 필요하다. 이게 없으면 화면이 미매칭 행을 학생에
     *                   이을 수 없다 — 키가 학교코드·반·번호 세 값이다
     */
    public record Unmatched(int rowNumber, String schoolCode, String name, String classNo,
                            String studentNo, String reason) {
    }
}
