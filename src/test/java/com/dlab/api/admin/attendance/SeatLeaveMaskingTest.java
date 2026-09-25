package com.dlab.api.admin.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.api.admin.attendance.AdminSeatLeaveController.LeaveRowResponse;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.LeaveRow;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.Status;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 좌석 이탈 목록의 이름 마스킹.
 *
 * <p>담임·행정에게는 가리고 상위 관리자에게는 원본을 준다 — 출결 현황과 같은 규칙이다.
 * <b>{@code masked}를 함께 내리는 이유</b>는 화면이 모르면 또 가려 이름이 통째로 사라지기 때문이다.
 */
class SeatLeaveMaskingTest {

    private static LeaveRow row() {
        return new LeaveRow(1L, 10L, "2026-0001", "김민지", "1반", "A", "A-01",
                Instant.parse("2026-09-22T01:00:00Z"), null, Status.OPEN, 10L, true);
    }

    @Test
    @DisplayName("상위 관리자가 아니면 이름을 가리고 가렸다고 알린다")
    void masksForNonRawViewer() {
        LeaveRowResponse response = LeaveRowResponse.of(row(), false);

        assertThat(response.name()).isEqualTo("김*지");
        assertThat(response.masked()).isTrue();
    }

    @Test
    @DisplayName("상위 관리자에게는 원본이 간다 — 화면이 다시 가리면 안 된다")
    void keepsRawForAdmin() {
        LeaveRowResponse response = LeaveRowResponse.of(row(), true);

        assertThat(response.name()).isEqualTo("김민지");
        assertThat(response.masked()).isFalse();
    }
}
