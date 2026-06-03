package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrengthWeaknessItem {

    private String point;

    private List<String> evidenceIds;

    private List<String> sourceUrls;
}
