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
            @Min(value = 1, message = "정원은 1명 이상이어야 합니다.") @Max(999) Short capacity) {
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
