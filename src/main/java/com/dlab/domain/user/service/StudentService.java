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
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.function.Supplier;
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

    /** 저장 버튼 중복 제출로 보는 시간 창. 사람이 다시 누르는 간격은 이 안에 들어온다. */
    private static final int DOUBLE_SUBMIT_WINDOW_SECONDS = 10;

    /** 학번 채번 충돌 재시도 횟수. 동시 접수는 드물어서 이 정도면 충분하다. */
    private static final int STUDENT_NO_RETRY = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final StudentRepository studentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudentSearchRepository studentSearchRepository;
    private final AcademyRepository academyRepository;
    private final TransactionTemplate transactionTemplate;
    private final java.time.Clock clock;
    /** 삭제 전 이력 확인용 — 다닌 흔적이 있으면 지우지 않고 퇴원·제적으로 보낸다 */
    private final com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository taggingLogRepository;
    private final com.dlab.domain.penalty.repository.PenaltyPointRepository penaltyPointRepository;
    /** 접수 직렬화용 자문 잠금(pg_advisory_xact_lock) 호출에 쓴다 */
    private final jakarta.persistence.EntityManager em;

    @Transactional(readOnly = true)
    public Page<StudentEnrollment> search(SearchScope scope, StudentSearchCondition condition,
                                          Pageable pageable) {
        return studentSearchRepository.search(scope, condition, pageable);
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
     *
     * <p><b>★ 재시도는 트랜잭션 <i>바깥</i>에서 한다. 이 메서드에 {@code @Transactional}을
     * 붙이지 말 것.</b> 채번 충돌은 유니크 제약 위반으로 드러나는데, 그 순간 트랜잭션이
     * <b>rollback-only로 찍힌다</b> — 같은 트랜잭션 안에서 아무리 다시 시도해도 이후 쓰기가
     * 전부 실패한다. 실제로 8명이 동시에 접수했을 때 <b>1명만 성공</b>했다
     * ({@code ConcurrencyTest}). 그래서 시도마다 <b>새 트랜잭션</b>을 연다.
     *
     * <p>사람 생성까지 통째로 재시도 범위에 넣는다. 사람만 바깥 트랜잭션에서 만들면
     * 등록 건을 새 트랜잭션에서 넣을 때 <b>아직 커밋되지 않은 사람을 참조</b>해 FK가 깨진다.
     */
    public StudentEnrollment admit(Long academyId, short year, String name, String phone,
                                   GradeType grade, Short retakeCount, TrackType track,
                                   LocalDate birthDate, String gender, String schoolName,
                                   String address, LocalDate admissionDate,
                                   AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (admissionDate != null && admissionDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등원일을 미래로 지정할 수 없습니다.");
        }
        return withStudentNoRetry(() -> {
            // ★ 잠금 → 확인 → 생성 순서를 지킨다. 트랜잭션 밖에서 확인하면
            //   동시에 들어온 두 요청이 <b>둘 다</b> "없음" 을 읽고 둘 다 넣는다.
            lockAdmission(academyId, year, name, phone, birthDate);
            Academy academy = academyRepository.findById(academyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

            // ★ 사람을 먼저 찾는다. 신원이 특정되면 <b>시간과 무관하게</b> 기등록으로 막고
            //   학번까지 알려줄 수 있다. 시간 창을 먼저 보면 그 정보를 못 준다.
            Student student = findOrCreatePerson(name, phone, birthDate);
            rejectAlreadyEnrolled(student, academyId, year);
            // 신원을 특정하지 못한 경우(연락처·생년월일이 빈 접수)만 여기까지 온다
            rejectDoubleSubmit(academyId, year, name, phone, birthDate);
            // 상세는 같은 트랜잭션에서 채운다 — 등록 후 따로 보내면 중간에 실패했을 때
            // 학생만 남고 상세가 비는 상태가 되고, 담당자는 그걸 알 방법이 없다
            student.updateProfile(null, null, birthDate, gender, schoolName, address);
            StudentEnrollment enrollment = enroll(academy, year, student, grade, track,
                    admissionDate);
            // N수가 아니면 차수를 받지 않는다 — 현역에 "재수 1"이 박히면 통계가 어긋난다
            if (grade == GradeType.N_SU) {
                enrollment.changeRetakeCount(retakeCount);
            }
            return enrollment;
        });
    }

    /**
     * 같은 사람이면 기존 {@link Student} 를 쓰고, 아니면 새로 만든다.
     *
     * <p>학생은 <b>사람 + 등록 건 2단</b>이다. 재접수는 사람을 새로 만드는 게 아니라
     * 기존 사람에 등록 건을 붙이는 것이 이 구조의 뜻이고, {@code reEnroll} 은 이미 그렇게 한다.
     * 그런데 신규 접수만 늘 {@code new Student} 를 만들고 있었다 — 저장 버튼을 두 번 누르면
     * 등록 건뿐 아니라 <b>사람까지 두 명</b> 생긴 이유다.
     *
     * <p>★ <b>이름·생년월일·연락처가 모두 있을 때만</b> 찾는다. 하나라도 비면 신원을
     * 특정할 수 없어, 같은 사람인지 동명이인인지 판단할 근거가 없다. 그때는 새 사람으로
     * 만들고 중복 제출 방어(자문 잠금 + 시간 창)가 맡는다.
     */
    private Student findOrCreatePerson(String name, String phone, LocalDate birthDate) {
        if (phone != null && !phone.isBlank() && birthDate != null) {
            var existing = studentRepository
                    .findByNameAndBirthDateAndPhoneAndDeletedFalse(name, birthDate, phone);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return studentRepository.save(new Student(generateUniqueCode(), name, phone));
    }

    /**
     * 이미 그 지점·그 연도에 <b>재원 중</b>이면 막는다.
     *
     * <p>DB 에도 같은 규칙이 부분 유니크({@code uq_enrollment_active_person})로 걸려 있다.
     * 여기서 먼저 보는 이유는 <b>메시지 때문</b>이다 — 제약에 걸리면 학번을 알려줄 수 없어
     * "이미 등록됐다" 까지만 말하게 된다. 담당자는 그 학생을 찾아가야 한다.
     *
     * <p>퇴원·제적 건은 {@code is_current} 가 false 라 걸리지 않는다 — 같은 해 재등록은
     * 정상 업무다(F27-6).
     */
    private void rejectAlreadyEnrolled(Student student, Long academyId, short year) {
        if (student.getId() == null) {
            return;   // 방금 만든 사람이라 등록 건이 있을 수 없다
        }
        enrollmentRepository
                .findByStudentIdAndAcademyIdAndYearAndDeletedFalse(student.getId(), academyId, year)
                .filter(StudentEnrollment::isCurrent)
                .ifPresent(e -> {
                    throw new BusinessException(ErrorCode.DUPLICATE_ADMISSION,
                            "이미 등록된 학생입니다. (학번 " + e.getStudentNo() + ")");
                });
    }

    /**
     * 같은 접수를 <b>한 번에 하나만</b> 처리하도록 직렬화한다.
     *
     * <p>{@link #rejectDoubleSubmit} 은 "최근에 같은 게 있나" 를 <b>읽고</b> 판단한다.
     * 읽기와 쓰기 사이에 틈이 있어, 동시에 들어온 두 요청이 둘 다 "없음" 을 읽고
     * 둘 다 넣는다 — 실제로 동시 호출에서 학번이 연속으로 두 개 발급됐다.
     * 버튼을 빠르게 두 번 누르면 브라우저가 두 요청을 <b>거의 동시에</b> 보내므로
     * 이 경로가 현실에서 더 흔하다.
     *
     * <p>DB 자문 잠금을 쓴다. 트랜잭션이 끝나면 자동으로 풀리고, <b>인스턴스가 여럿이어도</b>
     * 같은 DB 를 보므로 함께 직렬화된다 — 애플리케이션 락으로는 안 되는 이유다.
     *
     * <p>유니크 제약으로 막지 않는 이유: 동명이인 등록은 정상이라
     * (점검표 경계 23번) 영구 제약을 걸 수 없다. 막아야 하는 것은 "같은 사람" 이 아니라
     * <b>"같은 클릭"</b> 이다.
     */
    private void lockAdmission(Long academyId, short year, String name,
                               String phone, LocalDate birthDate) {
        // String.hashCode 는 명세로 고정돼 있어 인스턴스가 달라도 같은 값이 나온다
        long key = (academyId + "|" + year + "|" + name + "|" + phone + "|" + birthDate).hashCode();
        em.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, key)
                .getSingleResult();
    }

    /**
     * 저장 버튼을 두 번 누른 것을 막는다.
     *
     * <h2>왜 화면만으로는 안 되는가</h2>
     * 버튼을 요청 중 비활성으로 바꿔도 <b>서버는 그걸 모른다.</b> 탭을 두 개 열거나
     * 네트워크가 느려 사용자가 다시 누르면 그대로 두 건이 만들어진다 — 실제로 동시에
     * 두 번 보내면 학번이 연속으로 두 개 발급됐다. 화면에서 감추는 것과 서버가 막는 것은
     * 다르다(CLAUDE.md §7).
     *
     * <h2>이름만으로 막지 않는 이유</h2>
     * <b>동명이인 등록은 정상</b>이다(점검표 경계 23번). 그래서 이름·연락처·생년월일이
     * <b>모두 같고</b> {@value #DOUBLE_SUBMIT_WINDOW_SECONDS}초 안에 들어온 것만 막는다.
     *
     * <p>⚠️ <b>완전한 방어는 아니다.</b> 연락처·생년월일을 비운 채 같은 이름을 두 사람이
     * 동시에 넣으면 여전히 통과한다. 제대로 막으려면 화면이 요청마다 멱등키를 보내야 하는데
     * 그건 프론트 변경이 필요해 여기서는 <b>가장 흔한 경로(한 사람이 두 번 누름)</b>만 닫는다.
     */
    private void rejectDoubleSubmit(Long academyId, short year, String name,
                                    String phone, LocalDate birthDate) {
        java.time.Instant since = java.time.Instant.now(clock)
                .minusSeconds(DOUBLE_SUBMIT_WINDOW_SECONDS);
        boolean duplicated = enrollmentRepository
                .findRecentByName(academyId, year, name, since).stream()
                .anyMatch(e -> java.util.Objects.equals(e.getStudent().getPhone(), phone)
                        && java.util.Objects.equals(e.getStudent().getBirthDate(), birthDate));
        if (duplicated) {
            throw new BusinessException(ErrorCode.DUPLICATE_ADMISSION);
        }
    }

    /**
     * 채번 충돌 시 <b>새 트랜잭션으로</b> 다시 시도한다.
     *
     * <p>애플리케이션 락으로 막지 않는 이유는 <b>다중 인스턴스에서 무의미</b>하기 때문이다.
     * {@code UNIQUE(academy_id, year, student_no)} 제약을 심판으로 두고, 진 쪽이 다시 계산한다.
     */
    private StudentEnrollment withStudentNoRetry(Supplier<StudentEnrollment> attempt) {
        for (int i = 0; i < STUDENT_NO_RETRY; i++) {
            try {
                return transactionTemplate.execute(status -> attempt.get());
            } catch (DataIntegrityViolationException e) {
                // ★ 채번 충돌만 다시 시도한다. 예전에는 모든 무결성 위반을 여기서 삼켰다 —
                //   성별에 'X' 를 넣으면 gender CHECK 에 걸리는데 세 번 재시도한 뒤
                //   "학번 채번에 실패했습니다" 가 나갔다. 사용자는 원인을 찾을 수 없다.
                if (!isStudentNoConflict(e)) {
                    throw e;
                }
                log.info("학번 채번 충돌, 재시도 {}/{}", i + 1, STUDENT_NO_RETRY);
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "학번 채번에 실패했습니다. 다시 시도해주세요.");
    }

    /**
     * 이 무결성 위반이 <b>학번 채번 충돌</b>인가.
     *
     * <p>제약 이름({@code uq_enrollment_student_no})으로 가른다. 예외 종류로는 못 가린다 —
     * 유니크 위반도 CHECK 위반도 같은 {@link DataIntegrityViolationException} 이다.
     */
    private boolean isStudentNoConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("uq_enrollment_student_no")) {
                return true;
            }
        }
        return false;
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
                                         String address, GradeType grade, TrackType track) {
        Student student = studentRepository.save(new Student(generateUniqueCode(), name, phone));
        student.updateProfile(null, null, birthDate, gender, schoolName, address);
        return enroll(academy, year, student, grade, track, null);
    }

    /** 학생 정보 수정. {@code null} 인자는 변경하지 않는다. */
    @Transactional
    public StudentEnrollment update(Long enrollmentId, String name, String phone, LocalDate birthDate,
                                    String gender, String schoolName, String address, GradeType grade,
                                    Short retakeCount, TrackType track, EnrollmentStatus status,
                                    AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        enrollment.getStudent().updateProfile(name, phone, birthDate, gender, schoolName, address);
        enrollment.updateEnrollment(grade, track, status);
        // N수에서 벗어나면 차수를 지운다 — 남겨두면 현역인데 "재수 1"이 붙는다
        if (enrollment.getGrade() != GradeType.N_SU) {
            enrollment.changeRetakeCount(null);
        } else if (retakeCount != null) {
            enrollment.changeRetakeCount(retakeCount);
        }
        return enrollment;
    }

    /**
     * 잘못 등록한 학생 삭제 (soft).
     *
     * <p><b>정상 퇴원·제적에는 쓰지 않는다.</b> 그건 {@code POST /students/{id}/status}이고,
     * 반·좌석 해제와 앱 계정 차단까지 한 트랜잭션으로 돈다. 여기는 <b>오등록을 치우는</b>
     * 용도다 — 삭제 수단이 없어 테스트 학생이 재원생 수에 섞여 있던 자리다.
     *
     * <h2>이력이 있으면 거부한다</h2>
     * 출결 태깅이나 상벌점이 하나라도 붙었으면 실제로 다닌 학생이다. 지우면 그 기록이
     * 주인을 잃고, 출결률 분모도 조용히 바뀐다. 그 경우는 <b>퇴원·제적으로 처리</b>해야 한다.
     *
     * <h2>물리 삭제하지 않는다</h2>
     * 학번이 유니크라 행을 지우면 그 번호가 재사용되고, 나중에 같은 번호로 다른 학생을
     * 조회하게 된다. {@code is_deleted}만 세운다.
     *
     * <p><b>사람({@code student})은 다른 등록 건이 없을 때만</b> 같이 내린다 —
     * 삼수 재등록처럼 한 사람에 등록이 여럿이면 남은 쪽이 주인을 잃는다.
     */
    @Transactional
    public void delete(Long enrollmentId, AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        if (taggingLogRepository.existsByEnrollmentIdAndDeletedFalse(enrollmentId)
                || penaltyPointRepository.existsByEnrollmentIdAndDeletedFalse(enrollmentId)) {
            throw new BusinessException(ErrorCode.STUDENT_HAS_HISTORY);
        }

        Student student = enrollment.getStudent();
        enrollment.markDeleted();

        boolean lastOne = enrollmentRepository.findByStudentId(student.getId()).stream()
                .noneMatch(e -> !e.isDeleted() && !e.getId().equals(enrollmentId));
        if (lastOne) {
            student.markDeleted();
        }
        log.info("학생 삭제(오등록 정리): enrollmentId={}, studentNo={}, 사람도 삭제={}",
                enrollmentId, enrollment.getStudentNo(), lastOne);
    }

    /**
     * 재등록. <b>같은 사람에 등록 건만 추가</b>하고 이전 건은 이력으로 내린다.
     *
     * <p>새 사람으로 만들면 상담 이력·신상기록부가 끊기고 동일인 추적이 불가능해진다
     * (독학재수라 삼수 재등록이 실제로 흔하다).
     */
    public StudentEnrollment reEnroll(Long studentId, Long academyId, short year,
                                      GradeType grade, TrackType track, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        // admit과 같은 이유로 재시도가 트랜잭션 바깥이다
        return withStudentNoRetry(() -> {
            Student student = studentRepository.findById(studentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
            Academy academy = academyRepository.findById(academyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

            enrollmentRepository.findCurrentByStudentId(studentId).ifPresent(previous -> {
                previous.expire();
                // 재배정과 같은 이유 — is_current 부분 유니크가 있다면 순서를 강제해야 한다
                enrollmentRepository.flush();
            });
            return enroll(academy, year, student, grade, track, null);
        });
    }

    /**
     * 등록 건 생성 + 학번 채번. <b>한 번만 시도한다.</b>
     *
     * <p>채번은 "다음 번호 계산 → INSERT"라 동시 접수 시 같은 번호가 나올 수 있고,
     * 그때 {@code UNIQUE(academy_id, year, student_no)}가 진 쪽을 떨어뜨린다.
     * <b>재시도는 여기서 하지 않는다</b> — 제약 위반 순간 트랜잭션이 rollback-only가 되어
     * 같은 트랜잭션 안의 재시도는 무조건 실패한다({@link #withStudentNoRetry} 참고).
     *
     * <p>{@code flush()}가 필요하다. 안 하면 위반이 트랜잭션 커밋 시점에야 터져서
     * 재시도 지점 바깥으로 예외가 새어나간다.
     */
    private StudentEnrollment enroll(Academy academy, short year, Student student,
                                     GradeType grade, TrackType track,
                                     LocalDate admissionDate) {
        String studentNo = nextStudentNo(academy.getId(), year);
        StudentEnrollment enrollment = enrollmentRepository.save(
                new StudentEnrollment(student, academy, year, studentNo, null, grade));
        enrollment.changeTrack(track);
        // ★ 채우지 않으면 등원일 검색·재원기간 산정이 전부 빈 값을 보게 된다.
        //   소급 등록(이미 다니던 학생을 나중에 넣는 경우)이 실제로 있어 지정도 받는다
        enrollment.recordAdmission(admissionDate == null ? LocalDate.now(clock) : admissionDate);
        enrollmentRepository.flush();
        return enrollment;
    }

    /**
     * 다음 학번 미리보기.
     *
     * <p>⚠️ <b>예약이 아니다.</b> 등록 시점에 다시 계산하므로, 두 사람이 동시에 화면을 열면
     * 같은 번호가 보이고 실제로는 한 명만 그 번호를 갖는다(유니크 제약이 심판이다).
     * 화면에 "예정"이라고 적어야 하고, 이 값을 등록 요청에 실어 보내면 안 된다.
     */
    @Transactional(readOnly = true)
    public String previewStudentNo(Long academyId, short year, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return nextStudentNo(academyId, year);
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
