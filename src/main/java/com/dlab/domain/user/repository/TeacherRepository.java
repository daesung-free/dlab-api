package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    List<Teacher> findByAcademyId(Long academyId);
}
