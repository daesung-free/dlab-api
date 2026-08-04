package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.CourseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseTypeRepository extends JpaRepository<CourseType, Long> {

    List<CourseType> findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(
            Long academyId, short year);

    boolean existsByAcademyIdAndYearAndNameAndDeletedFalse(Long academyId, short year, String name);
}
