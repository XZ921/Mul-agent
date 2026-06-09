package cn.bugstack.competitoragent.model.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把字符串列表统一落库为 JSON 文本。
 * <p>
 * Task RAG 相关对象需要在数据库里持久化 `sourceUrls`、`issueFlags` 这类列表，
 * 但在 Java 侧又希望直接以 `List<String>` 操作，避免到处手写序列化和反序列化逻辑。
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        try {
            return OBJECT_MAPPER.writeValueAsString(normalize(attribute));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化字符串列表失败", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(dbData, new TypeReference<List<String>>() {
            });
            return normalize(values);
        } catch (Exception e) {
            throw new IllegalArgumentException("反序列化字符串列表失败", e);
        }
    }

    /**
     * 数据进入实体前统一去重和去空白，保证后续回指链路稳定。
     */
    private List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }
}
