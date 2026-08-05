package com.dlab.api.app.appconfig;

import com.dlab.domain.appconfig.entity.AppConfig;
import com.dlab.domain.appconfig.service.AppConfigService;

import java.time.Instant;

/** 앱 설정 응답. */
public final class AppConfigResponse {

    private AppConfigResponse() {
    }

    /**
     * 부팅 응답.
     *
     * @param updateRequired 앱이 보낸 버전이 최소 지원 버전 미만인가.
     *                       버전을 안 보냈으면 항상 {@code false}
     */
    public record Boot(String platform, String minVersion, String latestVersion,
                       boolean updateRequired, boolean maintenance,
                       String maintenanceMessage, Instant maintenanceUntil) {

        public static Boot from(AppConfigService.BootConfig config) {
            return new Boot(config.platform().name(), config.minVersion(), config.latestVersion(),
                    config.updateRequired(), config.maintenance(),
                    config.maintenanceMessage(), config.maintenanceUntil());
        }
    }

    /** 관리자 화면용 — 설정 원본. */
    public record Detail(Long id, String platform, String minVersion, String latestVersion,
                         boolean maintenance, String maintenanceMessage, Instant maintenanceUntil) {

        public static Detail from(AppConfig config) {
            return new Detail(config.getId(), config.getPlatform().name(), config.getMinVersion(),
                    config.getLatestVersion(), config.isMaintenance(),
                    config.getMaintenanceMessage(), config.getMaintenanceUntil());
        }
    }
}
