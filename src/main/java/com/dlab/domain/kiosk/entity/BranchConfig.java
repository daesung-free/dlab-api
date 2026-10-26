package com.dlab.domain.kiosk.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 지점별 외부 연동 설정 (요구사항 F-4.10-7). 지점당 1행.
 *
 * <p>물리 키오스크가 여러 대여도 1행이면 충분하다 — 기기는 우리에게 직접 인증하지 않고
 * 키오스크 백엔드가 지점 단위로 한 번 인증한다.
 *
 * <p><b>{@code kioskSecret}은 사실상 평문 비밀값이다.</b>
 * 검증식이 {@code MD5(yyyyMMdd + secret)}라 원문을 다시 계산해야 해서 해시로 저장할 수 없다.
 * 저장소 암호화(RDS at-rest)를 전제로 하고, 화면에는 마스킹해서 노출한다.
 * 값을 로그·커밋에 남기지 말 것(CLAUDE.md §8).
 */
@Getter
@Entity
@Table(name = "branch_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    /** 키오스크가 {@code /auth/token}에 제시하는 식별자. */
    @Column(name = "kiosk_client_id", length = 100)
    private String kioskClientId;

    @Column(name = "kiosk_secret", length = 200)
    private String kioskSecret;

    /** 디랩 자체 명의 PG 가맹점코드. 급식·등록비 공통. */
    @Column(name = "pg_merchant_code", length = 100)
    private String pgMerchantCode;

    /**
     * Zyxel Nebula 장비 ID.
     *
     * <p>★ 제어는 <b>사이트 단위</b>로 한다({@link #nebulaSiteId}) — Voucher API 가 그렇다.
     * 이 값은 장애 시 장비에 직접 붙는 수동 폴백용으로 남긴다.
     */
    @Column(name = "nebula_device_id", length = 100)
    private String nebulaDeviceId;

    /**
     * Zyxel Nebula 사이트 ID — <b>Voucher 호출 경로에 들어간다.</b>
     *
     * <p>지점마다 다르고, 이 값이 없으면 그 지점은 와이파이 제어가 되지 않는다.
     * 키오스크 자격증명과 같은 자리라 <b>본사만</b> 수정한다.
     */
    @Column(name = "nebula_site_id", length = 64)
    private String nebulaSiteId;

    /**
     * 키오스크가 {@code /kiosk/**} 를 마지막으로 부른 시각.
     *
     * <h2>★★ 왜 남기는가</h2>
     * <b>단말이 꺼져 있으면 이탈 기록이 「0」이 아니라 「비는 것」</b>이다. 이 값이 없으면
     * 좌석표가 비었을 때 <b>「아무도 안 나갔다」와 「단말이 죽었다」를 가를 수 없다.</b>
     *
     * <p>⚠️ 키오스크는 <b>우리 장애를 알려주지 않는다</b>(§3) — 급식 조회가 실패하면 전원
     * 통과시키는 폴백이 있어 <b>우리 서버가 죽어도 화면은 정상으로 보인다.</b>
     *
     * <p>★ 하트비트가 없어 <b>호출 시각</b>을 쓴다. 정확한 생존 신호는 아니지만
     * 「오늘 한 번도 안 불렀다」는 확실히 드러난다.
     */
    @Column(name = "kiosk_last_seen_at")
    private java.time.Instant kioskLastSeenAt;

    /** 온라인으로 볼 시간. ★ 서버가 정한다 — 화면이 정하면 지점마다 다른 기준이 생긴다. */
    public static final java.time.Duration ONLINE_WINDOW = java.time.Duration.ofMinutes(10);

    /** 갱신 간격. ★ 호출마다 UPDATE 하면 태깅이 몰리는 시간대에 같은 행을 계속 때린다. */
    private static final java.time.Duration TOUCH_INTERVAL = java.time.Duration.ofMinutes(1);

    /**
     * 통신 시각을 갱신한다.
     *
     * @return 실제로 갱신했으면 {@code true}. <b>간격 안이면 아무것도 하지 않는다</b>
     */
    public boolean touchKioskSeen(java.time.Instant now) {
        if (kioskLastSeenAt != null
                && kioskLastSeenAt.isAfter(now.minus(TOUCH_INTERVAL))) {
            return false;
        }
        this.kioskLastSeenAt = now;
        return true;
    }

    /** 살아 있다고 볼 수 있는가. ⚠️ 한산한 시간대에는 {@code false} 가 될 수 있다. */
    public boolean isKioskOnline(java.time.Instant now) {
        return kioskLastSeenAt != null && kioskLastSeenAt.isAfter(now.minus(ONLINE_WINDOW));
    }

    /**
     * 지점별 정책값.
     *
     * <p><b>스키마가 확정되지 않은 값의 임시 수용소로 쓰지 말 것.</b> 여기 들어간 값은
     * 타입 검증도 인덱스도 없어서, 오타가 나도 기동 시점에는 아무 일이 없고 그 값을 읽는
     * 화면에서만 조용히 비어 보인다. 지속적으로 쓰는 값이면 컬럼으로 승격한다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> configJson = new LinkedHashMap<>();

    public BranchConfig(Long academyId) {
        this.academyId = academyId;
    }

    public void issueKioskCredential(String clientId, String secret) {
        this.kioskClientId = clientId;
        this.kioskSecret = secret;
    }

    public boolean hasKioskCredential() {
        return kioskClientId != null && kioskSecret != null;
    }

    public void changePgMerchantCode(String pgMerchantCode) {
        this.pgMerchantCode = pgMerchantCode;
    }

    public void changeNebulaDeviceId(String nebulaDeviceId) {
        this.nebulaDeviceId = nebulaDeviceId;
    }

    public void changeNebulaSiteId(String nebulaSiteId) {
        this.nebulaSiteId = nebulaSiteId;
    }

    /** 통째로 갈아끼운다 — 화면이 편집한 전체 맵을 그대로 보낸다. */
    public void replacePolicy(Map<String, String> policy) {
        this.configJson = policy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy);
    }
}
