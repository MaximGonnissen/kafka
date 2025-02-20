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
package kafka.controller

import java.util.Properties

import kafka.api.{ApiVersion, KAFKA_0_10_0_IV1, KAFKA_0_10_2_IV0, KAFKA_0_9_0, KAFKA_1_0_IV0, KAFKA_2_2_IV0, KAFKA_2_3_IV2, KAFKA_2_4_IV0, KAFKA_2_4_IV1, LeaderAndIsr}
import kafka.cluster.{Broker, EndPoint}
import kafka.server.KafkaConfig
import kafka.utils.TestUtils
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.message.{LeaderAndIsrResponseData, StopReplicaResponseData, UpdateMetadataResponseData}
import org.apache.kafka.common.message.LeaderAndIsrResponseData.LeaderAndIsrPartitionError
import org.apache.kafka.common.message.StopReplicaResponseData.StopReplicaPartitionError
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{AbstractControlRequest, AbstractResponse, LeaderAndIsrRequest, LeaderAndIsrResponse, StopReplicaRequest, StopReplicaResponse, UpdateMetadataRequest, UpdateMetadataResponse}
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.junit.Assert._
import org.junit.Test
import org.scalatest.Assertions

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class ControllerChannelManagerTest {
  private val controllerId = 1
  private val controllerEpoch = 1
  private val config = KafkaConfig.fromProps(TestUtils.createBrokerConfig(controllerId, "zkConnect"))
  private val logger = new StateChangeLogger(controllerId, true, None)

  type ControlRequest = AbstractControlRequest.Builder[_ <: AbstractControlRequest]

  @Test
  def testLeaderAndIsrRequestSent(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Map(
      new TopicPartition("foo", 0) -> LeaderAndIsr(1, List(1, 2)),
      new TopicPartition("foo", 1) -> LeaderAndIsr(2, List(2, 3)),
      new TopicPartition("bar", 1) -> LeaderAndIsr(3, List(1, 3))
    )

    batch.newBatch()
    partitions.foreach { case (partition, leaderAndIsr) =>
      val leaderIsrAndControllerEpoch = LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)
      context.partitionLeadershipInfo.put(partition, leaderIsrAndControllerEpoch)
      batch.addLeaderAndIsrRequestForBrokers(Seq(2), partition, leaderIsrAndControllerEpoch, replicaAssignment(Seq(1, 2, 3)), isNew = false)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    val leaderAndIsrRequests = batch.collectLeaderAndIsrRequestsFor(2)
    val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(2)
    assertEquals(1, leaderAndIsrRequests.size)
    assertEquals(1, updateMetadataRequests.size)

    val leaderAndIsrRequest = leaderAndIsrRequests.head
    assertEquals(controllerId, leaderAndIsrRequest.controllerId)
    assertEquals(controllerEpoch, leaderAndIsrRequest.controllerEpoch)
    assertEquals(partitions.keySet,
      leaderAndIsrRequest.partitionStates.asScala.map(p => new TopicPartition(p.topicName, p.partitionIndex)).toSet)
    assertEquals(partitions.map { case (k, v) => (k, v.leader) },
      leaderAndIsrRequest.partitionStates.asScala.map(p => new TopicPartition(p.topicName, p.partitionIndex) -> p.leader).toMap)
    assertEquals(partitions.map { case (k, v) => (k, v.isr) },
      leaderAndIsrRequest.partitionStates.asScala.map(p => new TopicPartition(p.topicName, p.partitionIndex) -> p.isr.asScala).toMap)

    applyLeaderAndIsrResponseCallbacks(Errors.NONE, batch.sentRequests(2).toList)
    assertEquals(1, batch.sentEvents.size)

    val LeaderAndIsrResponseReceived(leaderAndIsrResponse, brokerId) = batch.sentEvents.head
    assertEquals(2, brokerId)
    assertEquals(partitions.keySet,
      leaderAndIsrResponse.partitions.asScala.map(p => new TopicPartition(p.topicName, p.partitionIndex)).toSet)
  }

  @Test
  def testLeaderAndIsrRequestIsNew(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partition = new TopicPartition("foo", 0)
    val leaderAndIsr = LeaderAndIsr(1, List(1, 2))

    val leaderIsrAndControllerEpoch = LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)
    context.partitionLeadershipInfo.put(partition, leaderIsrAndControllerEpoch)

    batch.newBatch()
    batch.addLeaderAndIsrRequestForBrokers(Seq(2), partition, leaderIsrAndControllerEpoch, replicaAssignment(Seq(1, 2, 3)), isNew = true)
    batch.addLeaderAndIsrRequestForBrokers(Seq(2), partition, leaderIsrAndControllerEpoch, replicaAssignment(Seq(1, 2, 3)), isNew = false)
    batch.sendRequestsToBrokers(controllerEpoch)

    val leaderAndIsrRequests = batch.collectLeaderAndIsrRequestsFor(2)
    val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(2)
    assertEquals(1, leaderAndIsrRequests.size)
    assertEquals(1, updateMetadataRequests.size)

    val leaderAndIsrRequest = leaderAndIsrRequests.head
    val partitionStates = leaderAndIsrRequest.partitionStates.asScala
    assertEquals(Seq(partition), partitionStates.map(p => new TopicPartition(p.topicName, p.partitionIndex)))
    val partitionState = partitionStates.find(p => p.topicName == partition.topic && p.partitionIndex == partition.partition)
    assertEquals(Some(true), partitionState.map(_.isNew))
  }

  @Test
  def testLeaderAndIsrRequestSentToLiveOrShuttingDownBrokers(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    // 2 is shutting down, 3 is dead
    context.shuttingDownBrokerIds += (2 -> 0)
    context.removeLiveBrokers(Set(3))

    val partition = new TopicPartition("foo", 0)
    val leaderAndIsr = LeaderAndIsr(1, List(1, 2))

    val leaderIsrAndControllerEpoch = LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)
    context.partitionLeadershipInfo.put(partition, leaderIsrAndControllerEpoch)

    batch.newBatch()
    batch.addLeaderAndIsrRequestForBrokers(Seq(1, 2, 3), partition, leaderIsrAndControllerEpoch, replicaAssignment(Seq(1, 2, 3)), isNew = false)
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(2, batch.sentRequests.size)
    assertEquals(Set(1, 2), batch.sentRequests.keySet)

    for (brokerId <- Set(1, 2)) {
      val leaderAndIsrRequests = batch.collectLeaderAndIsrRequestsFor(brokerId)
      val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(brokerId)
      assertEquals(1, leaderAndIsrRequests.size)
      assertEquals(1, updateMetadataRequests.size)
      val leaderAndIsrRequest = leaderAndIsrRequests.head
      assertEquals(Seq(partition), leaderAndIsrRequest.partitionStates.asScala.map(p => new TopicPartition(p.topicName, p.partitionIndex)))
    }
  }

  @Test
  def testLeaderAndIsrInterBrokerProtocolVersion(): Unit = {
    testLeaderAndIsrRequestFollowsInterBrokerProtocolVersion(ApiVersion.latestVersion, ApiKeys.LEADER_AND_ISR.latestVersion)

    for (apiVersion <- ApiVersion.allVersions) {
      val leaderAndIsrRequestVersion: Short =
        if (apiVersion >= KAFKA_2_4_IV1) 5
        else if (apiVersion >= KAFKA_2_4_IV0) 4
        else if (apiVersion >= KAFKA_2_3_IV2) 3
        else if (apiVersion >= KAFKA_2_2_IV0) 2
        else if (apiVersion >= KAFKA_1_0_IV0) 1
        else 0

      testLeaderAndIsrRequestFollowsInterBrokerProtocolVersion(apiVersion, leaderAndIsrRequestVersion)
    }
  }

  private def testLeaderAndIsrRequestFollowsInterBrokerProtocolVersion(interBrokerProtocolVersion: ApiVersion,
                                                                       expectedLeaderAndIsrVersion: Short): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val config = createConfig(interBrokerProtocolVersion)
    val batch = new MockControllerBrokerRequestBatch(context, config)

    val partition = new TopicPartition("foo", 0)
    val leaderAndIsr = LeaderAndIsr(1, List(1, 2))

    val leaderIsrAndControllerEpoch = LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)
    context.partitionLeadershipInfo.put(partition, leaderIsrAndControllerEpoch)

    batch.newBatch()
    batch.addLeaderAndIsrRequestForBrokers(Seq(2), partition, leaderIsrAndControllerEpoch, replicaAssignment(Seq(1, 2, 3)), isNew = false)
    batch.sendRequestsToBrokers(controllerEpoch)

    val leaderAndIsrRequests = batch.collectLeaderAndIsrRequestsFor(2)
    assertEquals(1, leaderAndIsrRequests.size)
    assertEquals(s"IBP $interBrokerProtocolVersion should use version $expectedLeaderAndIsrVersion",
      expectedLeaderAndIsrVersion, leaderAndIsrRequests.head.version)
  }

  @Test
  def testUpdateMetadataRequestSent(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Map(
      new TopicPartition("foo", 0) -> LeaderAndIsr(1, List(1, 2)),
      new TopicPartition("foo", 1) -> LeaderAndIsr(2, List(2, 3)),
      new TopicPartition("bar", 1) -> LeaderAndIsr(3, List(1, 3))
    )

    partitions.foreach { case (partition, leaderAndIsr) =>
      context.partitionLeadershipInfo.put(partition, LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch))
    }

    batch.newBatch()
    batch.addUpdateMetadataRequestForBrokers(Seq(2), partitions.keySet)
    batch.sendRequestsToBrokers(controllerEpoch)

    val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(2)
    assertEquals(1, updateMetadataRequests.size)

    val updateMetadataRequest = updateMetadataRequests.head
    val partitionStates = updateMetadataRequest.partitionStates.asScala.toBuffer
    assertEquals(3, partitionStates.size)
    assertEquals(partitions.map { case (k, v) => (k, v.leader) },
      partitionStates.map(ps => (new TopicPartition(ps.topicName, ps.partitionIndex), ps.leader)).toMap)
    assertEquals(partitions.map { case (k, v) => (k, v.isr) },
      partitionStates.map(ps => (new TopicPartition(ps.topicName, ps.partitionIndex), ps.isr.asScala)).toMap)

    assertEquals(controllerId, updateMetadataRequest.controllerId)
    assertEquals(controllerEpoch, updateMetadataRequest.controllerEpoch)
    assertEquals(3, updateMetadataRequest.liveBrokers.size)
    assertEquals(Set(1, 2, 3), updateMetadataRequest.liveBrokers.asScala.map(_.id).toSet)

    applyUpdateMetadataResponseCallbacks(Errors.STALE_BROKER_EPOCH, batch.sentRequests(2).toList)
    assertEquals(1, batch.sentEvents.size)

    val UpdateMetadataResponseReceived(updateMetadataResponse, brokerId) = batch.sentEvents.head
    assertEquals(2, brokerId)
    assertEquals(Errors.STALE_BROKER_EPOCH, updateMetadataResponse.error)
  }

  @Test
  def testUpdateMetadataDoesNotIncludePartitionsWithoutLeaderAndIsr(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    batch.newBatch()
    batch.addUpdateMetadataRequestForBrokers(Seq(2), partitions)
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(2)
    assertEquals(1, updateMetadataRequests.size)

    val updateMetadataRequest = updateMetadataRequests.head
    assertEquals(0, updateMetadataRequest.partitionStates.asScala.size)
    assertEquals(3, updateMetadataRequest.liveBrokers.size)
    assertEquals(Set(1, 2, 3), updateMetadataRequest.liveBrokers.asScala.map(_.id).toSet)
  }

  @Test
  def testUpdateMetadataRequestDuringTopicDeletion(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Map(
      new TopicPartition("foo", 0) -> LeaderAndIsr(1, List(1, 2)),
      new TopicPartition("foo", 1) -> LeaderAndIsr(2, List(2, 3)),
      new TopicPartition("bar", 1) -> LeaderAndIsr(3, List(1, 3))
    )

    partitions.foreach { case (partition, leaderAndIsr) =>
      context.partitionLeadershipInfo.put(partition, LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch))
    }

    context.queueTopicDeletion(Set("foo"))

    batch.newBatch()
    batch.addUpdateMetadataRequestForBrokers(Seq(2), partitions.keySet)
    batch.sendRequestsToBrokers(controllerEpoch)

    val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(2)
    assertEquals(1, updateMetadataRequests.size)

    val updateMetadataRequest = updateMetadataRequests.head
    assertEquals(3, updateMetadataRequest.partitionStates.asScala.size)

    assertTrue(updateMetadataRequest.partitionStates.asScala
      .filter(_.topicName == "foo")
      .map(_.leader)
      .forall(leaderId => leaderId == LeaderAndIsr.LeaderDuringDelete))

    assertEquals(partitions.filter { case (k, _) => k.topic == "bar" }.map { case (k, v) => (k, v.leader) },
      updateMetadataRequest.partitionStates.asScala.filter(ps => ps.topicName == "bar").map { ps =>
        (new TopicPartition(ps.topicName, ps.partitionIndex), ps.leader) }.toMap)
    assertEquals(partitions.map { case (k, v) => (k, v.isr) },
      updateMetadataRequest.partitionStates.asScala.map(ps => (new TopicPartition(ps.topicName, ps.partitionIndex), ps.isr.asScala)).toMap)

    assertEquals(3, updateMetadataRequest.liveBrokers.size)
    assertEquals(Set(1, 2, 3), updateMetadataRequest.liveBrokers.asScala.map(_.id).toSet)
  }

  @Test
  def testUpdateMetadataIncludesLiveOrShuttingDownBrokers(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    // 2 is shutting down, 3 is dead
    context.shuttingDownBrokerIds += (2 -> 0)
    context.removeLiveBrokers(Set(3))

    batch.newBatch()
    batch.addUpdateMetadataRequestForBrokers(Seq(1, 2, 3), Set.empty)
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(Set(1, 2), batch.sentRequests.keySet)

    for (brokerId <- Set(1, 2)) {
      val updateMetadataRequests = batch.collectUpdateMetadataRequestsFor(brokerId)
      assertEquals(1, updateMetadataRequests.size)

      val updateMetadataRequest = updateMetadataRequests.head
      assertEquals(0, updateMetadataRequest.partitionStates.asScala.size)
      assertEquals(2, updateMetadataRequest.liveBrokers.size)
      assertEquals(Set(1, 2), updateMetadataRequest.liveBrokers.asScala.map(_.id).toSet)
    }
  }

  @Test
  def testUpdateMetadataInterBrokerProtocolVersion(): Unit = {
    testUpdateMetadataFollowsInterBrokerProtocolVersion(ApiVersion.latestVersion, ApiKeys.UPDATE_METADATA.latestVersion)

    for (apiVersion <- ApiVersion.allVersions) {
      val updateMetadataRequestVersion: Short =
        if (apiVersion >= KAFKA_2_4_IV1) 7
        else if (apiVersion >= KAFKA_2_3_IV2) 6
        else if (apiVersion >= KAFKA_2_2_IV0) 5
        else if (apiVersion >= KAFKA_1_0_IV0) 4
        else if (apiVersion >= KAFKA_0_10_2_IV0) 3
        else if (apiVersion >= KAFKA_0_10_0_IV1) 2
        else if (apiVersion >= KAFKA_0_9_0) 1
        else 0

      testUpdateMetadataFollowsInterBrokerProtocolVersion(apiVersion, updateMetadataRequestVersion)
    }
  }

  private def testUpdateMetadataFollowsInterBrokerProtocolVersion(interBrokerProtocolVersion: ApiVersion,
                                                          expectedUpdateMetadataVersion: Short): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val config = createConfig(interBrokerProtocolVersion)
    val batch = new MockControllerBrokerRequestBatch(context, config)

    batch.newBatch()
    batch.addUpdateMetadataRequestForBrokers(Seq(2), Set.empty)
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val requests = batch.collectUpdateMetadataRequestsFor(2)
    val allVersions = requests.map(_.version)
    assertTrue(s"IBP $interBrokerProtocolVersion should use version $expectedUpdateMetadataVersion, " +
      s"but found versions $allVersions",
      allVersions.forall(_ == expectedUpdateMetadataVersion))
  }

  @Test
  def testStopReplicaRequestSent(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    batch.newBatch()
    partitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = false)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val sentRequests = batch.sentRequests(2)
    assertEquals(1, sentRequests.size)

    val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(2)
    assertEquals(1, sentStopReplicaRequests.size)

    val stopReplicaRequest = sentStopReplicaRequests.head
    assertFalse(stopReplicaRequest.deletePartitions())
    assertEquals(partitions, stopReplicaRequest.partitions.asScala.toSet)

    applyStopReplicaResponseCallbacks(Errors.NONE, batch.sentRequests(2).toList)
    assertEquals(0, batch.sentEvents.size)
  }

  @Test
  def testStopReplicaRequestsWhileTopicQueuedForDeletion(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    // Topic deletion is queued, but has not begun
    context.queueTopicDeletion(Set("foo"))

    batch.newBatch()
    partitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = true)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val sentRequests = batch.sentRequests(2)
    assertEquals(1, sentRequests.size)

    val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(2)
    assertEquals(1, sentStopReplicaRequests.size)
    assertEquals(partitions, sentStopReplicaRequests.flatMap(_.partitions.asScala).toSet)
    assertTrue(sentStopReplicaRequests.forall(_.deletePartitions))

    // No events will be sent after the response returns
    applyStopReplicaResponseCallbacks(Errors.NONE, batch.sentRequests(2).toList)
    assertEquals(0, batch.sentEvents.size)
  }

  @Test
  def testStopReplicaRequestsWhileTopicDeletionStarted(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    context.queueTopicDeletion(Set("foo"))
    context.beginTopicDeletion(Set("foo"))

    batch.newBatch()
    partitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = true)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val sentRequests = batch.sentRequests(2)
    assertEquals(1, sentRequests.size)

    val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(2)
    assertEquals(1, sentStopReplicaRequests.size)
    assertEquals(partitions, sentStopReplicaRequests.flatMap(_.partitions.asScala).toSet)
    assertTrue(sentStopReplicaRequests.forall(_.deletePartitions()))

    // When the topic is being deleted, we should provide a callback which sends
    // the received event for the StopReplica response
    applyStopReplicaResponseCallbacks(Errors.NONE, batch.sentRequests(2).toList)
    assertEquals(1, batch.sentEvents.size)

    // We should only receive events for the topic being deleted
    val includedPartitions = batch.sentEvents.flatMap {
      case event: TopicDeletionStopReplicaResponseReceived => event.partitionErrors.keySet
      case otherEvent => Assertions.fail(s"Unexpected sent event: $otherEvent")
    }.toSet
    assertEquals(partitions.filter(_.topic == "foo"), includedPartitions)
  }

  @Test
  def testMixedDeleteAndNotDeleteStopReplicaRequests(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val deletePartitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1)
    )

    val nonDeletePartitions = Set(
      new TopicPartition("bar", 0),
      new TopicPartition("bar", 1)
    )

    batch.newBatch()
    deletePartitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = true)
    }
    nonDeletePartitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = false)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val sentRequests = batch.sentRequests(2)
    assertEquals(2, sentRequests.size)

    val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(2)
    assertEquals(2, sentStopReplicaRequests.size)

    val (deleteRequests, nonDeleteRequests) = sentStopReplicaRequests.partition(_.deletePartitions())
    assertEquals(1, deleteRequests.size)
    assertEquals(deletePartitions, deleteRequests.head.partitions.asScala.toSet)
    assertEquals(1, nonDeleteRequests.size)
    assertEquals(nonDeletePartitions, nonDeleteRequests.head.partitions.asScala.toSet)
  }

  @Test
  def testStopReplicaGroupsByBroker(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    batch.newBatch()
    partitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2, 3), partition, deletePartition = false)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(2, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))
    assertTrue(batch.sentRequests.contains(3))

    val sentRequests = batch.sentRequests(2)
    assertEquals(1, sentRequests.size)

    for (brokerId <- Set(2, 3)) {
      val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(brokerId)
      assertEquals(1, sentStopReplicaRequests.size)

      val stopReplicaRequest = sentStopReplicaRequests.head
      assertFalse(stopReplicaRequest.deletePartitions())
      assertEquals(partitions, stopReplicaRequest.partitions.asScala.toSet)

      applyStopReplicaResponseCallbacks(Errors.NONE, batch.sentRequests(2).toList)
      assertEquals(0, batch.sentEvents.size)
    }
  }

  @Test
  def testStopReplicaSentOnlyToLiveAndShuttingDownBrokers(): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo", "bar"), 2, 3)
    val batch = new MockControllerBrokerRequestBatch(context)

    // 2 is shutting down, 3 is dead
    context.shuttingDownBrokerIds += (2 -> 0)
    context.removeLiveBrokers(Set(3))

    val partitions = Set(
      new TopicPartition("foo", 0),
      new TopicPartition("foo", 1),
      new TopicPartition("bar", 1)
    )

    batch.newBatch()
    partitions.foreach { partition =>
      batch.addStopReplicaRequestForBrokers(Seq(2, 3), partition, deletePartition = false)
    }
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val sentRequests = batch.sentRequests(2)
    assertEquals(1, sentRequests.size)

    val sentStopReplicaRequests = batch.collectStopReplicaRequestsFor(2)
    assertEquals(1, sentStopReplicaRequests.size)

    val stopReplicaRequest = sentStopReplicaRequests.head
    assertFalse(stopReplicaRequest.deletePartitions())
    assertEquals(partitions, stopReplicaRequest.partitions.asScala.toSet)
  }

  @Test
  def testStopReplicaInterBrokerProtocolVersion(): Unit = {
    testStopReplicaFollowsInterBrokerProtocolVersion(ApiVersion.latestVersion, ApiKeys.STOP_REPLICA.latestVersion)

    for (apiVersion <- ApiVersion.allVersions) {
      if (apiVersion < KAFKA_2_2_IV0)
        testStopReplicaFollowsInterBrokerProtocolVersion(apiVersion, 0.toShort)
      else if (apiVersion < KAFKA_2_3_IV2)
        testStopReplicaFollowsInterBrokerProtocolVersion(apiVersion, 1.toShort)
      else if (apiVersion < KAFKA_2_4_IV1)
        testStopReplicaFollowsInterBrokerProtocolVersion(apiVersion, 2.toShort)
      else
        testStopReplicaFollowsInterBrokerProtocolVersion(apiVersion, 3.toShort)
    }
  }

  private def testStopReplicaFollowsInterBrokerProtocolVersion(interBrokerProtocolVersion: ApiVersion,
                                                               expectedStopReplicaRequestVersion: Short): Unit = {
    val context = initContext(Seq(1, 2, 3), Set("foo"), 2, 3)
    val config = createConfig(interBrokerProtocolVersion)
    val batch = new MockControllerBrokerRequestBatch(context, config)

    val partition = new TopicPartition("foo", 0)

    batch.newBatch()
    batch.addStopReplicaRequestForBrokers(Seq(2), partition, deletePartition = false)
    batch.sendRequestsToBrokers(controllerEpoch)

    assertEquals(0, batch.sentEvents.size)
    assertEquals(1, batch.sentRequests.size)
    assertTrue(batch.sentRequests.contains(2))

    val requests = batch.collectStopReplicaRequestsFor(2)
    val allVersions = requests.map(_.version)
    assertTrue(s"IBP $interBrokerProtocolVersion should use version $expectedStopReplicaRequestVersion, " +
      s"but found versions $allVersions",
      allVersions.forall(_ == expectedStopReplicaRequestVersion))
  }

  private def applyStopReplicaResponseCallbacks(error: Errors, sentRequests: List[SentRequest]): Unit = {
    sentRequests.filter(_.responseCallback != null).foreach { sentRequest =>
      val stopReplicaRequest = sentRequest.request.build().asInstanceOf[StopReplicaRequest]
      val stopReplicaResponse =
        if (error == Errors.NONE) {
          val partitionErrors = stopReplicaRequest.partitions.asScala.map { tp =>
            new StopReplicaPartitionError()
              .setTopicName(tp.topic)
              .setPartitionIndex(tp.partition)
              .setErrorCode(error.code)
          }.toBuffer.asJava
          new StopReplicaResponse(new StopReplicaResponseData().setPartitionErrors(partitionErrors))
        } else {
          stopReplicaRequest.getErrorResponse(error.exception)
        }
      sentRequest.responseCallback.apply(stopReplicaResponse)
    }
  }

  private def applyLeaderAndIsrResponseCallbacks(error: Errors, sentRequests: List[SentRequest]): Unit = {
    sentRequests.filter(_.request.apiKey == ApiKeys.LEADER_AND_ISR).filter(_.responseCallback != null).foreach { sentRequest =>
      val leaderAndIsrRequest = sentRequest.request.build().asInstanceOf[LeaderAndIsrRequest]
      val partitionErrors = leaderAndIsrRequest.partitionStates.asScala.map(p =>
        new LeaderAndIsrPartitionError()
          .setTopicName(p.topicName)
          .setPartitionIndex(p.partitionIndex)
          .setErrorCode(error.code))
      val leaderAndIsrResponse = new LeaderAndIsrResponse(
        new LeaderAndIsrResponseData()
          .setErrorCode(error.code)
          .setPartitionErrors(partitionErrors.toBuffer.asJava))
      sentRequest.responseCallback(leaderAndIsrResponse)
    }
  }

  private def applyUpdateMetadataResponseCallbacks(error: Errors, sentRequests: List[SentRequest]): Unit = {
    sentRequests.filter(_.request.apiKey == ApiKeys.UPDATE_METADATA).filter(_.responseCallback != null).foreach { sentRequest =>
      val response = new UpdateMetadataResponse(new UpdateMetadataResponseData().setErrorCode(error.code))
      sentRequest.responseCallback(response)
    }
  }

  private def createConfig(interBrokerVersion: ApiVersion): KafkaConfig = {
    val props = new Properties()
    props.put(KafkaConfig.BrokerIdProp, controllerId.toString)
    props.put(KafkaConfig.ZkConnectProp, "zkConnect")
    props.put(KafkaConfig.InterBrokerProtocolVersionProp, interBrokerVersion.version)
    props.put(KafkaConfig.LogMessageFormatVersionProp, interBrokerVersion.version)
    KafkaConfig.fromProps(props)
  }

  private def replicaAssignment(replicas: Seq[Int]): ReplicaAssignment = ReplicaAssignment(replicas, Seq(), Seq())

  private def initContext(brokers: Seq[Int],
                          topics: Set[String],
                          numPartitions: Int,
                          replicationFactor: Int): ControllerContext = {
    val context = new ControllerContext
    val brokerEpochs = brokers.map { brokerId =>
      val endpoint = new EndPoint("localhost", 9900 + brokerId, new ListenerName("PLAINTEXT"),
        SecurityProtocol.PLAINTEXT)
      Broker(brokerId, Seq(endpoint), rack = None) -> 1L
    }.toMap

    context.setLiveBrokerAndEpochs(brokerEpochs)

    // Simple round-robin replica assignment
    var leaderIndex = 0
    for (topic <- topics; partitionId <- 0 until numPartitions) {
      val partition = new TopicPartition(topic, partitionId)
      val replicas = (0 until replicationFactor).map { i =>
        val replica = brokers((i + leaderIndex) % brokers.size)
        replica
      }
      context.updatePartitionReplicaAssignment(partition, replicas)
      leaderIndex += 1
    }
    context
  }

  private case class SentRequest(request: ControlRequest, responseCallback: AbstractResponse => Unit)

  private class MockControllerBrokerRequestBatch(context: ControllerContext, config: KafkaConfig = config)
    extends AbstractControllerBrokerRequestBatch(config, context, logger) {

    val sentEvents = ListBuffer.empty[ControllerEvent]
    val sentRequests = mutable.Map.empty[Int, ListBuffer[SentRequest]]

    override def sendEvent(event: ControllerEvent): Unit = {
      sentEvents.append(event)
    }
    override def sendRequest(brokerId: Int, request: ControlRequest, callback: AbstractResponse => Unit): Unit = {
      sentRequests.getOrElseUpdate(brokerId, ListBuffer.empty)
      sentRequests(brokerId).append(SentRequest(request, callback))
    }

    def collectStopReplicaRequestsFor(brokerId: Int): List[StopReplicaRequest] = {
      sentRequests.get(brokerId) match {
        case Some(requests) => requests
          .filter(_.request.apiKey == ApiKeys.STOP_REPLICA)
          .map(_.request.build().asInstanceOf[StopReplicaRequest]).toList
        case None => List.empty[StopReplicaRequest]
      }
    }

    def collectUpdateMetadataRequestsFor(brokerId: Int): List[UpdateMetadataRequest] = {
      sentRequests.get(brokerId) match {
        case Some(requests) => requests
          .filter(_.request.apiKey == ApiKeys.UPDATE_METADATA)
          .map(_.request.build().asInstanceOf[UpdateMetadataRequest]).toList
        case None => List.empty[UpdateMetadataRequest]
      }
    }

    def collectLeaderAndIsrRequestsFor(brokerId: Int): List[LeaderAndIsrRequest] = {
      sentRequests.get(brokerId) match {
        case Some(requests) => requests
          .filter(_.request.apiKey == ApiKeys.LEADER_AND_ISR)
          .map(_.request.build().asInstanceOf[LeaderAndIsrRequest]).toList
        case None => List.empty[LeaderAndIsrRequest]
      }
    }
  }

}
