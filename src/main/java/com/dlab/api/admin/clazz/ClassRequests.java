package com.dlab.api.admin.clazz;

import com.dlab.domain.user.entity.ClassType;
import jakarta.validation.constraints.*;

import java.util.List;

public final class ClassRequests {

    private ClassRequests() {
    }

    public record ClassCreate(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "반 이름은 필수입니다.") @Size(max = 50) String name,
            @NotNull(message = "반 유형은 필수입니다.") ClassType classType,
            /** 미지정 가능 — 나중에 담임 지정 API로 붙일 수 있다. */
            Long homeroomTeacherId,
            /** 정원. <b>미지정 가능</b> — 정원을 두지 않는 반이 있다. */
            @Min(value = 1, message = "정원은 1명 이상이어야 합니다.") @Max(999) Short capacity,
            /**
             * 모의고사 반 번호(1~99, 10의 배수 제외).
             *
             * <p>★ <b>여기서 받는 이유</b>: 전에는 별도 API 로만 넣을 수 있어
             * <b>새로 만든 반이 항상 비어 있었다.</b> 그 반에 학생을 배정하면
             * <b>학번이 안 나가는데 배정은 성공</b>해서, 화면에는 「채번이 고장났다」로 보였다.
             *
             * <p>비워도 만들어진다 — 다만 <b>학생을 배정하려면 그 전에 지정해야 한다.</b>
             */
            @Min(value = 1, message = "모의고사 반 번호는 1 이상입니다.")
            @Max(value = 99, message = "모의고사 반 번호는 99 이하입니다.") Short examClassNo) {
    }

    /**
     * 반 기본정보 수정 (이름·정원).
     *
     * <p>담임은 여기가 아니라 {@code PUT /homeroom}이다 — 담임 변경은 승인 에스컬레이션
     * 대상이 바뀌는 별개의 사건이라 축을 섞지 않는다.
     *
     * @param clearCapacity 정원을 <b>비우려면</b> {@code true}. {@code capacity}를 안 보내는 것
     *                      하나로는 "안 바꿈"과 "정원 없앰"이 구분되지 않는다
     */
    public record ClassUpdate(
            @Size(max = 50) String name,
            @Min(value = 1, message = "정원은 1명 이상이어야 합니다.") @Max(999) Short capacity,
            Boolean clearCapacity) {

        /** 안 보내면 "안 바꿈"이다 — null을 true로 읽으면 정원이 조용히 지워진다. */
        public boolean clearsCapacity() {
            return Boolean.TRUE.equals(clearCapacity);
        }
    }

    /**
     * 학생 일괄 배정 (F-4.1-4 "선택 N명 일괄 배정").
     *
     * <p>단건 배정({@link AssignStudent})은 <b>그대로 남는다</b> — 이미 쓰는 곳이 있고,
     * 한 명 배정에 배열을 만들 이유가 없다.
     */
    public record AssignStudents(
            @NotEmpty(message = "배정할 학생을 선택하세요.")
            @Size(max = 500, message = "한 번에 500명까지 배정할 수 있습니다.")
            List<Long> enrollmentIds) {
    }

    public record AssignHomeroom(
            @NotNull(message = "선생님은 필수입니다.") Long teacherId) {
    }

    public record AssignStudent(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }
}
