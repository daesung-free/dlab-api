package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.MockExamStudentKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MockExamStudentKeyRepository extends JpaRepository<MockExamStudentKey, Long> {

    @Query("""
            SELECT k FROM MockExamStudentKey k
            WHERE k.academy.id = :academyId
              AND k.year = :year
              AND k.deleted = false
            """)
    List<MockExamStudentKey> findAllByScope(Long academyId, short year);

    @Query("""
            SELECT k FROM MockExamStudentKey k
            WHERE k.academy.id = :academyId
              AND k.year = :year
              AND k.schoolCode = :schoolCode
              AND k.classNo = :classNo
              AND k.studentNo = :studentNo
              AND k.deleted = false
            """)
    Optional<MockExamStudentKey> findByKey(Long academyId, short year, String schoolCode,
                                           String classNo, String studentNo);
}
