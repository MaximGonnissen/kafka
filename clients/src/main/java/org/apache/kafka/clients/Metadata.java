/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidMetadataException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.utils.LogContext;
import org.slf4j.Logger;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A class encapsulating some of the logic around metadata.
 * <p>
 * This class is shared by the client thread (for partitioning) and the background sender thread.
 *
 * Metadata is maintained for only a subset of topics, which can be added to over time. When we request metadata for a
 * topic we don't have any metadata for it will trigger a metadata update.
 * <p>
 * If topic expiry is enabled for the metadata, any topic that has not been used within the expiry interval
 * is removed from the metadata refresh set after an update. Consumers disable topic expiry since they explicitly
 * manage topics while producers rely on topic expiry to limit the refresh set.
 */
public class Metadata implements Closeable {
    private final Logger log;
    private final long refreshBackoffMs;
    private final long metadataExpireMs;
    private int updateVersion;  // bumped on every metadata response
    private int requestVersion; // bumped on every new topic addition
    private long lastRefreshMs;
    private long lastSuccessfulRefreshMs;
    private KafkaException fatalException;
    private Set<String> invalidTopics;
    private Set<String> unauthorizedTopics;
    private MetadataCache cache = MetadataCache.empty();
    private boolean needUpdate;
    private final ClusterResourceListeners clusterResourceListeners;
    private boolean isClosed;
    private final Map<TopicPartition, Integer> lastSeenLeaderEpochs;

    private final long maxClusterMetadataExpireTimeMs;
    private int nodesTriedSinceLastSuccessfulRefresh;
    private boolean forceClusterMetadataUpdateFromBootstrap;

    /**
     * Create a new Metadata instance
     *
     * @param refreshBackoffMs         The minimum amount of time that must expire between metadata refreshes to avoid busy
     *                                 polling
     * @param metadataExpireMs         The maximum amount of time that metadata can be retained without refresh
     * @param logContext               Log context corresponding to the containing client
     * @param clusterResourceListeners List of ClusterResourceListeners which will receive metadata updates.
     */
    public Metadata(long refreshBackoffMs,
                    long metadataExpireMs,
                    LogContext logContext,
                    ClusterResourceListeners clusterResourceListeners) {
        this(refreshBackoffMs, metadataExpireMs, logContext, clusterResourceListeners, -1);
    }

    public Metadata(long refreshBackoffMs,
        long metadataExpireMs,
        LogContext logContext,
        ClusterResourceListeners clusterResourceListeners,
        long metadataClusterMetadataExpireTimeMs) {
        this.log = logContext.logger(Metadata.class);
        this.refreshBackoffMs = refreshBackoffMs;
        this.metadataExpireMs = metadataExpireMs;
        this.lastRefreshMs = 0L;
        this.lastSuccessfulRefreshMs = 0L;
        this.requestVersion = 0;
        this.updateVersion = 0;
        this.needUpdate = false;
        this.clusterResourceListeners = clusterResourceListeners;
        this.isClosed = false;
        this.lastSeenLeaderEpochs = new HashMap<>();
        this.invalidTopics = Collections.emptySet();
        this.unauthorizedTopics = Collections.emptySet();
        this.maxClusterMetadataExpireTimeMs = metadataClusterMetadataExpireTimeMs;
        this.nodesTriedSinceLastSuccessfulRefresh = 0;
        this.forceClusterMetadataUpdateFromBootstrap = false;
    }

    /**
     * Get the current cluster info without blocking
     */
    public synchronized Cluster fetch() {
        return cache.cluster();
    }

    /**
     * Increment the nodesTriedSinceLastSuccessfulRefresh
     */
    public synchronized void incrementNodesTriedSinceLastSuccessfulRefresh() {
        this.nodesTriedSinceLastSuccessfulRefresh++;
    }

    /**
     * Whether the client should update the cluster metadata by resolving the bootstrap server again
     * @param nowMs
     * @return true if client is not in bootstrap mode and hasn't refreshed cluster metadata for maxClusterMetadataExpireTimeMs and
     * has tried connecting to at least one node in current node set; or forceClusterMetadataUpdateFromBootstrap
     * has been set by receiving stale metadata from a different cluster
     */
    public synchronized boolean shouldUpdateClusterMetadataFromBootstrap(long nowMs) {
        return this.maxClusterMetadataExpireTimeMs > 0 &&
            (this.nodesTriedSinceLastSuccessfulRefresh >= 1 &&
            (this.lastRefreshMs != 0 && this.lastSuccessfulRefreshMs + this.maxClusterMetadataExpireTimeMs <= nowMs)) ||
            this.forceClusterMetadataUpdateFromBootstrap;
    }

    /**
     * Return the next time when the current cluster info can be updated (i.e., backoff time has elapsed).
     *
     * @param nowMs current time in ms
     * @return remaining time in ms till the cluster info can be updated again
     */
    public synchronized long timeToAllowUpdate(long nowMs) {
        return Math.max(this.lastRefreshMs + this.refreshBackoffMs - nowMs, 0);
    }

    /**
     * The next time to update the cluster info is the maximum of the time the current info will expire and the time the
     * current info can be updated (i.e. backoff time has elapsed); If an update has been request then the expiry time
     * is now
     *
     * @param nowMs current time in ms
     * @return remaining time in ms till updating the cluster info
     */
    public synchronized long timeToNextUpdate(long nowMs) {
        long timeToExpire = needUpdate ? 0 : Math.max(this.lastSuccessfulRefreshMs + this.metadataExpireMs - nowMs, 0);
        return Math.max(timeToExpire, timeToAllowUpdate(nowMs));
    }

    public long metadataExpireMs() {
        return this.metadataExpireMs;
    }

    /**
     * Request an update of the current cluster metadata info, return the current updateVersion before the update
     */
    public synchronized int requestUpdate() {
        this.needUpdate = true;
        return this.updateVersion;
    }

    /**
     * Request an update of the current cluster metadata info by resolving the bootstrap server and randomly pick
     * a node from the resolved node set. This happens when client receives stale metadata response from brokers in
     * a different cluster and need to refresh the cluster metadata without waiting for maxClusterMetadataExpireTimeMs
     */
    public synchronized void requestClusterMetadataUpdateFromBootstrap() {
        this.forceClusterMetadataUpdateFromBootstrap = true;
    }

    /**
     * Request an update for the partition metadata iff the given leader epoch is at newer than the last seen leader epoch
     */
    public synchronized boolean updateLastSeenEpochIfNewer(TopicPartition topicPartition, int leaderEpoch) {
        Objects.requireNonNull(topicPartition, "TopicPartition cannot be null");
        return updateLastSeenEpoch(topicPartition, leaderEpoch, oldEpoch -> leaderEpoch > oldEpoch, true);
    }


    public Optional<Integer> lastSeenLeaderEpoch(TopicPartition topicPartition) {
        return Optional.ofNullable(lastSeenLeaderEpochs.get(topicPartition));
    }

    /**
     * Conditionally update the leader epoch for a partition
     *
     * @param topicPartition       topic+partition to update the epoch for
     * @param epoch                the new epoch
     * @param epochTest            a predicate to determine if the old epoch should be replaced
     * @param setRequestUpdateFlag sets the "needUpdate" flag to true if the epoch is updated
     * @return true if the epoch was updated, false otherwise
     */
    private synchronized boolean updateLastSeenEpoch(TopicPartition topicPartition,
                                                     int epoch,
                                                     Predicate<Integer> epochTest,
                                                     boolean setRequestUpdateFlag) {
        Integer oldEpoch = lastSeenLeaderEpochs.get(topicPartition);
        log.trace("Determining if we should replace existing epoch {} with new epoch {}", oldEpoch, epoch);
        if (oldEpoch == null || epochTest.test(oldEpoch)) {
            log.debug("Updating last seen epoch from {} to {} for partition {}", oldEpoch, epoch, topicPartition);
            lastSeenLeaderEpochs.put(topicPartition, epoch);
            if (setRequestUpdateFlag) {
                this.needUpdate = true;
            }
            return true;
        } else {
            log.debug("Not replacing existing epoch {} with new epoch {} for partition {}", oldEpoch, epoch, topicPartition);
            return false;
        }
    }

    /**
     * Check whether an update has been explicitly requested.
     *
     * @return true if an update was requested, false otherwise
     */
    public synchronized boolean updateRequested() {
        return this.needUpdate;
    }

    /**
     * Return the cached partition info if it exists and a newer leader epoch isn't known about.
     */
    public synchronized Optional<MetadataCache.PartitionInfoAndEpoch> partitionInfoIfCurrent(TopicPartition topicPartition) {
        Integer epoch = lastSeenLeaderEpochs.get(topicPartition);
        if (epoch == null) {
            // old cluster format (no epochs)
            return cache.getPartitionInfo(topicPartition);
        } else {
            return cache.getPartitionInfoHavingEpoch(topicPartition, epoch);
        }
    }

    public synchronized void bootstrap(List<InetSocketAddress> addresses) {
        this.needUpdate = true;
        this.updateVersion += 1;
        this.cache = MetadataCache.bootstrap(addresses);
    }

    /**
     * Update metadata assuming the current request version. This is mainly for convenience in testing.
     */
    public synchronized void update(MetadataResponse response, long now) {
        this.update(this.requestVersion, response, now);
    }

    /**
     * Updates the cluster metadata. If topic expiry is enabled, expiry time
     * is set for topics if required and expired topics are removed from the metadata.
     *
     * @param requestVersion The request version corresponding to the update response, as provided by
     *     {@link #newMetadataRequestAndVersion()}.
     * @param response metadata response received from the broker
     * @param now current time in milliseconds
     */
    public synchronized void update(int requestVersion, MetadataResponse response, long now) {
        Objects.requireNonNull(response, "Metadata response cannot be null");
        if (isClosed())
            throw new IllegalStateException("Update requested after metadata close");

        if (!validateCluster(response.clusterId())) {
            //if validateCluster fails, do not update metadataCache with the wrong cluster information,
            //just return and wait for next update
            //
            //here we don't blacklist this node from the cluster's
            //node set since we don't have enough information from the response to map to the actual node,
            //and since there are usually hours to days interval before we put a removed broker to a different
            //cluster, clients should either find another node in cached node set or resolved bootstrap server
            //again and find a new node to send update metadata request, it should be ok to not blacklist this node
            requestClusterMetadataUpdateFromBootstrap();
            return;
        }

        if (requestVersion == this.requestVersion)
            this.needUpdate = false;
        else
            requestUpdate();

        this.lastRefreshMs = now;
        this.lastSuccessfulRefreshMs = now;
        this.updateVersion += 1;
        this.nodesTriedSinceLastSuccessfulRefresh = 0;
        this.forceClusterMetadataUpdateFromBootstrap = false;

        String previousClusterId = cache.cluster().clusterResource().clusterId();

        this.cache = handleMetadataResponse(response, topic -> retainTopic(topic.topic(), topic.isInternal(), now));

        Cluster cluster = cache.cluster();
        maybeSetMetadataError(cluster);

        this.lastSeenLeaderEpochs.keySet().removeIf(tp -> !retainTopic(tp.topic(), false, now));

        String newClusterId = cache.cluster().clusterResource().clusterId();
        if (!Objects.equals(previousClusterId, newClusterId)) {
            log.info("Cluster ID: {}", newClusterId);
        }
        clusterResourceListeners.onUpdate(cache.cluster().clusterResource());

        log.debug("Updated cluster metadata updateVersion {} to {}", this.updateVersion, this.cache);
    }

    private boolean validateCluster(String newClusterId) {
        String previousClusterId = this.cache.cluster().clusterResource().clusterId();
        boolean validateResult = true;

        if (previousClusterId != null && newClusterId != null && !previousClusterId.equals(newClusterId)) {
            // kafka cluster id is unique.
            // On client side, the cluster id in Metadata is only null during bootstrap, and client is
            // expected to talk to the same cluster during its life cycle. Therefore if cluster id changes
            // during metadata update, meaning this metadata update response is from a different cluster,
            // client should reject this response and not update cached cluster to the wrong cluster

            // Since removing brokers and adding to another cluster can be common operation,
            // it might be more suitable to just throw an exception for update and fail this update operation
            // instead of bringing down the client completely, so that the metadata can be updated later from
            // other brokers in the same cluster.

            // this code path will only get executed when all brokers in original cluster has been removed and
            // one/some brokers have been added to another. If only some of the original brokers were removed/added
            // to another cluster, the client should get updated metadata with valid brokers from other hosts.
            // so we can just throw an exception and close the network client

            log.error("Received metadata from a different cluster {}, current cluster {} has no valid brokers anymore,"
                + "please reboot the producer/consumer", newClusterId, previousClusterId);

            validateResult = false;
        }

        return validateResult;
    }

    private void maybeSetMetadataError(Cluster cluster) {
        clearRecoverableErrors();
        checkInvalidTopics(cluster);
        checkUnauthorizedTopics(cluster);
    }

    private void checkInvalidTopics(Cluster cluster) {
        if (!cluster.invalidTopics().isEmpty()) {
            log.error("Metadata response reported invalid topics {}", cluster.invalidTopics());
            invalidTopics = new HashSet<>(cluster.invalidTopics());
        }
    }

    private void checkUnauthorizedTopics(Cluster cluster) {
        if (!cluster.unauthorizedTopics().isEmpty()) {
            log.error("Topic authorization failed for topics {}", cluster.unauthorizedTopics());
            unauthorizedTopics = new HashSet<>(cluster.unauthorizedTopics());
        }
    }

    /**
     * Transform a MetadataResponse into a new MetadataCache instance.
     */
    private MetadataCache handleMetadataResponse(MetadataResponse metadataResponse,
                                                 Predicate<MetadataResponse.TopicMetadata> topicsToRetain) {
        Set<String> internalTopics = new HashSet<>();
        List<MetadataCache.PartitionInfoAndEpoch> partitions = new ArrayList<>();
        Map<Integer, Node> brokersById = metadataResponse.brokersById();

        for (MetadataResponse.TopicMetadata metadata : metadataResponse.topicMetadata()) {
            if (!topicsToRetain.test(metadata))
                continue;

            if (metadata.error() == Errors.NONE) {
                if (metadata.isInternal())
                    internalTopics.add(metadata.topic());

                for (MetadataResponse.PartitionMetadata partitionMetadata : metadata.partitionMetadata()) {
                    // Even if the partition's metadata includes an error, we need to handle the update to catch new epochs
                    updatePartitionInfo(metadata.topic(), partitionMetadata,
                        metadataResponse.hasReliableLeaderEpochs(), partitionInfoAndEpoch -> {
                            Node leader = partitionInfoAndEpoch.partitionInfo().leader();

                            if (leader != null && !leader.equals(brokersById.get(leader.id()))) {
                                // If we are reusing metadata from a previous response (which is possible if it
                                // contained a larger epoch), we may not have leader information available in the
                                // latest response. To keep the state consistent, we override the partition metadata
                                // so that the leader is set consistently with the broker metadata
                                PartitionInfo partitionInfo = partitionInfoAndEpoch.partitionInfo();
                                PartitionInfo partitionInfoWithoutLeader = new PartitionInfo(
                                        partitionInfo.topic(),
                                        partitionInfo.partition(),
                                        brokersById.get(leader.id()),
                                        partitionInfo.replicas(),
                                        partitionInfo.inSyncReplicas(),
                                        partitionInfo.offlineReplicas());
                                partitions.add(new MetadataCache.PartitionInfoAndEpoch(partitionInfoWithoutLeader,
                                        partitionInfoAndEpoch.epoch()));
                            } else {
                                partitions.add(partitionInfoAndEpoch);
                            }
                        });

                    if (partitionMetadata.error().exception() instanceof InvalidMetadataException) {
                        log.debug("Requesting metadata update for partition {} due to error {}",
                                new TopicPartition(metadata.topic(), partitionMetadata.partition()), partitionMetadata.error());
                        requestUpdate();
                    }
                }
            } else if (metadata.error().exception() instanceof InvalidMetadataException) {
                log.debug("Requesting metadata update for topic {} due to error {}", metadata.topic(), metadata.error());
                requestUpdate();
            }
        }

        return new MetadataCache(metadataResponse.clusterId(), brokersById.values(), partitions,
                metadataResponse.topicsByError(Errors.TOPIC_AUTHORIZATION_FAILED),
                metadataResponse.topicsByError(Errors.INVALID_TOPIC_EXCEPTION),
                internalTopics, metadataResponse.controller());
    }

    /**
     * Compute the correct PartitionInfo to cache for a topic+partition and pass to the given consumer.
     */
    private void updatePartitionInfo(String topic,
                                     MetadataResponse.PartitionMetadata partitionMetadata,
                                     boolean hasReliableLeaderEpoch,
                                     Consumer<MetadataCache.PartitionInfoAndEpoch> partitionInfoConsumer) {
        TopicPartition tp = new TopicPartition(topic, partitionMetadata.partition());

        if (hasReliableLeaderEpoch && partitionMetadata.leaderEpoch().isPresent()) {
            int newEpoch = partitionMetadata.leaderEpoch().get();
            // If the received leader epoch is at least the same as the previous one, update the metadata
            if (updateLastSeenEpoch(tp, newEpoch, oldEpoch -> newEpoch >= oldEpoch, false)) {
                PartitionInfo info = MetadataResponse.partitionMetaToInfo(topic, partitionMetadata);
                partitionInfoConsumer.accept(new MetadataCache.PartitionInfoAndEpoch(info, newEpoch));
            } else {
                // Otherwise ignore the new metadata and use the previously cached info
                cache.getPartitionInfo(tp).ifPresent(partitionInfoConsumer);
            }
        } else {
            // Handle old cluster formats as well as error responses where leader and epoch are missing
            lastSeenLeaderEpochs.remove(tp);
            PartitionInfo info = MetadataResponse.partitionMetaToInfo(topic, partitionMetadata);
            partitionInfoConsumer.accept(new MetadataCache.PartitionInfoAndEpoch(info,
                    RecordBatch.NO_PARTITION_LEADER_EPOCH));
        }
    }

    /**
     * If any non-retriable exceptions were encountered during metadata update, clear and throw the exception.
     * This is used by the consumer to propagate any fatal exceptions or topic exceptions for any of the topics
     * in the consumer's Metadata.
     */
    public synchronized void maybeThrowAnyException() {
        clearErrorsAndMaybeThrowException(this::recoverableException);
    }

    /**
     * If any fatal exceptions were encountered during metadata update, throw the exception. This is used by
     * the producer to abort waiting for metadata if there were fatal exceptions (e.g. authentication failures)
     * in the last metadata update.
     */
    public synchronized void maybeThrowFatalException() {
        KafkaException metadataException = this.fatalException;
        if (metadataException != null) {
            fatalException = null;
            throw metadataException;
        }
    }

    /**
     * If any non-retriable exceptions were encountered during metadata update, throw exception if the exception
     * is fatal or related to the specified topic. All exceptions from the last metadata update are cleared.
     * This is used by the producer to propagate topic metadata errors for send requests.
     */
    public synchronized void maybeThrowExceptionForTopic(String topic) {
        clearErrorsAndMaybeThrowException(() -> recoverableExceptionForTopic(topic));
    }

    private void clearErrorsAndMaybeThrowException(Supplier<KafkaException> recoverableExceptionSupplier) {
        KafkaException metadataException = Optional.ofNullable(fatalException).orElseGet(recoverableExceptionSupplier);
        fatalException = null;
        clearRecoverableErrors();
        if (metadataException != null)
            throw metadataException;
    }

    // We may be able to recover from this exception if metadata for this topic is no longer needed
    private KafkaException recoverableException() {
        if (!unauthorizedTopics.isEmpty())
            return new TopicAuthorizationException(unauthorizedTopics);
        else if (!invalidTopics.isEmpty())
            return new InvalidTopicException(invalidTopics);
        else
            return null;
    }

    private KafkaException recoverableExceptionForTopic(String topic) {
        if (unauthorizedTopics.contains(topic))
            return new TopicAuthorizationException(Collections.singleton(topic));
        else if (invalidTopics.contains(topic))
            return new InvalidTopicException(Collections.singleton(topic));
        else
            return null;
    }

    private void clearRecoverableErrors() {
        invalidTopics = Collections.emptySet();
        unauthorizedTopics = Collections.emptySet();
    }

    /**
     * Record an attempt to update the metadata that failed. We need to keep track of this
     * to avoid retrying immediately.
     */
    public synchronized void failedUpdate(long now) {
        this.lastRefreshMs = now;
    }

    /**
     * Propagate a fatal error which affects the ability to fetch metadata for the cluster.
     * Two examples are authentication and unsupported version exceptions.
     *
     * @param exception The fatal exception
     */
    public synchronized void fatalError(KafkaException exception) {
        this.fatalException = exception;
    }

    /**
     * @return The current metadata updateVersion
     */
    public synchronized int updateVersion() {
        return this.updateVersion;
    }

    /**
     * The last time metadata was successfully updated.
     */
    public synchronized long lastSuccessfulUpdate() {
        return this.lastSuccessfulRefreshMs;
    }

    /**
     * Close this metadata instance to indicate that metadata updates are no longer possible.
     */
    @Override
    public synchronized void close() {
        this.isClosed = true;
    }

    /**
     * Check if this metadata instance has been closed. See {@link #close()} for more information.
     *
     * @return True if this instance has been closed; false otherwise
     */
    public synchronized boolean isClosed() {
        return this.isClosed;
    }

    public synchronized void requestUpdateForNewTopics() {
        // Override the timestamp of last refresh to let immediate update.
        this.lastRefreshMs = 0;
        this.requestVersion++;
        requestUpdate();
    }

    public synchronized MetadataRequestAndVersion newMetadataRequestAndVersion() {
        return new MetadataRequestAndVersion(newMetadataRequestBuilder(), requestVersion);
    }

    protected MetadataRequest.Builder newMetadataRequestBuilder() {
        return MetadataRequest.Builder.allTopics();
    }

    protected boolean retainTopic(String topic, boolean isInternal, long nowMs) {
        return true;
    }

    public static class MetadataRequestAndVersion {
        public final MetadataRequest.Builder requestBuilder;
        public final int requestVersion;

        private MetadataRequestAndVersion(MetadataRequest.Builder requestBuilder,
                                          int requestVersion) {
            this.requestBuilder = requestBuilder;
            this.requestVersion = requestVersion;
        }
    }

    public synchronized LeaderAndEpoch leaderAndEpoch(TopicPartition tp) {
        return partitionInfoIfCurrent(tp)
                .map(infoAndEpoch -> {
                    Node leader = infoAndEpoch.partitionInfo().leader();
                    return new LeaderAndEpoch(leader == null ? Node.noNode() : leader, Optional.of(infoAndEpoch.epoch()));
                })
                .orElse(new LeaderAndEpoch(Node.noNode(), lastSeenLeaderEpoch(tp)));
    }

    public static class LeaderAndEpoch {

        public static final LeaderAndEpoch NO_LEADER_OR_EPOCH = new LeaderAndEpoch(Node.noNode(), Optional.empty());

        public final Node leader;
        public final Optional<Integer> epoch;

        public LeaderAndEpoch(Node leader, Optional<Integer> epoch) {
            this.leader = Objects.requireNonNull(leader);
            this.epoch = Objects.requireNonNull(epoch);
        }

        public static LeaderAndEpoch noLeaderOrEpoch() {
            return NO_LEADER_OR_EPOCH;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LeaderAndEpoch that = (LeaderAndEpoch) o;

            if (!leader.equals(that.leader)) return false;
            return epoch.equals(that.epoch);
        }

        @Override
        public int hashCode() {
            int result = leader.hashCode();
            result = 31 * result + epoch.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "LeaderAndEpoch{" +
                    "leader=" + leader +
                    ", epoch=" + epoch.map(Number::toString).orElse("absent") +
                    '}';
        }
    }
}
