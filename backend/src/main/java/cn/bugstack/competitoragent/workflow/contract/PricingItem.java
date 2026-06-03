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
public class PricingItem {

    private String model;

    private List<String> plans;

    private List<String> evidenceIds;

    private List<String> sourceUrls;
}
