package io.jenkins.plugins.pipelinegraphview;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import jenkins.appearance.AppearanceCategory;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

@Extension
@Symbol("pipelineGraphView")
public class PipelineGraphViewConfiguration extends GlobalConfiguration {

    private boolean showGraphOnJobPage;
    private boolean showStageNames;
    private boolean showStageDurations;
    private boolean showGraphOnBuildPage;

    public PipelineGraphViewConfiguration() {
        load();
    }

    public boolean isShowGraphOnJobPage() {
        return showGraphOnJobPage;
    }

    @DataBoundSetter
    public void setShowGraphOnJobPage(boolean showGraphOnJobPage) {
        this.showGraphOnJobPage = showGraphOnJobPage;
        save();
    }

    public boolean isShowStageNames() {
        return showStageNames;
    }

    @DataBoundSetter
    public void setShowStageNames(boolean showStageNames) {
        this.showStageNames = showStageNames;
        save();
    }

    public boolean isShowStageDurations() {
        return showStageDurations;
    }

    @DataBoundSetter
    public void setShowStageDurations(boolean showStageDurations) {
        this.showStageDurations = showStageDurations;
        save();
    }

    public boolean isShowGraphOnBuildPage() {
        return showGraphOnBuildPage;
    }

    @DataBoundSetter
    public void setShowGraphOnBuildPage(boolean showGraphOnBuildPage) {
        this.showGraphOnBuildPage = showGraphOnBuildPage;
        save();
    }

    public static PipelineGraphViewConfiguration get() {
        return ExtensionList.lookupSingleton(PipelineGraphViewConfiguration.class);
    }

    @NonNull
    @Override
    public GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(AppearanceCategory.class);
    }
}
