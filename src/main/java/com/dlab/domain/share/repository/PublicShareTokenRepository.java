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

    /**
     * 그 대상의 <b>살아 있는</b> 토큰.
     *
     * <p>★ <b>발급이 멱등해야 한다.</b> 누를 때마다 새 토큰이 나오면 이미 보낸 알림톡의
     * 링크가 죽는다 — 알림톡은 보낸 뒤 본문을 바꿀 수 없다.
     *
     * <p>부분 유니크 제약({@code uq_public_share_target})이 있어 <b>최대 1건</b>이다.
     * 만료 여부는 여기서 보지 않는다 — 제약이 「살아 있는 행 하나」이므로 만료된 행도
     * 이 조회에 걸려야 하고(치우고 새로 넣어야 한다), 만료 판정은
     * {@code PublicShareToken#live} 한 곳에만 둔다.
     */
    Optional<PublicShareToken> findByTargetTypeAndTargetIdAndDeletedFalse(
            ShareTargetType targetType, Long targetId);

    boolean existsByToken(String token);
}
