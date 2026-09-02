package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.grade.repository.ExamSubjectRepository;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 성적 입력 양식 조회.
 *
 * <p>앱은 <b>이 결과대로 화면을 그린다</b> — 과목명·칸 구성을 앱이 자체 판정하지 않는다.
 * 그래야 2028 수능 개편으로 과목이 바뀔 때 앱 배포 없이 데이터만 고치면 된다
 * (공휴일을 서버가 내려주는 것과 같은 이유 — A-9).
 */
@Service
@RequiredArgsConstructor
public class ExamFormService {

    private final ExamMasterRepository examMasterRepository;
    private final ExamSubjectRepository examSubjectRepository;

    /**
     * 이 학생이 낼 성적 양식.
     *
     * <p>양식이 없으면 <b>오류로 알린다.</b> 빈 목록을 내리면 앱에는 "입력할 게 없음"으로
     * 보여서, 마스터 데이터를 안 넣은 것과 정상적으로 없는 것이 구분되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Form> formOf(StudentEnrollment enrollment) {
        List<Form> forms = form(enrollment.getYear(), enrollment.getGrade(),
                enrollment.getAcademy().getId());
        if (forms.isEmpty()) {
            throw new BusinessException(ErrorCode.EXAM_FORM_NOT_FOUND);
        }
        return forms;
    }

    @Transactional(readOnly = true)
    public List<Form> form(short year, GradeType gradeType, Long academyId) {
        List<ExamMaster> exams = examMasterRepository.findForm(year, gradeType, academyId);
        if (exams.isEmpty()) {
            return List.of();
        }
        // 회차마다 과목을 따로 조회하면 N+1이 된다
        Map<Long, List<ExamSubject>> byExam = examSubjectRepository
                .findByExamMasterIds(exams.stream().map(ExamMaster::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(s -> s.getExamMaster().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        return exams.stream()
                .map(e -> new Form(e, byExam.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    /**
     * 학생이 낸 과목이 정말 그 학생 양식에 있는 과목인지 확인한다.
     *
     * <p><b>없으면 거절한다.</b> 과목 ID를 요청 본문으로 받는 구조라, 검사하지 않으면
     * 고2 학생이 N수 양식의 탐구 과목 ID를 실어 보내는 것만으로 <b>학년 통계에 남의 학년
     * 과목이 섞인다.</b> 화면이 올바로 그려도 API는 따로 막아야 한다.
     */
    public ExamSubject requireSubject(List<Form> forms, Long subjectId) {
        return forms.stream()
                .flatMap(f -> f.subjects().stream())
                .filter(s -> s.getId().equals(subjectId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.EXAM_SUBJECT_NOT_IN_FORM));
    }

    /** 시험 회차 하나 + 그 회차의 과목들. */
    public record Form(ExamMaster exam, List<ExamSubject> subjects) {
    }
}
