package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StudentEnrollmentRepository extends JpaRepository<StudentEnrollment, Long> {

    /**
     * 이 선생님에게 담임 예외로 지정된 학생(등록 건). 담임 조회 범위가 쓴다.
     *
     * <p>반으로만 범위를 잡으면 예외 학생이 새 담임에게 안 보이고 원래 담임에게 계속 보인다.
     */
    @Query("""
            SELECT e.id FROM StudentEnrollment e
            WHERE e.homeroomOverride.id = :teacherId
              AND e.year = :year
              AND e.deleted = false
            """)
    List<Long> findIdsByHomeroomOverride(Long teacherId, short year);

    /**
     * 그 해 그 지점의 등록 건. 엑셀 일괄 업로드가 "이미 있는 학생인가"를 판정할 때 쓴다.
     *
     * <p>{@code is_current}로 거르지 않는다 — 같은 연도·지점에는 등록 건이 하나뿐이고,
     * 지난 기수 행까지 걸러버리면 이번 기수 행을 못 찾아 같은 사람을 또 만들게 된다.
     */
    /** 그 사람의 모든 등록 건(삭제분 포함). 삭제 시 "마지막 하나인가"를 판단한다. */
    List<StudentEnrollment> findByStudentId(Long studentId);

    /**
     * 같은 이름으로 <b>방금</b> 들어온 등록. 저장 버튼 중복 제출을 가리는 <b>후보</b>다.
     *
     * <p>연락처·생년월일 일치는 <b>호출부에서</b> 본다. 여기서 {@code (:phone IS NULL OR ...)}
     * 로 처리하면 Postgres 가 파라미터 타입을 정하지 못해 쿼리가 통째로 실패한다
     * ({@code could not determine data type of parameter}). 후보가 "같은 이름 + 최근 몇 초"라
     * 많아야 한두 건이므로 Java 에서 걸러도 비용이 없다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student s
            WHERE e.academy.id = :academyId
              AND e.year = :year
              AND s.name = :name
              AND e.createdAt >= :since
              AND e.deleted = false
            """)
    List<StudentEnrollment> findRecentByName(Long academyId, short year, String name,
                                             java.time.Instant since);

    Optional<StudentEnrollment> findByStudentIdAndAcademyIdAndYearAndDeletedFalse(
            Long studentId, Long academyId, short year);

    /**
     * 카드번호로 현재 유효한 등록 건 찾기.
     * rfid_no는 UNIQUE가 아니므로(이력) is_current 필터가 필수다 —
     * 빠뜨리면 퇴원생 카드로 태깅이 통과한다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.rfidNo = :rfidNo AND e.current = true AND e.deleted = false
            """)
    Optional<StudentEnrollment> findCurrentByRfidNo(String rfidNo);

    /**
     * 학번으로 현재 등록 건.
     *
     * <p>학번은 <b>매년 초기화</b>되므로 현재 등록 건({@code current})만 봐야 한다 —
     * 조건을 빼면 작년 학생이 같은 학번으로 걸린다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.studentNo = :studentNo AND e.current = true AND e.deleted = false
            """)
    Optional<StudentEnrollment> findCurrentByStudentNo(String studentNo);

    /**
     * 해당 지점·연도의 학번 최대 일련번호. 채번의 다음 값 계산에 쓴다.
     *
     * <p>학번 형식이 {@code yyyy-NNNN}이라 뒤 4자리만 잘라 숫자로 본다.
     * 행이 없으면 0 — 그 해 첫 학생이다.
     *
     * <p>★ <b>직원은 뺀다.</b> 직원 학번이 9000번대라 같이 세면 다음 학생이
     * {@code 0011}이 아니라 {@code 9004}가 되어 대역을 나눈 의미가 사라진다.
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SPLIT_PART(student_no, '-', 2) AS INTEGER)), 0)
            FROM student_enrollment
            WHERE academy_id = :academyId AND year = :year AND student_no IS NOT NULL
              AND grade <> 'STAFF'
            """, nativeQuery = true)
    int findMaxSequence(Long academyId, short year);

    /**
     * 직원 학번의 다음 일련번호.
     *
     * <p><b>대역을 나눈다</b>({@code 9000}번대) — 키오스크가 4자리 학번 키패드를 쓰는데
     * 학생과 번호가 섞이면 번호만 보고 직원인지 알 수 없다. 운영에서 눈으로 구분된다.
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SPLIT_PART(student_no, '-', 2) AS INTEGER)), :floor)
            FROM student_enrollment
            WHERE academy_id = :academyId AND year = :year
              AND grade = 'STAFF' AND student_no IS NOT NULL
            """, nativeQuery = true)
    int findMaxStaffSequence(Long academyId, short year, int floor);

    /**
     * 학생(사람)의 현재 유효한 등록 건.
     * 신청·조회는 전부 "올해 등록 건" 기준이라 앱 요청마다 이걸로 변환한다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            JOIN FETCH e.academy
            WHERE e.student.id = :studentId AND e.current = true AND e.deleted = false
            """)
    Optional<StudentEnrollment> findCurrentByStudentId(Long studentId);

    /** 지점·연도의 현재 재원 등록 건. 반 지정이 없는 루틴의 대상 학생이 이것이다. */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId AND e.year = :year
              AND e.current = true AND e.deleted = false
              AND e.grade <> com.dlab.domain.user.entity.GradeType.STAFF
            ORDER BY e.studentNo
            """)
    List<StudentEnrollment> findCurrentByAcademyAndYear(Long academyId, short year);

    /**
     * 해당 날짜에 무단 미등원인 등록 건.
     * - 그날 등원 태깅(S/A)이 없고
     * - 사전 제출된 사유도 없는 학생만 (사유를 낸 학생은 무단결석이 아니다)
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId
              AND e.current = true
              AND e.deleted = false
              AND e.enrollmentStatus = com.dlab.domain.user.entity.EnrollmentStatus.ENROLLED
              AND NOT EXISTS (
                    SELECT 1 FROM AttendanceTaggingLog t
                    WHERE t.enrollment = e AND t.attendanceDate = :date)
              AND NOT EXISTS (
                    SELECT 1 FROM AbsenceReason r
                    WHERE r.enrollment = e AND r.attendanceDate = :date AND r.deleted = false)
            """)
    List<StudentEnrollment> findUnexcusedAbsentees(Long academyId, LocalDate date);

    /**
     * 지점의 현재 재원생 전체. 키오스크 {@code getStdInfoList}가 쓴다.
     *
     * <p><b>파라미터로 연도를 받지 않는다.</b> 키오스크는 연도를 보내지 않고, 학번이 매년
     * 초기화되므로 "지금 유효한 등록 건"({@code current = true})이 곧 올해분이다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId
              AND e.current = true
              AND e.deleted = false
              AND e.grade <> com.dlab.domain.user.entity.GradeType.STAFF
            ORDER BY e.studentNo ASC
            """)
    List<StudentEnrollment> findCurrentByAcademyId(Long academyId);

    /**
     * ★ <b>직원까지 포함한 전체.</b> 키오스크 동기화 전용이다.
     *
     * <p>직원도 카드를 인식해야 출퇴근을 찍으므로 {@code getStdInfoList}에는 같이 나가야 한다.
     * <b>이 메서드를 다른 곳에서 쓰면 직원이 학생 통계·배치에 섞인다</b> —
     * 기본 조회({@link #findCurrentByAcademyId})가 학생만 반환하는 이유가 그것이다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId
              AND e.current = true
              AND e.deleted = false
            ORDER BY e.studentNo ASC
            """)
    List<StudentEnrollment> findCurrentIncludingStaff(Long academyId);

    /** 직원만. 관리자 직원 목록 화면이 쓴다. */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId
              AND e.current = true
              AND e.deleted = false
              AND e.grade = com.dlab.domain.user.entity.GradeType.STAFF
            ORDER BY e.studentNo ASC
            """)
    List<StudentEnrollment> findCurrentStaff(Long academyId);

    /**
     * 그 반에서 마지막으로 쓴 모의고사 순번. 없으면 {@code 0} 이다.
     *
     * <p>반이 바뀌어도 번호는 고정이라 <b>지금 그 반에 있는 학생</b>이 아니라
     * <b>그 반 번호로 채번된 적이 있는 전부</b>를 센다 — 그러지 않으면 반을 옮긴 학생의
     * 번호가 재사용되어 같은 번호가 둘이 된다.
     */
    @Query("""
            SELECT COALESCE(MAX(e.examSeq), 0) FROM StudentEnrollment e
            WHERE e.academy.id = :academyId
              AND e.year = :year
              AND e.examClassNo = :examClassNo
              AND e.deleted = false
            """)
    short findMaxExamSeq(Long academyId, short year, short examClassNo);
}
