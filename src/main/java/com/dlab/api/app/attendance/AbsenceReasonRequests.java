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
    public record AbsenceReasonSubmit(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @NotNull AbsenceReasonType type,
                         @NotBlank(message = "사유를 입력해 주세요.")
                         @Size(max = 500) String reasonText,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
                         /** 사유 카테고리(병결·가정사 등). 비워도 된다 — 등록된 게 없을 수 있다 */
                         Long categoryId,
                         /**
                          * ★★ 어느 자녀의 신청인가 — <b>학부모가 낼 때만</b> 준다
                          * (디랩 1031 ③).
                          *
                          * <p>★ <b>비우면 학생 본인</b>이다. 학생이 이 값을 보내면
                          * 거절된다(학부모 계정이 아니다).
                          *
                          * <p>⚠️ 우선 승인자가 「부모」면 <b>제출 즉시 승인</b>된다 —
                          * 앱이 <b>「제출하면 바로 승인됩니다」</b>를 미리 보여줘야 한다.
                          */
                         Long studentId) {
    }
}
