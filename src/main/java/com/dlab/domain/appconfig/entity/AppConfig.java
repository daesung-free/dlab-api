package com.dlab.domain.appconfig.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 앱 부팅 설정 (F-4.12-3 · A-21).
 *
 * <p><b>앱에 박아두지 않고 서버가 들고 있는 이유</b> — 점검한다고 앱을 다시 심사받아
 * 배포할 수는 없다. 값이 바뀌어야 하는 것은 전부 여기 있다.
 *
 * <p>{@code academyId}·{@code year}가 없는 것은 의도된 예외다 — 지점마다 최소 지원 버전이
 * 다르면 앱을 지점별로 빌드해야 한다.
 */
@Getter
@Entity
@Table(name = "app_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Platform platform;

    /** 이 버전 미만이면 강제 업데이트. 비교는 {@link AppVersion}이 한다(사전순 금지). */
    @Column(name = "min_version", nullable = false, length = 20)
    private String minVersion;

    /** 권장 버전. 강제는 아니고 "업데이트 있음" 안내용이다. */
    @Column(name = "latest_version", length = 20)
    private String latestVersion;

    @Column(nullable = false)
    private boolean maintenance = false;

    @Column(name = "maintenance_message", length = 300)
    private String maintenanceMessage;

    /** 종료 예정 시각. 있으면 앱이 "○시까지"를 보여줄 수 있다. */
    @Column(name = "maintenance_until")
    private Instant maintenanceUntil;

    public AppConfig(Platform platform, String minVersion) {
        this.platform = platform;
        this.minVersion = minVersion;
    }

    /**
     * 버전 설정 변경.
     *
     * <p>{@code null}은 "변경하지 않음"이다 — 점검 모드만 켜려다 최소 버전이
     * 지워지는 일을 막는다.
     */
    public void updateVersions(String minVersion, String latestVersion) {
        if (minVersion != null) {
            this.minVersion = minVersion;
        }
        if (latestVersion != null) {
            this.latestVersion = latestVersion;
        }
    }

    /** 점검 모드 전환. 끌 때는 문구·종료시각도 함께 비운다 — 남으면 다음에 켤 때 옛 안내가 뜬다. */
    public void changeMaintenance(boolean maintenance, String message, Instant until) {
        this.maintenance = maintenance;
        this.maintenanceMessage = maintenance ? message : null;
        this.maintenanceUntil = maintenance ? until : null;
    }

    /** 강제 업데이트 대상인가. 앱이 버전을 안 보내면 판단하지 않는다. */
    public boolean requiresUpdate(String clientVersion) {
        return clientVersion != null && AppVersion.isBelow(clientVersion, minVersion);
    }
}
