package com.dlab.api.admin.student;

import jakarta.validation.constraints.NotBlank;
import com.dlab.common.validation.BirthDateRange;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public final class StudentRequests {

    private StudentRequests() {
    }

    /**
     * 연락처 형식.
     *
     * <p><b>이 값이 알림톡 수신처가 된다.</b> 패턴이 없을 때 "전화번호아님abc"가 그대로
     * 저장됐고, 발송이 조용히 실패해도 화면에는 그 번호가 정상처럼 보인다.
     *
     * <p>하이픈은 있어도 없어도 받는다 — 운영자가 붙여넣는 형태가 제각각이라 형식을
     * 하나로 강요하면 정상 번호가 거부된다. 국번 판별은 {@code common/privacy/Masking}이 한다.
     */
    private static final String PHONE_PATTERN = "^0\\d{1,2}-?\\d{3,4}-?\\d{4}$";

    /** 성별 코드. DB CHECK(gender IN ('M','F'))와 같은 값이다 — 한쪽만 고치면 어긋난다. */
    private static final String GENDER_PATTERN = "^[MF]$";

    /** 신규 접수. 학번은 서버가 채번하므로 받지 않는다. */
    /**
     * 신규 접수 등록.
     *
     * <p><b>상세 정보를 여기서 함께 받는다.</b> 등록과 수정으로 나눠 두 번 보내면, ①이 성공하고
     * ②가 실패했을 때 <b>학생은 이미 등록됐는데 화면은 "저장 실패"만 알리게 되어</b> 담당자가
     * 다시 등록하고 중복 학생이 생긴다.
     *
     * @param admissionDate 등원일. 생략하면 등록일이다. <b>중도 입학·소급 등록</b>에서
     *                      실제 등원일과 어긋나면 교습비 일할 계산까지 틀어진다
     */
    public record Admit(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @Size(max = 20)
            @Pattern(regexp = PHONE_PATTERN, message = "연락처 형식이 올바르지 않습니다.")
            String phone,
            @NotNull(message = "학년 구분은 필수입니다.") GradeType grade,
            /** N수 차수 — 1=재수, 2=삼수. N수가 아니면 비운다 */
            @Min(1) @Max(10) Short retakeCount,
            TrackType track,
            /**
             * 미래 날짜는 받지 않는다 — 2099-01-01 이 그대로 저장되던 자리다.
             * <b>하한도 둔다</b>: 1800-01-01 이 통과해 명단에 남던 자리이기도 하다.
             * 오타를 그대로 저장하면 나이·학년 통계가 조용히 어긋난다.
             */
            @Past(message = "생년월일은 과거 날짜여야 합니다.")
            @BirthDateRange LocalDate birthDate,
            /**
             * {@code M} / {@code F} 만 받는다.
             *
             * <p>전에는 길이만 봤다. 그래서 {@code "X"} 가 검증을 통과해 DB CHECK 에서 터졌고,
             * 사용자에게는 <b>어느 항목이 문제인지 없는 메시지</b>가 나갔다
             * (한때는 "학번 채번에 실패했습니다" 까지 나왔다). 앱 가입 쪽과 규칙을 맞춘다.
             */
            @Pattern(regexp = GENDER_PATTERN, message = "성별은 M 또는 F입니다.") String gender,
            @Size(max = 64) String schoolName,
            @Size(max = 200) String address,
            LocalDate admissionDate,
            /** 영문명(선택) */
            @Size(max = 100) String englishName,
            /** 고교 졸업연도(선택) */
            @Min(1990) @Max(2100) Short graduationYear) {

        public Admit(Long academyId, Integer year, String name, String phone, GradeType grade,
                     Short retakeCount, TrackType track, LocalDate birthDate, String gender,
                     String schoolName, String address, LocalDate admissionDate) {
            this(academyId, year, name, phone, grade, retakeCount, track, birthDate, gender,
                    schoolName, address, admissionDate, null, null);
        }
    }

    /**
     * 정보 수정. <b>보내지 않은(= {@code null}) 필드는 변경하지 않는다.</b>
     *
     * <p>학번은 여기 없다 — 서버가 채번하고 유니크 제약으로 지키는 값이라 임의 수정을 열면
     * 중복·건너뜀이 생긴다.
     */
    public record StudentUpdate(
            @Size(max = 20) String name,
            @Size(max = 20)
            @Pattern(regexp = PHONE_PATTERN, message = "연락처 형식이 올바르지 않습니다.")
            String phone,
            @Past(message = "생년월일은 과거 날짜여야 합니다.")
            @BirthDateRange LocalDate birthDate,
            /**
             * {@code M} / {@code F} 만 받는다.
             *
             * <p>전에는 길이만 봤다. 그래서 {@code "X"} 가 검증을 통과해 DB CHECK 에서 터졌고,
             * 사용자에게는 <b>어느 항목이 문제인지 없는 메시지</b>가 나갔다
             * (한때는 "학번 채번에 실패했습니다" 까지 나왔다). 앱 가입 쪽과 규칙을 맞춘다.
             */
            @Pattern(regexp = GENDER_PATTERN, message = "성별은 M 또는 F입니다.") String gender,
            @Size(max = 64) String schoolName,
            @Size(max = 200) String address,
            GradeType grade,
            @Min(1) @Max(10) Short retakeCount,
            TrackType track,
            EnrollmentStatus status,
            /** 영문명. 빈 문자열을 보내면 지운다 */
            @Size(max = 100) String englishName,
            @Min(1990) @Max(2100) Short graduationYear,
            /** {@code true}면 졸업연도를 지운다 — {@code null}은 "안 바꿈"이라 따로 받는다 */
            Boolean clearGraduationYear) {

        public boolean clearsGraduationYear() {
            return Boolean.TRUE.equals(clearGraduationYear);
        }
    }

    /**
     * 재적 상태 변경.
     *
     * <p>사유는 필수가 아니지만 <b>제적처럼 다툼이 생길 수 있는 전이</b>에는 남겨야 한다.
     */
    /**
     * 상태 변경.
     *
     * <p><b>사유가 필수다.</b> 상태 변경 이력에 남는 <b>유일한 설명</b>이라, 비면
     * 나중에 "왜 퇴원 처리했나" 에 답할 수 없다 — 제적은 재등록 심사에서 다툼이 되고,
     * 퇴원은 환불 산정과 얽힌다.
     *
     * <p>휴원처럼 가벼운 전이에도 함께 건다. 전이마다 규칙이 다르면 화면이 그 조건을
     * 다시 구현해야 하고, <b>한쪽만 바뀌면 어긋난다.</b>
     */
    public record StudentChangeStatus(
            @NotNull(message = "변경할 상태는 필수입니다.") EnrollmentStatus status,
            @NotBlank(message = "변경 사유는 필수입니다.")
            @Size(max = 200) String reason) {
    }

    /** 검색조건 저장. {@code conditions}는 화면이 만든 JSON 문자열 그대로 — 서버가 파싱하지 않는다. */
    public record SaveSearch(
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 50) String name,
            @NotBlank(message = "검색조건은 필수입니다.") @Size(max = 4000) String conditions) {
    }

    /** 재등록 — 같은 사람에 등록 건만 추가한다. */
    public record ReEnroll(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotNull(message = "학년 구분은 필수입니다.") GradeType grade,
            TrackType track) {
    }
}
