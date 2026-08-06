package com.dlab.domain.appconfig.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * FCM 토큰 (A-21 · F-4.12-2).
 *
 * <p><b>★ 토큰은 계정이 아니라 기기에 붙는다.</b> 같은 기기에서 로그아웃하고 다른 계정으로
 * 로그인하면 <b>같은 토큰이 다른 계정으로 올라온다.</b> 계정별로 쌓기만 하면 이전 사용자에게
 * 계속 알림이 가고, 학부모 계정이라면 <b>남의 자녀 출결이 뜬다.</b>
 * 그래서 토큰을 유니크로 잡고 재등록 시 소유 계정을 갈아끼운다.
 */
@Getter
@Entity
@Table(name = "push_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 255)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Platform platform;

    /** 마지막으로 앱이 이 토큰을 확인해준 시각. 만료 단말 정리(F-4.12-2)에 쓴다. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public PushToken(Account account, String token, Platform platform, Instant lastSeenAt) {
        this.account = account;
        this.token = token;
        this.platform = platform;
        this.lastSeenAt = lastSeenAt;
    }

    /** 재등록 — 소유 계정이 바뀔 수 있다(기기를 다른 사람이 쓰는 경우). */
    public void refresh(Account account, Platform platform, Instant at) {
        this.account = account;
        this.platform = platform;
        this.lastSeenAt = at;
    }
}
