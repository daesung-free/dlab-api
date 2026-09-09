package com.dlab.api.admin.master;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public final class MasterRequests {

    private MasterRequests() {
    }

    public record CreateDepartment(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "학과명은 필수입니다.") @Size(max = 50) String name,
            @Size(max = 30) String code,
            @Size(max = 200) String memo) {
    }

    /** 전년도 복사. 원본·대상 연도를 명시로 받는다 — "작년"을 서버가 추정하면 연말에 어긋난다. */
    public record CopyYear(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "원본 연도는 필수입니다.") @Min(2000) @Max(2100) Integer fromYear,
            @NotNull(message = "대상 연도는 필수입니다.") @Min(2000) @Max(2100) Integer toYear) {
    }

    /** 과정·전형처럼 "지점 + 연도 + 이름 + 순서"만 있는 마스터 공용. */
    public record CreateNamedMaster(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 50) String name,
            @Size(max = 30) String code,
            @Size(max = 200) String memo,
            @Min(0) @Max(999) Integer sortOrder) {

        /** 순서를 안 보내면 0. 정렬은 순서 → 이름이라 0이어도 이름순으로 안정적으로 나온다. */
        public short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder.shortValue();
        }
    }




    /** 커리큘럼. 반은 선택 — 지점 공통 커리큘럼이 있을 수 있다. */
    public record CreateCurriculum(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 100) String name,
            Long classId,
            @Size(max = 30) String code,
            @Size(max = 200) String memo,
            @Min(0) @Max(999) Integer sortOrder) {

        public short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder.shortValue();
        }
    }

    public record CreateTuition(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 100) String name,
            @NotNull(message = "금액은 필수입니다.") @Min(0) Integer amount,
            @Size(max = 30) String code,
            @Size(max = 200) String memo,
            @Min(0) @Max(999) Integer sortOrder) {

        public short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder.shortValue();
        }
    }

    /**
     * 청구기준 수정.
     *
     * <p>{@code name}·{@code amount}는 {@code null}이면 변경하지 않는다.
     * 반면 <b>{@code code}·{@code memo}는 {@code null}이 "지움"</b>이다 —
     * 부분 수정으로 만들면 코드를 비우는 방법이 없어진다.
     */
    public record UpdateTuition(
            @Size(max = 100) String name,
            @Min(0) Integer amount,
            @Size(max = 30) String code,
            @Size(max = 200) String memo) {
    }

    /**
     * 이름·코드·비고 수정.
     *
     * <p><b>{@code code}·{@code memo}는 {@code null}이 "지움"이다</b> —
     * 화면은 항상 현재 값을 실어 보낸다.
     */
    public record Rename(
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 50) String name,
            @Size(max = 30) String code,
            @Size(max = 200) String memo) {
    }

    /**
     * 사용/중지.
     *
     * <p>삭제와 다르다 — 중지는 "새로 고를 수 없다"는 뜻이고 이미 그 값을 쓰는
     * 데이터는 그대로 남는다.
     */
    public record ChangeActive(@NotNull(message = "사용 여부는 필수입니다.") Boolean active) {
    }

    public record CreateTrack(
            @NotBlank(message = "계열명은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 30) String code,
            @Size(max = 200) String memo) {
    }

    /**
     * 계열 수정.
     *
     * <p><b>공용 {@link Rename}을 쓰지 않는다</b> — 그쪽 이름 상한이 50자인데
     * {@code track_master.name}은 20자라, 공용을 쓰면 21자짜리가 검증을 통과한 뒤
     * DB에서 터져 500이 나간다.
     */
    public record RenameTrack(
            @NotBlank(message = "계열명은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 30) String code,
            @Size(max = 200) String memo) {
    }

    /**
     * 강의실 등록.
     *
     * <p>{@code capacity}는 <b>선택</b>이다 — 모르면 비운다. 0을 넣으면 "정원 0명"과
     * 구분되지 않는다.
     */
    public record CreateRoom(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "강의실 번호는 필수입니다.") @Size(max = 20) String roomNo,
            @Size(max = 50) String name,
            @Min(1) @Max(999) Short capacity,
            @Size(max = 200) String memo) {
    }

    /** 강의실 수정. 방 번호도 바꿀 수 있다 — 이 번호를 참조하는 다른 표가 없다. */
    public record UpdateRoom(
            @NotBlank(message = "강의실 번호는 필수입니다.") @Size(max = 20) String roomNo,
            @Size(max = 50) String name,
            @Min(1) @Max(999) Short capacity,
            @Size(max = 200) String memo) {
    }

    public record CreateLocker(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "사물함 번호는 필수입니다.") @Size(max = 20) String lockerNo) {
    }

    public record RenameLocker(
            @NotBlank(message = "사물함 번호는 필수입니다.") @Size(max = 20) String lockerNo) {
    }

    /**
     * 사물함 블록 일괄 등록.
     *
     * @param prefix 번호 앞에 붙는 문자열. {@code "L-"}이면 {@code L-001}. 비워도 된다
     * @param digits 0으로 채울 자릿수. 비우면 3자리({@code 007})
     */
    public record CreateLockerBlock(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @Size(max = 10) String prefix,
            @NotNull(message = "시작 번호는 필수입니다.") @Min(0) Integer startNo,
            @NotNull(message = "끝 번호는 필수입니다.") @Min(0) Integer endNo,
            @Min(1) @Max(6) Integer digits) {

        String prefixOrEmpty() {
            return prefix == null ? "" : prefix;
        }

        int digitsOrDefault() {
            return digits == null ? 3 : digits;
        }
    }

    public record AssignLocker(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }

    /**
     * 장학 부여.
     *
     * <p>{@code scholarshipType}은 <b>장학 마스터에 등록된 코드</b>여야 한다
     * ({@code GET /masters/scholarship-masters/selectable}).
     *
     * <p>{@code discountRate}는 <b>선택</b>이고 저장에 쓰이지 않는다 — 마스터 값을
     * 복사하고, 보내온 값이 다르면 막는다. 화면이 잘못된 할인율을 보여주고 있다는
     * 신호이므로 조용히 바꿔치우지 않는다.
     */
    public record GrantScholarship(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId,
            @NotBlank(message = "장학 종류는 필수입니다.") @Size(max = 20) String scholarshipType,
            @DecimalMin(value = "0.0", message = "할인율은 0 이상이어야 합니다.")
            @DecimalMax(value = "100.0", message = "할인율은 100 이하여야 합니다.")
            BigDecimal discountRate) {
    }

    /** 장학 종류 등록. {@code academyId}를 비우면 전 지점 공통 — 본사만 다룰 수 있다. */
    public record CreateScholarshipMaster(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotBlank(message = "장학 코드는 필수입니다.") @Size(max = 20) String code,
            @NotBlank(message = "장학명은 필수입니다.") @Size(max = 50) String name,
            @NotNull(message = "할인율은 필수입니다.")
            @DecimalMin(value = "0.0", message = "할인율은 0 이상이어야 합니다.")
            @DecimalMax(value = "100.0", message = "할인율은 100 이하여야 합니다.")
            BigDecimal discountRate,
            Short sortOrder,
            @Size(max = 200) String memo) {

        public short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }

    /** 장학 종류 수정. <b>{@code code}는 바꿀 수 없다</b> — 부여 이력·취소 규칙이 그 값에 걸려 있다. */
    public record UpdateScholarshipMaster(
            @NotBlank(message = "장학명은 필수입니다.") @Size(max = 50) String name,
            @NotNull(message = "할인율은 필수입니다.")
            @DecimalMin(value = "0.0", message = "할인율은 0 이상이어야 합니다.")
            @DecimalMax(value = "100.0", message = "할인율은 100 이하여야 합니다.")
            BigDecimal discountRate,
            Short sortOrder,
            @Size(max = 200) String memo) {

        public short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }

    public record ChangeScholarshipMasterActive(
            @NotNull(message = "사용 여부는 필수입니다.") Boolean active) {
    }

    /**
     * 장학 종류 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * <p>{@link UpdateScholarshipMaster}와 달리 전부 선택이다. 필수로 두면 이름만
     * 바꾸려는 화면이 할인율까지 실어 보내야 하고, 그 사이 남이 바꾼 값을 덮어쓴다.
     */
    public record PatchScholarshipMaster(
            @Size(max = 50) String name,
            @DecimalMin(value = "0.0", message = "할인율은 0 이상이어야 합니다.")
            @DecimalMax(value = "100.0", message = "할인율은 100 이하여야 합니다.")
            BigDecimal discountRate,
            Short sortOrder,
            @Size(max = 200) String memo) {
    }

    /**
     * 강의실 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * @param clearCapacity 수용인원을 비우려는 경우에만 {@code true}.
     *                      {@code capacity = null}은 "안 바꿈"이라, 이게 없으면 한번 넣은
     *                      인원수를 되돌릴 방법이 없다(반 정원과 같은 방식)
     */
    public record PatchRoom(
            @Size(max = 20) String roomNo,
            @Size(max = 50) String name,
            @Min(1) @Max(999) Short capacity,
            Boolean clearCapacity,
            @Size(max = 200) String memo) {

        /** 안 보내면 "안 바꿈"이다 — null을 true로 읽으면 인원수가 조용히 지워진다. */
        public boolean clearsCapacity() {
            return Boolean.TRUE.equals(clearCapacity);
        }
    }
}
