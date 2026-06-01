package cn.bugstack.competitoragent.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.util.StringUtils;

/**
 * 仅允许 http/https URL，空值交由其他注解决定。
 */
public class HttpUrlOnlyValidator implements ConstraintValidator<HttpUrlOnly, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        return UrlSecurityUtils.isHttpUrl(value);
    }
}
