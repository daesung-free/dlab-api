package com.dlab.api.app.appconfig;

import com.dlab.domain.appconfig.entity.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 앱 설정 요청 DTO. */
public final class SettingsRequests {

    private SettingsRequests() {
    }

    public record Agree(@NotNull(message = "동의 여부는 필수입니다.") Boolean agreed) {
    }

    public record ToggleNotification(
            @NotNull(message = "수신 여부는 필수입니다.") Boolean enabled) {
    }

    public record RegisterPushToken(
            @NotBlank(message = "토큰은 필수입니다.") @Size(max = 255) String token,
            @NotNull(message = "플랫폼은 필수입니다.") Platform platform) {
    }

    public record RemovePushToken(
            @NotBlank(message = "토큰은 필수입니다.") @Size(max = 255) String token) {
    }
}
