package com.dlab.domain.survey.repository;

import com.dlab.domain.survey.entity.Survey;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyRepository extends JpaRepository<Survey, Long> {

    /** 관리자 목록. 공지와 같이 <b>전 지점 설문도 함께</b> 내린다 — 제한되는 건 작성뿐이다. */
    @Query("""
            SELECT s FROM Survey s
            LEFT JOIN FETCH s.classMaster
            WHERE s.year = :year
              AND (s.academy IS NULL OR s.academy.id = :academyId)
              AND s.deleted = false
            ORDER BY s.closesAt DESC, s.id DESC
            """)
    List<Survey> findForAdmin(@Param("academyId") Long academyId, @Param("year") short year);

    @Query("""
            SELECT s FROM Survey s
            LEFT JOIN FETCH s.classMaster
            WHERE s.year = :year
              AND s.deleted = false
            ORDER BY s.closesAt DESC, s.id DESC
            """)
    List<Survey> findForAdminAllAcademy(@Param("year") short year);

    /**
     * 앱 목록 후보 — 전 지점 + 내 지점 + 내 반.
     *
     * <p><b>기간 판정은 여기서 하지 않는다</b> — 서비스가 {@code Clock}으로 거른다.
     * 쿼리에 {@code now()}를 박으면 테스트가 시각을 고정하지 못한다.
     */
    @Query("""
            SELECT s FROM Survey s
            WHERE s.year = :year
              AND s.deleted = false
              AND (
                    s.scope = com.dlab.domain.survey.entity.SurveyScope.ALL
                 OR (s.scope = com.dlab.domain.survey.entity.SurveyScope.BRANCH
                     AND s.academy.id = :academyId)
                 OR (s.scope = com.dlab.domain.survey.entity.SurveyScope.CLASS
                     AND s.classMaster.id = :classId)
              )
            ORDER BY s.closesAt ASC, s.id ASC
            """)
    List<Survey> findFeed(@Param("academyId") Long academyId,
                          @Param("classId") Long classId,
                          @Param("year") short year);
}
