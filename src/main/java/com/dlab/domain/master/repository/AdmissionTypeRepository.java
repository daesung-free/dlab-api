package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.AdmissionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdmissionTypeRepository extends JpaRepository<AdmissionType, Long> {

    List<AdmissionType> findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(
            Long academyId, short year);

    boolean existsByAcademyIdAndYearAndNameAndDeletedFalse(Long academyId, short year, String name);
}
