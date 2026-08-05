package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ParentGuardian;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParentGuardianRepository extends JpaRepository<ParentGuardian, Long> {

    /**
     * 중복가입 검사.
     *
     * <p>{@code phone}에 UNIQUE가 걸려 있지만 먼저 걸러야 제대로 된 안내가 나간다 —
     * 제약 위반으로 터지면 500이다.
     */
    boolean existsByPhoneAndDeletedFalse(String phone);

    Optional<ParentGuardian> findByPhoneAndDeletedFalse(String phone);
}
