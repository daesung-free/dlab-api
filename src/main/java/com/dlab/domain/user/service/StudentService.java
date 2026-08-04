package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;

/**
 * 학생 검색 · 신규 접수.
 *
 * <p>학생은 <b>사람 + 등록 건 2단</b>이다. 신규 접수는 두 행을 같이 만들고,
 * 재등록(삼수 등)은 같은 사람에 등록 건만 추가한다 — 그래야 상담 이력이 이어진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentService {

    /** 학번 채번 충돌 재시도 횟수. 동시 접수는 드물어서 이 정도면 충분하다. */
    private static final int STUDENT_NO_RETRY = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final StudentRepository studentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudentSearchRepository studentSearchRepository;
    private final AcademyRepository academyRepository;

    @Transactional(readOnly = true)
    public Page<StudentEnrollment> search(SearchScope scope, String keyword, GradeType grade,
                                          TrackType track, EnrollmentStatus status,
                                          Long classId, Pageable pageable) {
        return studentSearchRepository.search(scope, keyword, grade, track, status, classId, pageable);
    }

    @Transactional(readOnly = true)
    public StudentEnrollment get(Long enrollmentId, AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /**
     * 신규 접수. 사람과 등록 건을 함께 만든다.
     *
     * <p>학번은 서버가 채번한다 — 클라이언트가 지정하면 중복·건너뜀이 생긴다.
     */
    @Transactional
    public StudentEnrollment admit(Long academyId, short year, String name, String phone,
                                   GradeType grade, TrackType track, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Student student = studentRepository.save(new Student(generateUniqueCode(), name, phone));
        return enroll(academy, year, student, grade, track);
    }

    /**
     * 엑셀 일괄 업로드용 신규 등록.
     *
     * <p>{@link #admit}와 달리 권한 검사·지점 조회를 하지 않는다 — 호출자가 파일 단위로
     * 이미 한 번 했고, 행마다 다시 하면 수백 번 반복된다. 대신 <b>이 메서드를 컨트롤러에서
     * 직접 부르지 말 것</b>: 권한 검사가 없다.
     */
    @Transactional
    public StudentEnrollment admitParsed(Academy academy, short year, String name, String phone,
                                         LocalDate birthDate, String gender, String schoolName,
                                         GradeType grade, TrackType track) {
        Student student = studentRepository.save(new Student(generateUniqueCode(), name, phone));
        student.updateProfile(null, null, birthDate, gender, schoolName);
        return enroll(academy, year, student, grade, track);
    }

    /** 학생 정보 수정. {@code null} 인자는 변경하지 않는다. */
    @Transactional
    public StudentEnrollment update(Long enrollmentId, String name, String phone, LocalDate birthDate,
                                    String gender, String schoolName, GradeType grade,
                                    TrackType track, EnrollmentStatus status, AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        enrollment.getStudent().updateProfile(name, phone, birthDate, gender, schoolName);
        enrollment.updateEnrollment(grade, track, status);
        return enrollment;
    }

    /**
     * 재등록. <b>같은 사람에 등록 건만 추가</b>하고 이전 건은 이력으로 내린다.
     *
     * <p>새 사람으로 만들면 상담 이력·신상기록부가 끊기고 동일인 추적이 불가능해진다
     * (독학재수라 삼수 재등록이 실제로 흔하다).
     */
    @Transactional
    public StudentEnrollment reEnroll(Long studentId, Long academyId, short year,
                                      GradeType grade, TrackType track, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        enrollmentRepository.findCurrentByStudentId(studentId).ifPresent(previous -> {
            previous.expire();
            // 재배정과 같은 이유 — is_current 부분 유니크가 있다면 순서를 강제해야 한다
            enrollmentRepository.flush();
        });

        return enroll(academy, year, student, grade, track);
    }

    /**
     * 등록 건 생성 + 학번 채번.
     *
     * <p><b>채번은 "다음 번호 계산 → INSERT"라 동시 접수 시 같은 번호가 나올 수 있다.</b>
     * 애플리케이션 락으로 막으면 다중 인스턴스에서 무의미하므로,
     * {@code UNIQUE(academy_id, year, student_no)} 제약을 심판으로 두고 충돌하면 재시도한다.
     */
    private StudentEnrollment enroll(Academy academy, short year, Student student,
                                     GradeType grade, TrackType track) {
        for (int attempt = 0; attempt < STUDENT_NO_RETRY; attempt++) {
            String studentNo = nextStudentNo(academy.getId(), year);
            try {
                StudentEnrollment enrollment = enrollmentRepository.save(
                        new StudentEnrollment(student, academy, year, studentNo, null, grade));
                enrollment.changeTrack(track);
                enrollmentRepository.flush();
                return enrollment;
            } catch (DataIntegrityViolationException e) {
                // 다른 접수가 같은 번호를 먼저 가져갔다. 다시 계산한다.
                log.info("학번 채번 충돌, 재시도 {}/{}: academy={}, year={}, no={}",
                        attempt + 1, STUDENT_NO_RETRY, academy.getId(), year, studentNo);
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "학번 채번에 실패했습니다. 다시 시도해주세요.");
    }

    /** {@code year + %04d}. 요구사항 F-4.1-3 — 매년 초기화되므로 연도별로 1부터 센다. */
    private String nextStudentNo(Long academyId, short year) {
        int next = enrollmentRepository.findMaxSequence(academyId, year) + 1;
        return "%d-%04d".formatted(year, next);
    }

    /**
     * 학생 고유ID. 학부모 자녀연결의 유일한 키이고 마이페이지에 상시 노출된다.
     *
     * <p>혼동하기 쉬운 문자(0/O, 1/I)를 뺀다 — 학부모가 눈으로 보고 입력하는 값이라
     * 잘못 읽으면 연결이 안 된다.
     */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < STUDENT_NO_RETRY; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (studentRepository.findByUniqueCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "고유ID 생성에 실패했습니다.");
    }
}
