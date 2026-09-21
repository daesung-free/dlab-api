package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionResult;
import com.dlab.domain.admission.entity.AdmissionType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdmissionResultRepository extends JpaRepository<AdmissionResult, Long> {

    @Query("""
            SELECT r FROM AdmissionResult r
            WHERE r.enrollment.id = :enrollmentId AND r.deleted = false
            ORDER BY r.admissionType, r.id
            """)
    List<AdmissionResult> findByEnrollmentId(Long enrollmentId);

    /** 개수 제한 검사용. 수시 6 · 정시 3 이 서로 다르므로 구분해서 센다 */
    @Query("""
            SELECT COUNT(r) FROM AdmissionResult r
            WHERE r.enrollment.id = :enrollmentId
              AND r.admissionType = :admissionType
              AND r.deleted = false
            """)
    long countByType(Long enrollmentId, AdmissionType admissionType);

    /**
     * 기간별 실적.
     *
     * <p>기간은 <b>입력 시각</b> 기준이다 — 합격 발표가 몰리는 시기를 끊어 보는 화면이라
     * 등록 연도만으로는 원하는 구간이 안 나온다.
     */
    @Query("""
            SELECT r FROM AdmissionResult r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId
              AND r.year = :year
              AND r.deleted = false
              AND r.createdAt >= :from AND r.createdAt < :to
            ORDER BY r.admissionType, r.universityName, r.id
            """)
    List<AdmissionResult> findInPeriod(Long academyId, short year, Instant from, Instant to);

    /**
     * 그해 지점 전체 실적. 거르기·페이징은 서비스가 한다 — 지점 한 해치라 수백 건 수준이다.
     */
    @Query("""
            SELECT r FROM AdmissionResult r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId
              AND r.year = :year
              AND r.deleted = false
            ORDER BY e.studentNo, r.admissionType, r.id
            """)
    List<AdmissionResult> findInYear(Long academyId, short year);

    /**
     * 자동완성 후보 — <b>이미 입력된 값에서 만든다.</b>
     *
     * <p>전국 대학 전형 마스터는 매년 바뀌어 갱신 부담이 크다(§4). 쌓인 값을 쓰면
     * 마스터가 없어도 지금 동작하고, 쓸수록 목록이 정확해진다.
     *
     * <p>지점을 가리지 않는다 — 대학명은 지점 데이터가 아니라 <b>공통 어휘</b>다.
     * 다른 지점이 먼저 입력해 둔 표기를 함께 쓰는 편이 표기 흔들림을 줄인다.
     */
    @Query("""
            SELECT DISTINCT r.universityName FROM AdmissionResult r
            WHERE r.deleted = false
              AND LOWER(r.universityName) LIKE LOWER(CONCAT(:keyword, '%'))
            ORDER BY r.universityName
            """)
    List<String> suggestUniversities(String keyword);

    @Query("""
            SELECT DISTINCT r.departmentName FROM AdmissionResult r
            WHERE r.deleted = false
              AND (:university IS NULL OR r.universityName = :university)
              AND LOWER(r.departmentName) LIKE LOWER(CONCAT(:keyword, '%'))
            ORDER BY r.departmentName
            """)
    List<String> suggestDepartments(String university, String keyword);
}
