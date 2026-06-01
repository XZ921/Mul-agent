package cn.bugstack.competitoragent.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅允许 http/https URL 的校验注解。
 */
@Documented
@Constraint(validatedBy = HttpUrlOnlyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrlOnly {

    String message() default "仅支持 http/https URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
