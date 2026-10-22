package com.dlab.domain.share.repository;

import com.dlab.domain.share.entity.PublicShareToken;
import com.dlab.domain.share.entity.ShareTargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicShareTokenRepository extends JpaRepository<PublicShareToken, Long> {

    /**
     * 토큰으로 찾는다.
     *
     * <p>★ <b>종류를 함께 대조한다.</b> 대기자 토큰을 안내문 경로에 넣으면 같은 숫자의
     * 공지가 열린다 — {@code targetId} 가 테이블마다 따로 세는 값이라 우연히 맞는다.
     *
     * <p>soft delete 된 것도 돌려준다 — 폐기와 만료를 호출부가 <b>같은 응답</b>으로
     * 내려야 하므로 판정을 한 곳({@code live()})에 모은다.
     */
    Optional<PublicShareToken> findByTokenAndTargetType(String token, ShareTargetType targetType);

    boolean existsByToken(String token);
}
