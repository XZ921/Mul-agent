package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PricingItem {

    private String model;

    private List<String> plans;

    private List<String> evidenceIds;
}
