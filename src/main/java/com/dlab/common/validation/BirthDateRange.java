package com.dlab.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * 생년월일의 <b>하한</b>을 검사한다.
 *
 * <p>{@code @Past} 는 미래만 막는다 — {@code 1800-01-01} 이 그대로 저장돼 명단에 남던 자리다.
 * 독학재수 학원이라 재원생은 10대 후반~20대인데, 오타 하나가 통계(나이·학년 분포)를
 * 조용히 망가뜨린다. 저장된 뒤에는 그게 오타인지 아닌지 아무도 판단할 수 없다.
 *
 * <p><b>느슨하게 잡는다.</b> {@value #MIN_AGE}세~{@value #MAX_AGE}세는 실제 재원 연령대보다
 * 훨씬 넓다 — 여기서 좁히면 검정고시·장기 휴학처럼 드문 경우가 막힌다. 목적은 연령 심사가
 * 아니라 <b>명백한 오타를 거르는 것</b>이다.
 */
@Documented
@Constraint(validatedBy = BirthDateRange.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BirthDateRange {

    /** 이보다 어리면 오타로 본다. */
    int MIN_AGE = 10;

    /** 이보다 많으면 오타로 본다. */
    int MAX_AGE = 100;

    String message() default "생년월일을 확인해 주세요. " + MIN_AGE + "세~" + MAX_AGE + "세 범위만 등록됩니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BirthDateRange, LocalDate> {

        /**
         * ★ 시계를 주입받지 않는다. 처음에는 {@code Clock} 을 받게 했는데, 그러면
         * <b>스프링 밖에서 이 검증기를 만들 수 없다</b> — 순수 {@code Validator} 로 DTO 를
         * 검증하면 {@code HV000064: Unable to instantiate ConstraintValidator} 로 터진다.
         *
         * <p>여기서는 벽시계를 써도 된다. 판정 폭이 {@value #MIN_AGE}~{@value #MAX_AGE}년이라
         * 실행 시각이 몇 시인지가 결과를 바꾸지 않는다 — 분 단위가 걸리는
         * 출결 판정과는 성격이 다르다.
         */
        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            // null 은 여기서 판단하지 않는다 — 필수 여부는 @NotNull 의 몫이고,
            // 생년월일은 선택 항목이라 비워둘 수 있다
            if (value == null) {
                return true;
            }
            LocalDate today = LocalDate.now();
            return !value.isBefore(today.minusYears(MAX_AGE))
                    && !value.isAfter(today.minusYears(MIN_AGE));
        }
    }
}
