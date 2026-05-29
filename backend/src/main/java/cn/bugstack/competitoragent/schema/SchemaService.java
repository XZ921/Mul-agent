package cn.bugstack.competitoragent.schema;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.SchemaRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SchemaService {

    private final AnalysisSchemaRepository schemaRepository;

    public List<AnalysisSchema> listSchemas() {
        return schemaRepository.findAll();
    }

    public AnalysisSchema getSchema(Long id) {
        return schemaRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.SCHEMA_NOT_FOUND, "id=" + id));
    }

    @Transactional
    public AnalysisSchema createSchema(SchemaRequest request) {
        if (schemaRepository.existsByName(request.getName())) {
            throw new BusinessException(ResultCode.SCHEMA_NAME_DUPLICATE, "name=" + request.getName());
        }
        AnalysisSchema schema = AnalysisSchema.builder()
                .name(request.getName())
                .description(request.getDescription())
                .dimensions(request.getDimensions())
                .isPreset(false)
                .build();
        return schemaRepository.save(schema);
    }

    @Transactional
    public AnalysisSchema updateSchema(Long id, SchemaRequest request) {
        AnalysisSchema schema = getSchema(id);
        if (schema.isPreset()) {
            throw new BusinessException(ResultCode.SCHEMA_IN_USE, "预置模板不可修改");
        }
        schema.setName(request.getName());
        schema.setDescription(request.getDescription());
        schema.setDimensions(request.getDimensions());
        return schemaRepository.save(schema);
    }

    @Transactional
    public void deleteSchema(Long id) {
        AnalysisSchema schema = getSchema(id);
        if (schema.isPreset()) {
            throw new BusinessException(ResultCode.SCHEMA_IN_USE, "预置模板不可删除");
        }
        schemaRepository.delete(schema);
    }
}
