package com.dlab.api.app.firewall;

import jakarta.validation.constraints.NotNull;
import com.dlab.domain.firewall.entity.FirewallRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class FirewallRequests {

    private FirewallRequests() {
    }

    public record FirewallCreate(
            @Min(value = 1, message = "해제 시간은 1분 이상이어야 합니다.")
            @Max(value = FirewallRequest.MAX_REQUESTED_MINUTES,
                 message = "해제 시간은 최대 300분(5시간)입니다.")
            Integer requestedMinutes,

            @Size(max = 500, message = "사유는 500자를 넘을 수 없습니다.")
            String reason,

            /**
             * 해제 시작·종료 시각 — "15:00~17:00".
             *
             * <p><b>비우면 승인 즉시 시작</b>이고 {@code requestedMinutes}가 쓰인다.
             * 구간을 보내면 분 수는 서버가 계산하므로 {@code requestedMinutes}는 무시된다.
             */
            java.time.Instant startAt,
            java.time.Instant endAt) {

        /** 구간을 보내면 분 수는 서버가 계산한다. 둘 다 없으면 거절된다. */
        public int minutesOrZero() {
            return requestedMinutes == null ? 0 : requestedMinutes;
        }
    }
}
