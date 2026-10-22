package com.dlab.domain.share.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 없이 열리는 공개 페이지의 공유 토큰.
 *
 * <p>카카오 알림톡 버튼이 가리키는 주소({@code /notice/:token}·{@code /waitlist/:token})의
 * 토큰이다. 알림톡은 단방향이라 학생이 답장하면 카카오톡 1:1 채팅으로 들어가고 우리
 * 시스템과 연결되지 않는다 — 그래서 <b>버튼으로 받는다.</b>
 *
 * <h2>★ 토큰은 추측 불가한 랜덤이다</h2>
 * 순번·학생 id 기반으로 만들지 말 것. {@code id} 를 그대로 쓰면 1 부터 세어 올리는
 * 것만으로 남의 안내문·대기자 페이지가 열린다.
 *
 * <h2>★ {@code expiresAt} 은 서버가 정한다</h2>
 * 발급 요청에서 기한을 받지 않는다 — 화면마다 다른 기한을 보내면 <b>「왜 이 링크만
 * 일찍 닫혔나」를 추적할 수 없다.</b> {@code null} 은 무기한이다.
 */
@Getter
@Entity
@Table(name = "public_share_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicShareToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    /** 전 지점 공지는 지점이 없다 — {@code null} 이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private ShareTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 64)
    private String token;

    /** {@code null} 이면 무기한. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public PublicShareToken(ShareTargetType targetType, Long targetId, String token,
                            Academy academy, short year, Instant expiresAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.token = token;
        this.academy = academy;
        this.year = year;
        this.expiresAt = expiresAt;
    }

    /**
     * 지금 열어도 되는 링크인가.
     *
     * <p>⚠️ <b>만료를 「없음」과 다르게 다루지 말 것.</b> 호출부는 둘을 같은 응답
     * ({@code available:false})으로 내려야 한다 — 갈라서 내리면 토큰을 두드려 보는 쪽에
     * 「이건 있다」는 힌트가 된다.
     */
    public boolean live(Instant now) {
        return !isDeleted() && (expiresAt == null || now.isBefore(expiresAt));
    }
}
