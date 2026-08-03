package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.EmployeeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByAcademyIdAndEmployeeType(Long academyId, EmployeeType employeeType);
}
