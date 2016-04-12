/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.action.shard;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingService;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.FailedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.cluster.routing.ShardRouting.readShardRoutingEntry;

/**
 *
 */
public class ShardStateAction extends AbstractComponent {

    public static final String SHARD_STARTED_ACTION_NAME = "internal:cluster/shard/started";
    public static final String SHARD_FAILED_ACTION_NAME = "internal:cluster/shard/failure";

    private final TransportService transportService;
    private final ClusterService clusterService;
    private final AllocationService allocationService;
    private final RoutingService routingService;

    @Inject
    public ShardStateAction(Settings settings, ClusterService clusterService, TransportService transportService,
                            AllocationService allocationService, RoutingService routingService) {
        super(settings);
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.allocationService = allocationService;
        this.routingService = routingService;

        transportService.registerRequestHandler(SHARD_STARTED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardStartedTransportHandler());
        transportService.registerRequestHandler(SHARD_FAILED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardFailedTransportHandler());
    }

    public void shardFailed(final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, Listener listener) {
        shardFailed(shardRouting, indexUUID, message, failure, null, listener);
    }

    public void shardFailed(final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, TimeValue timeout, Listener listener) {
        DiscoveryNode masterNode = clusterService.state().nodes().masterNode();
        if (masterNode == null) {
            logger.warn("can't send shard failed for {}, no master known.", shardRouting);
            listener.onShardFailedNoMaster();
            return;
        }
        innerShardFailed(shardRouting, indexUUID, masterNode, message, failure, timeout, listener);
    }

    public void resendShardFailed(final ShardRouting shardRouting, final String indexUUID, final DiscoveryNode masterNode, final String message, @Nullable final Throwable failure, Listener listener) {
        logger.trace("{} re-sending failed shard for {}, indexUUID [{}], reason [{}]", failure, shardRouting.shardId(), shardRouting, indexUUID, message);
        innerShardFailed(shardRouting, indexUUID, masterNode, message, failure, null, listener);
    }

    private void innerShardFailed(final ShardRouting shardRouting, final String indexUUID, final DiscoveryNode masterNode, final String message, final Throwable failure, TimeValue timeout, Listener listener) {
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, message, failure);
        TransportRequestOptions options = TransportRequestOptions.EMPTY;
        if (timeout != null) {
            options = TransportRequestOptions.builder().withTimeout(timeout).build();
        }
        transportService.sendRequest(masterNode,
                SHARD_FAILED_ACTION_NAME, shardRoutingEntry, options, new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                    @Override
                    public void handleResponse(TransportResponse.Empty response) {
                        listener.onSuccess();
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        logger.warn("failed to send failed shard to {}", exp, masterNode);
                        listener.onShardFailedFailure(masterNode, exp);
                    }
                });
    }

    public void shardStarted(final ShardRouting shardRouting, String indexUUID, final String reason) {
        DiscoveryNode masterNode = clusterService.state().nodes().masterNode();
        if (masterNode == null) {
            logger.warn("{} can't send shard started for {}, no master known.", shardRouting.shardId(), shardRouting);
            return;
        }
        shardStarted(shardRouting, indexUUID, reason, masterNode);
    }

    public void shardStarted(final ShardRouting shardRouting, String indexUUID, final String reason, final DiscoveryNode masterNode) {
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, reason, null);
        logger.debug("{} sending shard started for {}", shardRoutingEntry.shardRouting.shardId(), shardRoutingEntry);
        transportService.sendRequest(masterNode,
                SHARD_STARTED_ACTION_NAME, new ShardRoutingEntry(shardRouting, indexUUID, reason, null), new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                    @Override
                    public void handleException(TransportException exp) {
                        logger.warn("failed to send shard started to [{}]", exp, masterNode);
                    }

                });
    }

    private final ShardFailedClusterStateHandler shardFailedClusterStateHandler = new ShardFailedClusterStateHandler();

    private void handleShardFailureOnMaster(final ShardRoutingEntry shardRoutingEntry) {
        logger.warn("{} received shard failed for {}", shardRoutingEntry.failure, shardRoutingEntry.shardRouting.shardId(), shardRoutingEntry);
        clusterService.submitStateUpdateTask(
                "shard-failed (" + shardRoutingEntry.shardRouting + "), message [" + shardRoutingEntry.message + "]",
                shardRoutingEntry,
                ClusterStateTaskConfig.build(Priority.HIGH),
                shardFailedClusterStateHandler,
                shardFailedClusterStateHandler);
    }

    class ShardFailedClusterStateHandler implements ClusterStateTaskExecutor<ShardRoutingEntry>, ClusterStateTaskListener {
        @Override
        public BatchResult<ShardRoutingEntry> execute(ClusterState currentState, List<ShardRoutingEntry> tasks) throws Exception {
            BatchResult.Builder<ShardRoutingEntry> batchResultBuilder = BatchResult.builder();
            List<FailedRerouteAllocation.FailedShard> shardRoutingsToBeApplied = new ArrayList<>(tasks.size());
            for (ShardRoutingEntry task : tasks) {
                shardRoutingsToBeApplied.add(new FailedRerouteAllocation.FailedShard(task.shardRouting, task.message, task.failure));
            }
            ClusterState maybeUpdatedState = currentState;
            try {
                RoutingAllocation.Result result = allocationService.applyFailedShards(currentState, shardRoutingsToBeApplied);
                if (result.changed()) {
                    maybeUpdatedState = ClusterState.builder(currentState).routingResult(result).build();
                }
                batchResultBuilder.successes(tasks);
            } catch (Throwable t) {
                batchResultBuilder.failures(tasks, t);
            }
            return batchResultBuilder.build(maybeUpdatedState);
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (oldState != newState && newState.getRoutingNodes().unassigned().size() > 0) {
                    logger.trace("unassigned shards after shard failures. scheduling a reroute.");
                    routingService.reroute("unassigned shards after shard failures, scheduling a reroute");
                }
        }

        @Override
        public void onFailure(String source, Throwable t) {
            logger.error("unexpected failure during [{}]", t, source);
        }
    }

    private final ShardStartedClusterStateHandler shardStartedClusterStateHandler =
            new ShardStartedClusterStateHandler();

    private void shardStartedOnMaster(final ShardRoutingEntry shardRoutingEntry) {
        logger.debug("received shard started for {}", shardRoutingEntry);

        clusterService.submitStateUpdateTask(
                "shard-started (" + shardRoutingEntry.shardRouting + "), reason [" + shardRoutingEntry.message + "]",
                shardRoutingEntry,
                ClusterStateTaskConfig.build(Priority.URGENT),
                shardStartedClusterStateHandler,
                shardStartedClusterStateHandler);
    }

    class ShardStartedClusterStateHandler implements ClusterStateTaskExecutor<ShardRoutingEntry>, ClusterStateTaskListener {
        @Override
        public BatchResult<ShardRoutingEntry> execute(ClusterState currentState, List<ShardRoutingEntry> tasks) throws Exception {
            BatchResult.Builder<ShardRoutingEntry> builder = BatchResult.builder();
            List<ShardRouting> shardRoutingsToBeApplied = new ArrayList<>(tasks.size());
            for (ShardRoutingEntry task : tasks) {
                shardRoutingsToBeApplied.add(task.shardRouting);
            }
            ClusterState maybeUpdatedState = currentState;
            try {
                RoutingAllocation.Result result =
                    allocationService.applyStartedShards(currentState, shardRoutingsToBeApplied, true);
                if (result.changed()) {
                    maybeUpdatedState = ClusterState.builder(currentState).routingResult(result).build();
                }
                builder.successes(tasks);
            } catch (Throwable t) {
                builder.failures(tasks, t);
            }

            return builder.build(maybeUpdatedState);
        }

        @Override
        public void onFailure(String source, Throwable t) {
            logger.error("unexpected failure during [{}]", t, source);
        }
    }

    private class ShardFailedTransportHandler implements TransportRequestHandler<ShardRoutingEntry> {

        @Override
        public void messageReceived(ShardRoutingEntry request, TransportChannel channel) throws Exception {
            handleShardFailureOnMaster(request);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

    class ShardStartedTransportHandler implements TransportRequestHandler<ShardRoutingEntry> {

        @Override
        public void messageReceived(ShardRoutingEntry request, TransportChannel channel) throws Exception {
            shardStartedOnMaster(request);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

    public static class ShardRoutingEntry extends TransportRequest {

        ShardRouting shardRouting;
        String indexUUID = IndexMetaData.INDEX_UUID_NA_VALUE;
        String message;
        Throwable failure;

        public ShardRoutingEntry() {
        }

        ShardRoutingEntry(ShardRouting shardRouting, String indexUUID, String message, @Nullable Throwable failure) {
            this.shardRouting = shardRouting;
            this.indexUUID = indexUUID;
            this.message = message;
            this.failure = failure;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            shardRouting = readShardRoutingEntry(in);
            indexUUID = in.readString();
            message = in.readString();
            failure = in.readThrowable();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardRouting.writeTo(out);
            out.writeString(indexUUID);
            out.writeString(message);
            out.writeThrowable(failure);
        }

        @Override
        public String toString() {
            return "" + shardRouting + ", indexUUID [" + indexUUID + "], message [" + message + "], failure [" + ExceptionsHelper.detailedMessage(failure) + "]";
        }
    }

    public interface Listener {
        default void onSuccess() {}
        default void onShardFailedNoMaster() {}
        default void onShardFailedFailure(final DiscoveryNode master, final TransportException e) {}
    }
}
