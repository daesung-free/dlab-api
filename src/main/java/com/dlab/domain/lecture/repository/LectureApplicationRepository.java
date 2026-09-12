package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.ApplicationStatus;
import com.dlab.domain.lecture.entity.LectureApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LectureApplicationRepository extends JpaRepository<LectureApplication, Long> {

    /** 확정 인원. 정원 비교의 기준이라 <b>대기·취소는 세지 않는다.</b> */
    long countByLectureIdAndStatus(Long lectureId, ApplicationStatus status);

    /**
     * 살아 있는 신청 수 — 삭제 가능 여부 판정용.
     *
     * <p>취소({@code CANCELED})는 세지 않는다. 취소한 사람만 남은 특강은
     * <b>아무도 기다리지 않는 상태</b>라 지워도 잃을 이력이 없다.
     */
    @Query("""
            SELECT count(a) FROM LectureApplication a
            WHERE a.lecture.id = :lectureId
              AND a.status <> com.dlab.domain.lecture.entity.ApplicationStatus.CANCELED
              AND a.deleted = false
            """)
    long countActiveByLectureId(@Param("lectureId") Long lectureId);

    /** 중복 신청 검사. 취소분은 재신청이 가능해야 하므로 제외한다. */
    @Query("""
            SELECT a FROM LectureApplication a
            WHERE a.lecture.id = :lectureId AND a.enrollment.id = :enrollmentId
              AND a.status <> com.dlab.domain.lecture.entity.ApplicationStatus.CANCELED
              AND a.deleted = false
            """)
    Optional<LectureApplication> findActive(@Param("lectureId") Long lectureId,
                                            @Param("enrollmentId") Long enrollmentId);

    /**
     * 명단 — 신청 시각 순.
     *
     * <p>대기 순번이 곧 이 순서다. 순번 컬럼을 두지 않는 이유는 앞사람이 취소할 때마다
     * 뒤 번호를 전부 다시 써야 하고, 그 사이 신규 신청이 끼면 어긋나기 때문이다.
     */
    @Query("""
            SELECT a FROM LectureApplication a
            JOIN FETCH a.enrollment e
            JOIN FETCH e.student
            WHERE a.lecture.id = :lectureId AND a.deleted = false
            ORDER BY a.status, a.appliedAt
            """)
    List<LectureApplication> findRoster(@Param("lectureId") Long lectureId);

    /** 승격 대상 — 가장 오래 기다린 한 명. */
    Optional<LectureApplication> findFirstByLectureIdAndStatusOrderByAppliedAtAsc(
            Long lectureId, ApplicationStatus status);

    /** 학생 본인의 신청 내역 (A-15 "신청 내역 확인"). */
    @Query("""
            SELECT a FROM LectureApplication a
            JOIN FETCH a.lecture
            WHERE a.enrollment.id = :enrollmentId AND a.deleted = false
            ORDER BY a.appliedAt DESC
            """)
    List<LectureApplication> findMine(@Param("enrollmentId") Long enrollmentId);

    /** 출석부 대상 — 취소자는 뺀다. */
    @Query("""
            SELECT a FROM LectureApplication a
            JOIN FETCH a.enrollment e
            JOIN FETCH e.student
            WHERE a.lecture.id = :lectureId AND a.deleted = false
              AND a.status = com.dlab.domain.lecture.entity.ApplicationStatus.APPLIED
            ORDER BY a.appliedAt
            """)
    List<LectureApplication> findConfirmed(@Param("lectureId") Long lectureId);
}
