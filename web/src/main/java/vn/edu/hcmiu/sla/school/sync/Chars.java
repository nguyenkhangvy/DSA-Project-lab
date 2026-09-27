package vn.edu.hcmiu.sla.school.sync;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A text length counted in characters, as pydantic counts max_length on the agent's side. {@code @Size}
 * counts Java's UTF-16 units instead, where an emoji is 2: text the agent cut to exactly 5000 characters
 * would then be refused. Empty (null) text is fine; add {@code @NotNull} where it is required.
 */
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Constraint(validatedBy = Chars.Check.class)
public @interface Chars {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    String message() default "length must be between {min} and {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Check implements ConstraintValidator<Chars, String> {

        private int min;
        private int max;

        @Override
        public void initialize(Chars chars) {
            min = chars.min();
            max = chars.max();
        }

        @Override
        public boolean isValid(String text, ConstraintValidatorContext context) {
            if (text == null) {
                return true;
            }
            int length = text.codePointCount(0, text.length());
            return length >= min && length <= max;
        }
    }
}
