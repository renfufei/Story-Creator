package com.storycreator.workflow.step;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.workflow.engine.WorkflowContext;

public interface WorkflowStepHandler {

    WorkflowStep getStep();

    AiRequest buildRequest(WorkflowContext context);
}
