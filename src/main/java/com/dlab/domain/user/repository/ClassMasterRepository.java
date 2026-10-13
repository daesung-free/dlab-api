package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ClassMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClassMasterRepository extends JpaRepository<ClassMaster, Long> {

    /**
     * 지점·연도별 반 목록.
     * {@code academyId}가 null이면 전 지점(상위 관리자) — SearchScope 규약과 같다.
     */
    @Query("""
            SELECT c FROM ClassMaster c
            LEFT JOIN FETCH c.homeroomTeacher
            WHERE c.deleted = false
              AND (:academyId IS NULL OR c.academy.id = :academyId)
              AND (:year IS NULL OR c.year = :year)
            ORDER BY c.name ASC
            """)
    List<ClassMaster> search(Long academyId, Short year);

    boolean existsByAcademyIdAndYearAndName(Long academyId, short year, String name);

    @Query("""
            SELECT c FROM ClassMaster c
            LEFT JOIN FETCH c.homeroomTeacher
            LEFT JOIN FETCH c.academy
            WHERE c.id = :id AND c.deleted = false
            """)
    Optional<ClassMaster> findDetailById(Long id);

    /**
     * 그 지점·연도에서 이 수험번호 반을 이미 쓰는 반.
     *
     * <p>★ <b>이름까지 돌려줘야 한다.</b> 「그 번호는 이미 쓰고 있습니다」만으로는 담당자가
     * 어느 반을 고쳐야 할지 모른다. DB 유니크 제약({@code uq_class_master_exam_no})이
     * 막아 주기는 하지만 그 오류에는 반 이름이 없다.
     */
    @Query("""
            SELECT c FROM ClassMaster c
            WHERE c.academy.id = :academyId AND c.year = :year
              AND c.examClassNo = :examClassNo AND c.deleted = false
            """)
    java.util.Optional<ClassMaster> findByExamClassNo(
            @org.springframework.data.repository.query.Param("academyId") Long academyId,
            @org.springframework.data.repository.query.Param("year") short year,
            @org.springframework.data.repository.query.Param("examClassNo") Short examClassNo);
}
