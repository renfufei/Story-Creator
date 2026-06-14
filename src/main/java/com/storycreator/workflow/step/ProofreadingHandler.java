package com.storycreator.workflow.step;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;
import org.springframework.stereotype.Component;

@Component
public class ProofreadingHandler implements WorkflowStepHandler {

    @Override
    public WorkflowStep getStep() {
        return WorkflowStep.PROOFREADING;
    }

    @Override
    public AiRequest buildRequest(WorkflowContext context) {
        // Placeholder - actual proofreading logic is in WorkflowEngine.runProofreading()
        return AiRequest.builder()
                .systemPrompt("你是一位专业的小说校对编辑。")
                .userPrompt("请对以下内容进行校对。")
                .maxTokens(2048)
                .temperature(0.3)
                .build();
    }
}
