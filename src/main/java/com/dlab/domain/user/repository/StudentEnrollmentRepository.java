package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StudentEnrollmentRepository extends JpaRepository<StudentEnrollment, Long> {

    /**
     * 그 해 그 지점의 등록 건. 엑셀 일괄 업로드가 "이미 있는 학생인가"를 판정할 때 쓴다.
     *
     * <p>{@code is_current}로 거르지 않는다 — 같은 연도·지점에는 등록 건이 하나뿐이고,
     * 지난 기수 행까지 걸러버리면 이번 기수 행을 못 찾아 같은 사람을 또 만들게 된다.
     */
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
     * 해당 지점·연도의 학번 최대 일련번호. 채번의 다음 값 계산에 쓴다.
     *
     * <p>학번 형식이 {@code yyyy-NNNN}이라 뒤 4자리만 잘라 숫자로 본다.
     * 행이 없으면 0 — 그 해 첫 학생이다.
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SPLIT_PART(student_no, '-', 2) AS INTEGER)), 0)
            FROM student_enrollment
            WHERE academy_id = :academyId AND year = :year AND student_no IS NOT NULL
            """, nativeQuery = true)
    int findMaxSequence(Long academyId, short year);

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
            ORDER BY e.studentNo ASC
            """)
    List<StudentEnrollment> findCurrentByAcademyId(Long academyId);
}
