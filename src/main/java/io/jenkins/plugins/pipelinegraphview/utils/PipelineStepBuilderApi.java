package io.jenkins.plugins.pipelinegraphview.utils;

import java.util.List;
import java.util.Map;

public interface PipelineStepBuilderApi {
    Map<String, List<FlowNodeWrapper>> getAllSteps();

    List<FlowNodeWrapper> getStageSteps(String startNodeId);
}
