package com.dlab.domain.user.service;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelExporter;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentSearchCondition;
import com.dlab.domain.user.repository.StudentSearchRepository;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 조회 (F-4.1-1).
 *
 * <p>생성·수정은 이 클래스에 넣지 않는다 — 담당이 갈려 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentQueryService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Export 양식. Import도 같은 매핑을 쓰므로 "내려받아 수정 후 재업로드"가 성립한다. */
    private static final ColumnMapping EXPORT_MAPPING = ColumnMapping.builder()
            .required("studentNo", "학번", "학생번호")
            .required("name", "이름", "성명")
            .optional("grade", "학년")
            .optional("track", "계열")
            .optional("status", "재원상태")
            .optional("phone", "연락처", "전화번호")
            .optional("schoolName", "출신학교")
            .optional("admissionDate", "등원일")
            .optional("rfidNo", "카드번호");

    private final StudentSearchRepository searchRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ExcelExporter excelExporter;

    public Page<StudentEnrollment> search(AuthPrincipal principal,
                                          StudentSearchCondition condition,
                                          Pageable pageable) {
        return searchRepository.search(scopeOf(principal, condition), condition, pageable);
    }

    /**
     * 등록 건 단건 조회.
     *
     * <p><b>지점 확인을 반드시 거친다.</b> 목록은 스코프로 걸러지지만 단건은 id를 직접 받으므로,
     * 확인하지 않으면 다른 지점 학생 id를 넣는 것만으로 열람된다.
     */
    public StudentEnrollment getEnrollment(AuthPrincipal principal, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /** 카드번호로 현재 등록 건. 키오스크 경로가 전부 이걸 쓴다. */
    public StudentEnrollment getByRfid(String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }

    /**
     * 목록 엑셀. <b>페이징 없이 조건에 맞는 전건</b>을 내린다 —
     * 화면 한 페이지만 받으면 사용자는 목록을 다 못 받았다는 걸 모른다.
     */
    public byte[] export(AuthPrincipal principal, StudentSearchCondition condition) {
        List<StudentEnrollment> rows = searchRepository.searchAll(scopeOf(principal, condition), condition);
        return excelExporter.export("재원생", EXPORT_MAPPING, rows, this::toRow);
    }

    private List<String> toRow(StudentEnrollment e) {
        return Arrays.asList(
                e.getStudentNo(),
                e.getStudent().getName(),
                e.getGrade() == null ? null : e.getGrade().name(),
                e.getTrack() == null ? null : e.getTrack().name(),
                e.getEnrollmentStatus() == null ? null : e.getEnrollmentStatus().name(),
                e.getStudent().getPhone(),
                e.getStudent().getSchoolName(),
                e.getAdmissionDate() == null ? null : e.getAdmissionDate().format(DATE),
                e.getRfidNo());
    }

    /** 지점은 인증 주체에서만 나온다. 연도는 요청에서 받되 없으면 스코프에도 없다. */
    private SearchScope scopeOf(AuthPrincipal principal, StudentSearchCondition condition) {
        Integer year = condition == null ? null : condition.year();
        return SearchScope.of(principal, year);
    }
}
