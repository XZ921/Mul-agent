package cn.bugstack.competitoragent.workflow.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrengthWeaknessItem {

    private String point;

    private List<String> evidenceIds;

    private List<String> sourceUrls;
}
