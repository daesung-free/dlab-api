package com.dlab.api.app.firewall;

import com.dlab.domain.firewall.entity.FirewallRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class FirewallRequests {

    private FirewallRequests() {
    }

    public record Create(
            @Min(value = 1, message = "해제 시간은 1분 이상이어야 합니다.")
            @Max(value = FirewallRequest.MAX_REQUESTED_MINUTES,
                 message = "해제 시간은 최대 300분(5시간)입니다.")
            int requestedMinutes,

            @Size(max = 500, message = "사유는 500자를 넘을 수 없습니다.")
            String reason) {
    }
}
