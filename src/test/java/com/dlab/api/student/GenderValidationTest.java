package com.dlab.api.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.api.admin.student.StudentRequests;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 성별 검증 (QA 지적 11번).
 *
 * <p>전에는 {@code @Size(max = 1)} 뿐이라 {@code "X"} 가 통과해 DB CHECK 에서 터졌고,
 * 사용자에게는 <b>어느 항목인지 없는 메시지</b>가 나갔다. 한때는 채번 재시도가 그 예외를
 * 삼켜 "학번 채번에 실패했습니다" 로 보이기까지 했다.
 *
 * <p>DTO 검증이라 스프링 컨텍스트가 필요 없다 — {@code Validator} 만 띄워 확인한다.
 */
class GenderValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<StudentRequests.Admit>> validate(String gender) {
        StudentRequests.Admit request = new StudentRequests.Admit(
                1L, 2026, "검증학생", null,
                com.dlab.domain.user.entity.GradeType.N_SU, null,
                com.dlab.domain.user.entity.TrackType.SCIENCE,
                LocalDate.of(2007, 1, 1), gender, null, null, null);
        return validator.validate(request);
    }

    @Test
    @DisplayName("★ 잘못된 성별은 그 항목을 가리키는 메시지로 걸린다")
    void invalidGenderNamesTheField() {
        var violations = validate("X");

        assertThat(violations).hasSize(1);
        ConstraintViolation<?> v = violations.iterator().next();
        assertThat(v.getPropertyPath()).hasToString("gender");
        assertThat(v.getMessage()).isEqualTo("성별은 M 또는 F입니다.");
    }

    @Test
    @DisplayName("M · F 는 통과한다")
    void validGenderPasses() {
        assertThat(validate("M")).isEmpty();
        assertThat(validate("F")).isEmpty();
    }

    @Test
    @DisplayName("성별은 선택 항목이라 비워도 된다")
    void genderIsOptional() {
        assertThat(validate(null)).isEmpty();
    }

    @Test
    @DisplayName("소문자는 받지 않는다 — DB CHECK 가 대문자만 허용한다")
    void lowercaseIsRejected() {
        assertThat(validate("m")).hasSize(1);
    }
}
