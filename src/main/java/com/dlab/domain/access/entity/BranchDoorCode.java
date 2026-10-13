package com.dlab.domain.access.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지점 도어락 번호 — <b>이력으로 쌓인다</b>.
 *
 * <p>「지금 번호」는 그 지점에서 <b>가장 최근 행</b>이다. 현재-플래그를 두지 않은 이유는
 * 교체가 곧 새 행이고 <b>순서가 그대로 이력</b>이기 때문이다 — 플래그를 두면 플래그와
 * 순서가 어긋날 여지가 생긴다.
 *
 * <h2>★ 번호를 평문으로 들고 있지 않다</h2>
 * DB 가 새면 전 지점 출입문이 열린다. 그래서 암호화해 두고, <b>중복 판정은 HMAC 해시</b>로
 * 한다 — 암호문은 매번 달라져서(IV) 비교에 쓸 수 없다.
 *
 * <p>⚠️ <b>해시가 단순 SHA 면 안 된다.</b> 네 자리 숫자는 1만 번만 돌려 보면 되므로
 * 사실상 평문이 된다. 비밀키를 섞는 이유가 그것이다({@code SecretCipher} 참고).
 */
@Getter
@Entity
@Table(name = "branch_door_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchDoorCode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /** AES-GCM 암호문({@code IV:암호문}). 같은 번호도 매번 다른 값이 된다 */
    @Column(name = "code_encrypted", nullable = false, length = 200)
    private String codeEncrypted;

    /** 재사용 판정용 HMAC. 같은 번호면 항상 같은 값이라 비교에 쓸 수 있다 */
    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    /** 교체 시각. 가장 최근 것이 「지금 번호」다 */
    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    public BranchDoorCode(Academy academy, short year, String codeEncrypted, String codeHash,
                          Instant appliedAt) {
        this.academy = academy;
        this.year = year;
        this.codeEncrypted = codeEncrypted;
        this.codeHash = codeHash;
        this.appliedAt = appliedAt;
    }
}
