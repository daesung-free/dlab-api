package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 성적 입력 양식 관리.
 *
 * <h2>★ 해마다 넣어야 한다</h2>
 * 마이그레이션에는 2026년분만 있다. 연도가 바뀌면 여기서 새로 넣지 않는 한
 * 그 해 가입자가 전부 {@code EXAM_FORM_NOT_FOUND}를 받는다 —
 * <b>기수 시작 전 체크리스트에 넣어야 하는 항목이다.</b>
 *
 * <h2>전 지점 공통은 본사만 건드린다</h2>
 * 공휴일과 같은 규칙이다. 지점 관리자가 공통 행을 고치면 나머지 8개 지점의 가입 화면이
 * 같이 바뀐다. 지점은 <b>자기 지점 행만</b> 만들 수 있고, 그 행이 있으면 그 지점에서는
 * 공통본 대신 그것이 쓰인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamFormAdminService {

    private final ExamMasterRepository examMasterRepository;
    private final com.dlab.domain.grade.repository.ExamSubjectPresetRepository presetRepository;
    private final jakarta.persistence.EntityManager em;
    private final AcademyRepository academyRepository;

    @Transactional(readOnly = true)
    public List<ExamMaster> list(AuthPrincipal me, short year, Long academyId) {
        requireScope(me, academyId);
        return examMasterRepository.findAllByScope(year, academyId);
    }

    /**
     * 시험 회차 + 과목을 <b>한 번에</b> 등록한다.
     *
     * <p>회차만 먼저 만들 수 있게 두면 과목이 없는 회차가 양식에 뜨고, 학생 화면에는
     * 제목만 있고 입력 칸이 없는 빈 표가 그려진다.
     */
    @Transactional
    public ExamMaster create(AuthPrincipal me, Command command) {
        requireScope(me, command.academyId());

        com.dlab.domain.grade.entity.ExamPurpose purpose = command.purposeOrDefault();
        requirePurposeRules(purpose, command.examCode(), command.examDate());

        examMasterRepository.findOne(command.year(), command.gradeType(), command.examCode(),
                purpose, command.examDate(), command.academyId()).ifPresent(e -> {
            throw new BusinessException(ErrorCode.EXAM_MASTER_DUPLICATED);
        });

        Academy academy = command.academyId() == null ? null
                : academyRepository.findById(command.academyId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        ExamMaster exam = purpose == com.dlab.domain.grade.entity.ExamPurpose.ACADEMY
                ? ExamMaster.academyExam(academy, command.year(), command.gradeType(),
                        command.examCode(), command.examName(), command.examDate(),
                        command.sortOrder())
                : new ExamMaster(academy, command.year(), command.gradeType(),
                        command.examCode(), command.examName(), (short) command.sortOrder());

        boolean academyExam = purpose == com.dlab.domain.grade.entity.ExamPurpose.ACADEMY;
        List<SubjectInput> subjects = command.subjects() == null ? List.of() : command.subjects();
        if (subjects.isEmpty() && academyExam) {
            // 디랩 시험은 과목을 비우면 학년별 기본 구성으로 채운다 — 매달 6과목을 손으로 넣지 않게
            subjects = presetRepository.findEffective(command.year(), command.gradeType(),
                            command.academyId()).stream()
                    .map(p -> new SubjectInput(p.getSubjectCode(), p.getSubjectName(),
                            p.getSortOrder(), p.isHasStandardScore(), p.isHasPercentile(),
                            p.isHasGradeLevel(), p.isHasRawScore()))
                    .toList();
        }
        if (subjects.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, academyExam
                    ? "과목이 없고 이 학년의 기본 과목 구성도 등록되지 않았습니다."
                    : "과목이 없는 시험은 만들 수 없습니다.");
        }
        subjects.forEach(s -> exam.addSubject(s.subjectCode(), s.subjectName(),
                        s.sortOrder(), s.hasStandardScore(), s.hasPercentile(), s.hasGradeLevel())
                // ★ 비우면 디랩 시험은 켜고 입학 전 성적은 끈다. 연구소 파일에는 원점수가
                //   있고, 신상기록부에는 없다 — 기본값이 반대면 가입 화면에 없던 칸이 생긴다
                .acceptRawScore(s.hasRawScore() == null ? academyExam : s.hasRawScore()));

        return examMasterRepository.save(exam);
    }

    /**
     * 회차 삭제(soft).
     *
     * <p><b>물리 삭제하지 않는다.</b> 이미 낸 성적이 이 회차를 참조하고 있어,
     * 지우면 그 학생 성적이 어느 시험이었는지 알 수 없게 된다.
     * 삭제해도 <b>기존 점수는 남는다</b> — 새 학생 양식에서만 빠진다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long examMasterId) {
        ExamMaster exam = examMasterRepository.findById(examMasterId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXAM_MASTER_NOT_FOUND));

        requireScope(me, exam.isCommon() ? null : exam.getAcademy().getId());
        exam.markDeleted();
        exam.getSubjects().forEach(s -> s.markDeleted());
        log.info("성적 양식 회차 삭제: examMasterId={}", examMasterId);
    }

    /**
     * @param academyId {@code null}이면 전 지점 공통 — <b>본사만</b> 다룰 수 있다
     */
    private void requireScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            if (me.academyScopeFilter() != null) {
                throw new BusinessException(ErrorCode.EXAM_FORM_SCOPE_FORBIDDEN);
            }
            return;
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    // ── 학년별 과목 기본 구성 ──────────────────────────────────

    /**
     * 기본 구성 목록.
     *
     * @param academyId {@code null}이면 공통본. 지점을 넣으면 <b>그 지점에 실제로 쓰일</b>
     *                  구성이다(지점 행이 없으면 공통본)
     */
    @Transactional(readOnly = true)
    public List<com.dlab.domain.grade.entity.ExamSubjectPreset> presets(
            AuthPrincipal me, short year, GradeType gradeType, Long academyId) {
        requireScope(me, academyId);
        if (academyId == null) {
            return presetRepository.findOwn(year, null).stream()
                    .filter(p -> gradeType == null || p.getGradeType() == gradeType)
                    .toList();
        }
        List<GradeType> grades = gradeType != null ? List.of(gradeType)
                : List.of(GradeType.HIGH2, GradeType.HIGH3, GradeType.N_SU);
        return grades.stream()
                .flatMap(g -> presetRepository.findEffective(year, g, academyId).stream())
                .toList();
    }

    /**
     * 한 학년의 기본 구성을 통째로 바꾼다.
     *
     * <p><b>이미 만든 회차는 바뀌지 않는다</b> — 과목은 회차마다 복사되어 있어, 여기를 고쳐도
     * 지난 성적의 과목 구성이 소급해서 바뀌지 않는다. 다음에 만드는 회차부터 적용된다.
     *
     * <p>빈 목록을 보내면 그 범위의 행을 전부 지운다 — 지점 행을 지우면 공통본으로 돌아간다.
     */
    @Transactional
    public List<com.dlab.domain.grade.entity.ExamSubjectPreset> replacePresets(
            AuthPrincipal me, Long academyId, short year, GradeType gradeType,
            List<SubjectInput> subjects) {
        requireScope(me, academyId);
        long distinctCodes = subjects.stream().map(SubjectInput::subjectCode).distinct().count();
        if (distinctCodes != subjects.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 과목 코드가 두 번 있습니다.");
        }
        Academy academy = academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        presetRepository.findOwn(year, academyId).stream()
                .filter(p -> p.getGradeType() == gradeType)
                .forEach(com.dlab.common.entity.BaseEntity::markDeleted);
        // ★ 지운 것을 먼저 내보낸다. Hibernate 가 INSERT 를 UPDATE 보다 먼저 보내서,
        //   같은 과목 코드를 다시 넣으면 부분 유니크 인덱스에 걸린다
        em.flush();

        return presetRepository.saveAll(subjects.stream()
                .map(s -> new com.dlab.domain.grade.entity.ExamSubjectPreset(academy, year,
                        gradeType, s.subjectCode(), s.subjectName(), s.sortOrder(),
                        s.hasStandardScore(), s.hasPercentile(), s.hasGradeLevel(),
                        s.hasRawScore() == null || s.hasRawScore()))
                .toList());
    }

    // ── 연도 롤오버 ──────────────────────────────────────────

    /**
     * 전년도 양식을 새 해로 복사한다 — 입학 전 성적 양식 + 학년별 기본 구성.
     *
     * <p><b>디랩 시험 회차는 복사하지 않는다.</b> 시행일이 붙은 한 번뿐인 시험이라 새 해에
     * 같은 날짜로 있을 리 없다 — 새 해에는 기본 구성으로 새로 만든다.
     *
     * <p><b>이미 있는 것은 건너뛴다.</b> 두 번 불러도 같은 결과다. 기본 구성은 학년 단위로
     * 판단한다 — 새 해에 그 학년 행이 하나라도 있으면 일부만 섞이지 않게 통째로 건너뛴다.
     *
     * <p>시험 이름의 연도는 차이만큼 올린다("2026년 6월 학력평가" → "2027년 6월 학력평가",
     * "2026학년도 수능" → "2027학년도 수능"). <b>9평이 8월로 옮겨가는 것 같은 제도 변경은
     * 반영하지 않는다</b> — 복사한 뒤 관리자가 고친다.
     *
     * @param academyId {@code null}이면 공통본 — 본사만
     */
    @Transactional
    public RolloverResult rollover(AuthPrincipal me, Long academyId, short fromYear, short toYear) {
        requireScope(me, academyId);
        if (toYear <= fromYear) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "새 연도는 원본 연도보다 커야 합니다.");
        }
        int delta = toYear - fromYear;

        int formsCreated = 0;
        int formsSkipped = 0;
        for (ExamMaster source : examMasterRepository.findAllByScope(fromYear, academyId)) {
            if (source.isAcademyExam()) {
                continue;
            }
            boolean exists = examMasterRepository.findOne(toYear, source.getGradeType(),
                    source.getExamCode(), source.getPurpose(), null, academyId).isPresent();
            if (exists) {
                formsSkipped++;
                continue;
            }
            ExamMaster copy = new ExamMaster(source.getAcademy(), toYear, source.getGradeType(),
                    source.getExamCode(), shiftYears(source.getExamName(), delta),
                    source.getSortOrder());
            source.activeSubjects().forEach(sub -> copy.addSubject(sub.getSubjectCode(),
                            sub.getSubjectName(), sub.getSortOrder(), sub.isHasStandardScore(),
                            sub.isHasPercentile(), sub.isHasGradeLevel())
                    .acceptRawScore(sub.isHasRawScore()));
            examMasterRepository.save(copy);
            formsCreated++;
        }

        List<com.dlab.domain.grade.entity.ExamSubjectPreset> target =
                presetRepository.findOwn(toYear, academyId);
        Set<GradeType> filled = target.stream()
                .map(com.dlab.domain.grade.entity.ExamSubjectPreset::getGradeType)
                .collect(Collectors.toSet());
        List<com.dlab.domain.grade.entity.ExamSubjectPreset> copies = new ArrayList<>();
        Set<GradeType> skippedGrades = new java.util.TreeSet<>();
        for (var preset : presetRepository.findOwn(fromYear, academyId)) {
            if (filled.contains(preset.getGradeType())) {
                skippedGrades.add(preset.getGradeType());
                continue;
            }
            copies.add(preset.copyTo(toYear));
        }
        presetRepository.saveAll(copies);

        log.info("성적 양식 롤오버: academyId={}, {} → {}, 회차 {}건 생성 / {}건 건너뜀, 기본 과목 {}건",
                academyId, fromYear, toYear, formsCreated, formsSkipped, copies.size());
        return new RolloverResult(formsCreated, formsSkipped, copies.size(),
                List.copyOf(skippedGrades));
    }

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");

    /** 이름 속 연도를 차이만큼 올린다. 연도가 없으면 그대로다("6월 평가원 모의고사"). */
    public static String shiftYears(String name, int delta) {
        Matcher m = YEAR.matcher(name);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, String.valueOf(Integer.parseInt(m.group(1)) + delta));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * @param presetsSkippedGrades 새 해에 이미 행이 있어 건너뛴 학년
     */
    public record RolloverResult(int formsCreated, int formsSkipped, int presetsCreated,
                                 List<GradeType> presetsSkippedGrades) {
    }

    /**
     * 용도별 규칙.
     *
     * <ul>
     *   <li><b>디랩 시험은 시행일이 필수다</b> — 월례고사가 코드만으로는 구분되지 않는다</li>
     *   <li><b>월례고사(MONTHLY)는 디랩 시험만 된다</b> — 입학 전 성적에는 더프가 없다</li>
     * </ul>
     */
    private void requirePurposeRules(com.dlab.domain.grade.entity.ExamPurpose purpose,
                                     ExamCode examCode, java.time.LocalDate examDate) {
        boolean academy = purpose == com.dlab.domain.grade.entity.ExamPurpose.ACADEMY;
        if (academy && examDate == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "디랩에서 본 시험은 시행일이 필요합니다.");
        }
        if (!academy && examCode == ExamCode.MONTHLY) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "월례고사는 디랩에서 본 시험으로만 등록합니다.");
        }
    }

    /**
     * @param purpose  비우면 입학 전 성적 양식이다(기존 동작)
     * @param examDate 디랩 시험은 필수
     */
    public record Command(Long academyId, short year, GradeType gradeType, ExamCode examCode,
                          String examName, int sortOrder, List<SubjectInput> subjects,
                          com.dlab.domain.grade.entity.ExamPurpose purpose,
                          java.time.LocalDate examDate) {

        public com.dlab.domain.grade.entity.ExamPurpose purposeOrDefault() {
            return purpose == null ? com.dlab.domain.grade.entity.ExamPurpose.ADMISSION : purpose;
        }
    }

    /** @param hasRawScore 비우면 디랩 시험은 켜고 입학 전 성적은 끈다 */
    public record SubjectInput(String subjectCode, String subjectName, int sortOrder,
                               boolean hasStandardScore, boolean hasPercentile,
                               boolean hasGradeLevel, Boolean hasRawScore) {

        public SubjectInput(String subjectCode, String subjectName, int sortOrder,
                            boolean hasStandardScore, boolean hasPercentile,
                            boolean hasGradeLevel) {
            this(subjectCode, subjectName, sortOrder, hasStandardScore, hasPercentile,
                    hasGradeLevel, null);
        }
    }
}
