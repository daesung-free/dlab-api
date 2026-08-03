package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    /** 학부모 자녀연결용 고유ID 조회. */
    Optional<Student> findByUniqueCode(String uniqueCode);
}
