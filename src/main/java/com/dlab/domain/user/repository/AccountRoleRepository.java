package com.dlab.domain.user.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Set;

/**
 * 계정에 부여된 RBAC 역할 조회.
 *
 * <p>`account_role`은 조인 테이블이라 엔티티로 만들지 않고 이름만 뽑는다 —
 * 토큰에 실을 때 필요한 건 역할 이름뿐이다.
 */
public interface AccountRoleRepository extends Repository<com.dlab.domain.user.entity.Account, Long> {

    @Query(value = """
            SELECT r.name FROM account_role ar
            JOIN role r ON r.id = ar.role_id
            WHERE ar.account_id = :accountId AND r.is_deleted = false
            """, nativeQuery = true)
    Set<String> findRoleNamesByAccountId(Long accountId);
}
