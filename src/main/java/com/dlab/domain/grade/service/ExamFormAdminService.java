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

import java.util.List;

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

        if (command.subjects().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "과목이 없는 시험은 만들 수 없습니다.");
        }
        boolean academyExam = purpose == com.dlab.domain.grade.entity.ExamPurpose.ACADEMY;
        command.subjects().forEach(s -> exam.addSubject(s.subjectCode(), s.subjectName(),
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
