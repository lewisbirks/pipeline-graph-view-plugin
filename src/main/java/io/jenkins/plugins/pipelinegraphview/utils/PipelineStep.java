package io.jenkins.plugins.pipelinegraphview.utils;

import io.jenkins.plugins.pipelinegraphview.analysis.TimingInfo;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;

public class PipelineStep extends AbstractPipelineNode {
    final String stageId;
    private final PipelineInputStep inputStep;

    public PipelineStep(
            String id,
            String name,
            PipelineState state,
            String type,
            String title,
            String stageId,
            PipelineInputStep inputStep,
            TimingInfo timingInfo) {
        super(id, name, state, type, title, timingInfo);
        this.stageId = stageId;
        this.inputStep = inputStep;
    }

    public static class PipelineStepJsonProcessor extends AbstractPipelineNodeJsonProcessor {

        public static void configure(JsonConfig config) {
            baseConfigure(config);
            config.registerJsonBeanProcessor(PipelineStep.class, new PipelineStepJsonProcessor());
            PipelineInputStep.PipelineInputStepJsonProcessor.configure(config);
        }

        @Override
        public JSONObject processBean(Object bean, JsonConfig jsonConfig) {
            if (!(bean instanceof PipelineStep step)) {
                return null;
            }
            JSONObject json = create(step, jsonConfig);

            json.element("stageId", step.stageId);
            if (step.inputStep != null) {
                json.element("inputStep", step.inputStep, jsonConfig);
            }
            return json;
        }
    }
}
