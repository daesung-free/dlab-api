package com.dlab.domain.appconfig.repository;

import com.dlab.domain.appconfig.entity.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    Optional<PushToken> findByTokenAndDeletedFalse(String token);

    /** 발송 대상 단말. 한 계정에 기기 여러 대가 붙을 수 있다. */
    List<PushToken> findByAccountIdAndDeletedFalse(Long accountId);
}
