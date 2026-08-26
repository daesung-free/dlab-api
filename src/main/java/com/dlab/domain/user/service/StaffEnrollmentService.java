package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 카드 등록 — 키오스크 출퇴근용.
 *
 * <h2>학생 등록과 별도인 이유</h2>
 * 공통인 것이 이름·연락처·학번·카드뿐이다. 학년·계열은 직원에게 없고(필수값이라 아무거나
 * 넣으면 학년별 통계가 오염된다), 학생 등록에 붙은 승인·온보딩·학부모 연결·급식 청구가
 * 전부 무의미하다. <b>권한도 다르다</b> — 직원 등록은 {@code SUPER_ADMIN} 전용이고
 * 학생 등록은 지점 관리자도 한다.
 *
 * <h2>★ 학번 대역을 나눈다</h2>
 * 키오스크가 4자리 학번 키패드를 쓰는데 학생과 번호가 섞이면 <b>번호만 보고 직원인지
 * 알 수 없다</b>. {@value #STAFF_NO_FLOOR}번대부터 뽑아 운영에서 눈으로 구분되게 한다.
 *
 * <h2>앱 계정은 만들지 않는다</h2>
 * 계정은 학생이 앱에서 직접 가입할 때만 생긴다. 직원 등록은 카드·학번만 발급한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffEnrollmentService {

    /** 직원 학번 대역 시작. 이 값 다음부터 뽑는다. */
    private static final int STAFF_NO_FLOOR = 9000;

    private final StudentRepository studentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    public List<StudentEnrollment> list(AuthPrincipal me, Long academyId) {
        return enrollmentRepository.findCurrentStaff(requireScope(me, academyId));
    }

    /**
     * 직원 등록.
     *
     * @param rfidNo 카드번호. 없으면 나중에 발급한다 — 학번 키패드만으로도 찍을 수 있다
     */
    @Transactional
    public StudentEnrollment register(AuthPrincipal me, Long academyId, String name,
                                      String phone, String rfidNo) {
        Long scope = requireScope(me, academyId);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이름을 입력해 주세요.");
        }

        Academy academy = academyRepository.findById(scope)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        short year = (short) LocalDate.now(clock).getYear();
        Student person = studentRepository.save(
                new Student(uniqueCode(academy, year), name.trim(), phone));

        StudentEnrollment enrollment = enrollmentRepository.save(new StudentEnrollment(
                person, academy, year, nextStaffNo(scope, year), rfidNo, GradeType.STAFF));
        enrollmentRepository.flush();
        return enrollment;
    }

    /** 카드 교체·분실 재발급. */
    @Transactional
    public StudentEnrollment changeCard(AuthPrincipal me, Long enrollmentId, String rfidNo) {
        StudentEnrollment enrollment = requireStaff(me, enrollmentId);
        enrollment.assignCard(rfidNo);
        return enrollment;
    }

    /**
     * 퇴사 처리.
     *
     * <p>행을 지우지 않고 {@code current}를 내린다 — 지우면 그 사람의 근태 이력이
     * 어느 직원 것인지 추적할 수 없다. 다음 동기화에서 키오스크가 비활성 처리한다.
     */
    @Transactional
    public void retire(AuthPrincipal me, Long enrollmentId) {
        requireStaff(me, enrollmentId).expire();
    }

    // ─────────────────────────────────────────────────────────

    private StudentEnrollment requireStaff(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!enrollment.getGrade().isStaff()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "직원 등록 건이 아닙니다.");
        }
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    private Long requireScope(AuthPrincipal me, Long requested) {
        if (requested == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해 주세요.");
        }
        if (!me.canAccessAcademy(requested)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return requested;
    }

    private String nextStaffNo(Long academyId, short year) {
        int next = enrollmentRepository.findMaxStaffSequence(academyId, year, STAFF_NO_FLOOR) + 1;
        return "%d-%04d".formatted(year, next);
    }

    /**
     * 사람 식별자.
     *
     * <p>학생 고유ID는 학부모 자녀연결에 쓰이는 값이라 형식이 다르다. 직원은 연결할
     * 학부모가 없으므로 충돌하지 않게 접두사만 나눈다.
     */
    private String uniqueCode(Academy academy, short year) {
        return "ST-%s-%d-%d".formatted(academy.getAcadCd(), year, System.nanoTime() % 100000);
    }
}
