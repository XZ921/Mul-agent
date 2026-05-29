package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StrengthWeaknessItem {

    private String point;

    private List<String> evidenceIds;
}
