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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
public class PipelineGraphListener implements GraphListener {

    private static final ConcurrentHashMap<String, CountingLock> fileLocks = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(PipelineGraphListener.class);

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
        String uniqueId = computeUniqueId(node);


        // update the current node file
        Lock mine = getLock(uniqueId);
        try {
            mine.lock();
            File file = getFile(workflowRun, id);
            // create the parent directory if it doesn't exist
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                log.warn("Unable to create parent directory for file {}", file.getAbsolutePath());
                return;
            }
            // read the current file
            JsonFlowNode current = new JsonFlowNode(node);
            if (file.exists()) {
                String existing;
                try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                    existing = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Unable to read file {}", file.getAbsolutePath(), e);
                    return;
                }
                JSONObject json = JSONObject.fromObject(existing);
                current.merge(JsonFlowNode.fromJson(json));
            }
            writeFile(file, current);
        } finally {
            mine.unlock();
            releaseLock(uniqueId);
        }

        updateParentsChildren(node, workflowRun);
    }

    private void updateParentsChildren(FlowNode node, WorkflowRun run) {
        String id = node.getId();
        for (FlowNode parent : node.getParents()) {
            if (!(parent instanceof BlockStartNode) && !(parent instanceof BlockEndNode<?>)) {
                log.warn("Parent of node {} is not a stage", id);
                continue;
            }
            Lock parentLock = getLock(parent.getId());
            try {
                parentLock.lock();
                File file = getFile(run, parent.getId());
                if (!file.exists()) {
                    log.warn("Parent file {} does not exist", file.getAbsolutePath());
                    continue;
                }

                String foo;
                try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                    foo = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (FileNotFoundException e) {
                    log.warn("SHOULD NOT HAPPEN {}", file.getAbsolutePath(), e);
                    continue;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                JSONObject json = JSONObject.fromObject(foo, new JsonConfig());
                JsonFlowNode parentNode = JsonFlowNode.fromJson(json);
                if (parentNode.addChild(id)) {
                    writeFile(file, parentNode);
                }
            } finally {
                parentLock.unlock();
                releaseLock(parent.getId());
            }
        }
    }

    private static void writeFile(File file, JsonFlowNode node) {
        try {
            Files.writeString(file.toPath(), JsonFlowNode.toJson(node).toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Unable to write file {}", file.getAbsolutePath(), e);
        }
    }

    private static String computeUniqueId(FlowNode node) {
        return node.getId() + ":" + node.getDisplayName();
    }

    private File getFile(WorkflowRun run, String id) {
        return new File(new File(run.getRootDir(), "pipeline-graph"), id + ".json");
    }

    private Lock getLock(String file) {
        return fileLocks.compute(file, (key, existing) -> {
            if (existing == null) {
                return new CountingLock();
            }
            existing.count++;
            return existing;
        }).lock;
    }

    private void releaseLock(String file) {
        fileLocks.compute(file, (key, lock) -> {
            if (lock == null) {
                log.warn("Unable to release lock for file {}", file);
                return null;
            }
            int count = lock.count--;
            if (count <= 0) {
                if (count < 0) {
                    log.warn("Lock count for file {} is somehow negative", file);
                }
                return null;
            }
            return lock;
        });
    }

    private static class JsonFlowNode {
        final String id;
        List<String> parents;
        List<String> children;

        JsonFlowNode(FlowNode node) {
            this.id = Objects.requireNonNull(node.getId());
            this.parents = new ArrayList<>(node.getParentIds());
        }

        public JsonFlowNode(String id, List<String> parents, List<String> children) {
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


}
