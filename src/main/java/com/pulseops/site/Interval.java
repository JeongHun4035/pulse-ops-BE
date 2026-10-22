package com.pulseops.site;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Interval.Validator.class)
public @interface Interval {
    String message() default "intervalSeconds must be 60, 300, or 600";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    class Validator implements ConstraintValidator<Interval, Integer> {
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value == 60 || value == 300 || value == 600;
        }
    }
}
