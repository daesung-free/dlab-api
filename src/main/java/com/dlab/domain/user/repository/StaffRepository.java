package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Staff;
import com.dlab.domain.user.entity.StaffType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    Optional<Staff> findByUserAccountId(Long userAccountId);

    List<Staff> findByBranchIdAndStaffType(Long branchId, StaffType staffType);
}
