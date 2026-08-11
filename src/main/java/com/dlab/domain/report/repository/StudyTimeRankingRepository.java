package com.dlab.domain.report.repository;

import com.dlab.domain.report.entity.RankingPeriod;
import com.dlab.domain.report.entity.StudyTimeRanking;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudyTimeRankingRepository extends JpaRepository<StudyTimeRanking, Long> {

    /** 상위 N명. {@code academyId}가 {@code null}이면 전 지점 통합 랭킹이다. */
    @Query("""
            SELECT r FROM StudyTimeRanking r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.periodType = :periodType
              AND r.periodStart = :periodStart
              AND ((:academyId IS NULL AND r.academy IS NULL)
                   OR r.academy.id = :academyId)
            ORDER BY r.ranking
            """)
    List<StudyTimeRanking> findTop(@Param("academyId") Long academyId,
                                   @Param("periodType") RankingPeriod periodType,
                                   @Param("periodStart") LocalDate periodStart,
                                   org.springframework.data.domain.Pageable pageable);

    /** 내 등수. */
    @Query("""
            SELECT r FROM StudyTimeRanking r
            WHERE r.enrollment.id = :enrollmentId
              AND r.periodType = :periodType
              AND r.periodStart = :periodStart
              AND ((:academyId IS NULL AND r.academy IS NULL)
                   OR r.academy.id = :academyId)
            """)
    Optional<StudyTimeRanking> findMine(@Param("enrollmentId") Long enrollmentId,
                                        @Param("academyId") Long academyId,
                                        @Param("periodType") RankingPeriod periodType,
                                        @Param("periodStart") LocalDate periodStart);

    /** 참여 인원 — "N명 중 3등"을 보여준다. 분모가 없으면 등수만으로는 의미를 모른다. */
    @Query("""
            SELECT COUNT(r) FROM StudyTimeRanking r
            WHERE r.periodType = :periodType
              AND r.periodStart = :periodStart
              AND ((:academyId IS NULL AND r.academy IS NULL)
                   OR r.academy.id = :academyId)
            """)
    long countParticipants(@Param("academyId") Long academyId,
                           @Param("periodType") RankingPeriod periodType,
                           @Param("periodStart") LocalDate periodStart);

    /**
     * 재적재 전 삭제.
     *
     * <p><b>물리 삭제다.</b> 여기는 원본이 아니라 언제든 다시 만들 수 있는 파생 데이터라
     * soft delete로 남기면 매일 한 벌씩 쌓여 조회 인덱스만 무거워진다.
     */
    @Modifying
    @Query("""
            DELETE FROM StudyTimeRanking r
            WHERE r.periodType = :periodType AND r.periodStart = :periodStart
            """)
    void deletePeriod(@Param("periodType") RankingPeriod periodType,
                      @Param("periodStart") LocalDate periodStart);
}
