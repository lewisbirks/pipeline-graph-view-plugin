package io.jenkins.plugins.pipelinegraphview.analysis;

import hudson.Extension;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineGraphNodeListener implements GraphListener {

    private static final ConcurrentHashMap<WorkflowRun, RunLockBucket> runBuckets = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(PipelineGraphNodeListener.class);

    @Override
    public void onNewHead(FlowNode node) {

        // if the node is not a stage, then we don't care about it.
        if (!(node instanceof BlockStartNode) && !(node instanceof BlockEndNode<?>)) {
            return;
        }

        FlowExecution execution = node.getExecution();
        WorkflowRun workflowRun;
        try {
            workflowRun = (WorkflowRun) execution.getOwner().getExecutable();
        } catch (IOException e) {
            log.warn("Unable to find Run from flow node.", e);
            return;
        }
        String id = node.getId();

        // update the current node file
        Lock mine = getLock(workflowRun, node);
        try {
            mine.lock();
            Path file = getFile(workflowRun, id);
            JsonFlowNode current = new JsonFlowNode(node);
            if (Files.exists(file)) {
                String existing;
                try {
                    existing = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Unable to read file {}", file.toAbsolutePath(), e);
                    return;
                }
                JSONObject json = JSONObject.fromObject(existing);
                current.merge(JsonFlowNode.fromJson(json));
            }
            writeFile(file, current);
        } finally {
            mine.unlock();
            releaseLock(workflowRun, node);
        }

        try {
            updateParentsChildren(node, workflowRun);
        } catch (IOException e) {
            log.warn("Unable to update parents/children for node {}", id, e);
        }
    }

    private void updateParentsChildren(FlowNode node, WorkflowRun run) throws IOException {
        String id = node.getId();
        for (FlowNode pnode : node.getParents()) {
            FlowNode parent = pnode;
            if (!(parent instanceof BlockStartNode) && !(parent instanceof BlockEndNode<?>)) {
                Queue<FlowNode> toCheck = new ArrayDeque<>(parent.getParents());
                while (!toCheck.isEmpty()) {
                    FlowNode next = toCheck.poll();
                    if (!(next instanceof BlockStartNode) && !(next instanceof BlockEndNode<?>)) {
                        toCheck.addAll(next.getParents());
                        continue;
                    }
                    parent = next;
                    break;
                }
            }

            String pId = parent.getId();
            Lock parentLock = getLock(run, parent);
            try {
                parentLock.lock();
                Path file = getFile(run, pId);
                if (!Files.exists(file)) {
                    log.warn("Parent file {} does not exist", file.toAbsolutePath());
                    continue;
                }

                String foo;
                try {
                    foo = Files.readString(file, StandardCharsets.UTF_8);
                } catch (FileNotFoundException e) {
                    log.warn("SHOULD NOT HAPPEN {}", file.toAbsolutePath(), e);
                    continue;
                }
                JSONObject json = JSONObject.fromObject(foo, new JsonConfig());
                JsonFlowNode parentNode = JsonFlowNode.fromJson(json);
                if (parentNode.addChild(id)) {
                    writeFile(file, parentNode);
                }
            } finally {
                parentLock.unlock();
                releaseLock(run, parent);
            }
        }
    }

    private static void writeFile(Path file, JsonFlowNode node) {
        try {
            Files.writeString(file, JsonFlowNode.toJson(node).toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Unable to write file {}", file.toAbsolutePath(), e);
        }
    }

    private Path getFile(WorkflowRun run, String id) {
        Path filePath = run.getRootDir().toPath().resolve("pipeline-graph").resolve(id + ".json");
        try {
            Files.createDirectories(filePath.getParent());
            return filePath;
        } catch (IOException e) {
            log.warn("Unable to create directory for file {}", filePath.toAbsolutePath(), e);
            throw new UncheckedIOException(e);
        }
    }

    private Lock getLock(WorkflowRun run, FlowNode node) {
        return runBuckets.computeIfAbsent(run, ignored -> new RunLockBucket()).getLock(node);
    }

    private void releaseLock(WorkflowRun run, FlowNode node) {
        runBuckets.compute(run, (key, bucket) -> {
            if (bucket == null) {
                log.warn("Unable to release lock for run {}", run);
                return null;
            }
            bucket.releaseLock(node);

            if (bucket.fileLocks.isEmpty()) {
                return null;
            }
            return bucket;
        });
    }

    public static class JsonFlowNode {
        final String id;
        List<String> parents;
        List<String> children;

        JsonFlowNode(FlowNode node) {
            this.id = Objects.requireNonNull(node.getId());
            this.parents = new ArrayList<>(node.getParentIds());
        }

        JsonFlowNode(String id, List<String> parents, List<String> children) {
            this.id = id;
            this.parents = parents;
            this.children = children;
        }

        boolean addChild(String child) {
            if (this.children == null) {
                this.children = new ArrayList<>();
            }
            return this.children.add(child);
        }

        void merge(JsonFlowNode other) {
            if (other == null) {
                return;
            }
            if (!Objects.equals(this.id, other.id)) {
                log.warn("Attempted to merge JsonFlowNode with different ids: {} != {}", this.id, other.id);
                return;
            }
            if (this.parents == null) {
                this.parents = other.parents;
            } else if (other.parents != null) {
                other.parents.forEach(parent -> {
                    if (!this.parents.contains(parent)) {
                        this.parents.add(parent);
                    }
                });
            }
            if (this.children == null) {
                this.children = other.children;
            } else if (other.children != null) {
                other.children.forEach(child -> {
                    if (!this.children.contains(child)) {
                        this.children.add(child);
                    }
                });
            }
        }

        static JsonFlowNode fromJson(JSONObject json) {
            String id = json.getString("id");
            List<String> parents = json.getJSONArray("parents").stream().map(o -> (String) o).collect(Collectors.toCollection(ArrayList::new));
            List<String> children;
            if (json.containsKey("children")) {
                children = json.getJSONArray("children").stream().map(o -> (String) o).collect(Collectors.toCollection(ArrayList::new));
            } else {
                children = new ArrayList<>();
            }
            return (new JsonFlowNode(id, parents, children));
        }

        static JSONObject toJson(JsonFlowNode node) {
            JSONObject json = new JSONObject();
            json.element("id", node.id);
            json.element("parents", node.parents);
            if (node.children != null) {
                json.element("children", node.children);
            }
            return json;
        }


    }

    private static class RunLockBucket {

        /**
         * A wrapper around a lock that keeps track of how many times it has been acquired.
         */
        private static class CountingLock {
            final Lock lock;
            int count;

            CountingLock() {
                this.lock = new ReentrantLock();
                this.count = 1;
            }
        }

        private final Map<String, CountingLock> fileLocks = new ConcurrentHashMap<>();

        Lock getLock(FlowNode node) {
            return fileLocks.compute(node.getId(), (key, existing) -> {
                if (existing == null) {
                    return new CountingLock();
                }
                existing.count++;
                return existing;
            }).lock;
        }

        void releaseLock(FlowNode node) {
            String id = node.getId();
            fileLocks.compute(id, (key, lock) -> {
                if (lock == null) {
                    log.warn("Unable to release lock for file {}", id);
                    return null;
                }
                int count = lock.count--;
                if (count <= 0) {
                    if (count < 0) {
                        log.warn("Lock count for file {} is somehow negative", id);
                    }
                    return null;
                }
                return lock;
            });

        }
    }
}
