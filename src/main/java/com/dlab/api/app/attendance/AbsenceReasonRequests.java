package com.dlab.api.app.attendance;

import com.dlab.domain.attendance.entity.AbsenceReasonType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;

/** 앱 사유 신청 요청 본문. */
public final class AbsenceReasonRequests {

    private AbsenceReasonRequests() {
    }

    /**
     * 사유 제출.
     *
     * <p><b>시각은 유형이 정한다</b> — 외출은 시작·종료가 모두 필요하고, 조퇴는 시작만
     * (복귀가 없다), 결석·지각은 종일이라 둘 다 비운다. 어긋나면 서버가 거절한다.
     *
     * @param date 과거·미래 모두 받는다. 사전 제출은 미등원 알림 제외에 필요하고,
     *             사후 제출은 실제로 가장 흔한 흐름이다
     */
    public record Submit(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @NotNull AbsenceReasonType type,
                         @NotBlank(message = "사유를 입력해 주세요.")
                         @Size(max = 500) String reasonText,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime) {
    }
}
