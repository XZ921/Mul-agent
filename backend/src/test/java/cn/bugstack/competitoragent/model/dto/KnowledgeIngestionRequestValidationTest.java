package cn.bugstack.competitoragent.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIngestionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldRejectUnsafeSourceUrls() {
        KnowledgeIngestionRequest request = validRequest();
        request.setSourceUrls(List.of("file:///private/data.pdf"));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void shouldRequireDomainKeyForKnowledgeIntake() {
        KnowledgeIngestionRequest request = validRequest();
        request.setDomainKey(" ");

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void shouldAcceptSafeHttpSourceUrls() {
        KnowledgeIngestionRequest request = validRequest();
        request.setSourceUrls(List.of("https://docs.example.com/product", "http://kb.example.com/spec"));

        assertTrue(validator.validate(request).isEmpty());
    }

    private KnowledgeIngestionRequest validRequest() {
        KnowledgeIngestionRequest request = new KnowledgeIngestionRequest();
        request.setDomainKey("org-product-docs");
        request.setSourceCategory("UPLOADED_DOCUMENTS");
        request.setTitle("产品资料接入");
        return request;
    }
}
