package io.jenkins.plugins.pipelinegraphview.analysis;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.file.Counters;
import org.apache.commons.io.file.DeletingPathVisitor;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

@Extension
public class PipelineGraphRunListener extends RunListener<WorkflowRun> {
    @Override
    public void onCompleted(WorkflowRun workflowRun, @NonNull TaskListener listener) {
        // merge all json things into a pipeline-graph-complete/graph.json file
        File root = workflowRun.getRootDir();
        Path partialPath = root.toPath().resolve("pipeline-graph");

        List<PipelineGraphNodeListener.JsonFlowNode> nodes = new ArrayList<>();

        try {
            Files.walkFileTree(partialPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String json = Files.readString(file);
                    JSONObject obj = JSONObject.fromObject(json);
                    nodes.add(PipelineGraphNodeListener.JsonFlowNode.fromJson(obj));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JSONArray a = JSONArray.fromObject(nodes.stream()
            // not required but makes the output more readable
            .sorted(Comparator.comparing(o -> Integer.parseInt(o.id)))
            .map(PipelineGraphNodeListener.JsonFlowNode::toJson)
            .toList());

        Path completePath = root
            .toPath()
            .resolve("pipeline-graph-complete")
            .resolve("graph.json");

        try {
            Files.createDirectories(completePath.getParent());
            Files.writeString(completePath, a.toString(), StandardCharsets.UTF_8);
            Files.walkFileTree(partialPath, new DeletingPathVisitor(Counters.noopPathCounters()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
