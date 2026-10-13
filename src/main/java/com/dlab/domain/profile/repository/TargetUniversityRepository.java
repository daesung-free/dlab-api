package com.dlab.domain.profile.repository;

import com.dlab.domain.profile.entity.TargetUniversity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TargetUniversityRepository extends JpaRepository<TargetUniversity, Long> {

    @Query("""
            SELECT u FROM TargetUniversity u
            LEFT JOIN FETCH u.certificateFile
            WHERE u.profile.id = :profileId AND u.deleted = false
            ORDER BY u.sortOrder ASC, u.id ASC
            """)
    List<TargetUniversity> findByProfile(@Param("profileId") Long profileId);
}
