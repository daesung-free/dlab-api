package com.dlab.api.grade;

import com.dlab.api.admin.grade.ExamFormRequests;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamPurpose;
import com.dlab.domain.user.entity.GradeType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 회차 등록 요청 변환. */
class ExamFormRequestsTest {

    @Test
    @DisplayName("★ 정렬 순서를 빼고 보내도 된다 — 0으로 들어간다(예전엔 500)")
    void sortOrderIsOptional() {
        var request = new ExamFormRequests.ExamFormCreate(8L, (short) 2026, GradeType.HIGH3,
                ExamCode.JUNE, "2026년 6월 모의평가", null, null, ExamPurpose.ACADEMY,
                LocalDate.of(2026, 6, 4));

        var command = request.toCommand();

        assertThat(command.sortOrder()).isZero();
        assertThat(command.subjects()).isEmpty();
    }
}
