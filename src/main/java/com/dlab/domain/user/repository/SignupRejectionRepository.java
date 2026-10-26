package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.SignupRejection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignupRejectionRepository extends JpaRepository<SignupRejection, Long> {

    /** 가장 최근 반려. 로그인·재가입 양쪽에서 「왜 막혔나」를 이걸로 답한다. */
    Optional<SignupRejection> findFirstByAccountIdAndDeletedFalseOrderByIdDesc(Long accountId);

    /** 해소되지 않은 반려. 재신청이 들어오면 전부 닫는다. */
    List<SignupRejection> findByAccountIdAndResolvedAtIsNullAndDeletedFalse(Long accountId);
}
