/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.net.SocketTimeoutException
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import com.yammer.metrics.core.{Gauge, Timer}
import kafka.api._
import kafka.cluster.Broker
import kafka.metrics.KafkaMetricsGroup
import kafka.server.KafkaConfig
import kafka.utils._
import org.apache.kafka.clients._
import org.apache.kafka.common.message.LeaderAndIsrRequestData.LeaderAndIsrPartitionState
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network._
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.message.UpdateMetadataRequestData.{UpdateMetadataBroker, UpdateMetadataEndpoint, UpdateMetadataPartitionState}
import org.apache.kafka.common.requests._
import org.apache.kafka.common.security.JaasContext
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.utils.{LogContext, Time}
import org.apache.kafka.common.{KafkaException, Node, Reconfigurable, TopicPartition}

import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.collection.{Seq, Map, Set, mutable}

object ControllerChannelManager {
  val QueueSizeMetricName = "QueueSize"
  val RequestRateAndQueueTimeMetricName = "RequestRateAndQueueTimeMs"
}

class ControllerChannelManager(controllerContext: ControllerContext,
                               config: KafkaConfig,
                               time: Time,
                               metrics: Metrics,
                               stateChangeLogger: StateChangeLogger,
                               val clusterId: String,
                               threadNamePrefix: Option[String] = None) extends Logging with KafkaMetricsGroup {
  import ControllerChannelManager._

  protected val brokerStateInfo = new HashMap[Int, ControllerBrokerStateInfo]
  protected val remoteControllerStateInfo = new HashMap[Int, ControllerBrokerStateInfo]
  private val brokerLock = new Object
  this.logIdent = "[Channel manager on controller " + config.brokerId + "]: "
  val brokerResponseSensors: mutable.Map[ApiKeys, BrokerResponseTimeStats] = mutable.HashMap.empty
  newGauge(
    "TotalQueueSize",
    new Gauge[Int] {
      def value: Int = brokerLock synchronized {
        brokerStateInfo.values.iterator.map(_.messageQueue.size).sum +
            remoteControllerStateInfo.values.iterator.map(_.messageQueue.size).sum
      }
    }
  )

  def startup() = {
    controllerContext.liveOrShuttingDownBrokers.foreach(addNewBroker)

    brokerLock synchronized {
      brokerStateInfo.foreach(brokerState => startRequestSendThread(brokerState._1, brokerState._2.requestSendThread))
      info("GRR DEBUG:  about to iterate remoteControllerStateInfo to start RequestSendThreads")
      remoteControllerStateInfo.foreach(remoteState => startRequestSendThread(remoteState._1, remoteState._2.requestSendThread))
    }
    initBrokerResponseSensors()
  }

  def shutdown() = {
    brokerLock synchronized {
      brokerStateInfo.values.toList.foreach(removeExistingBroker)
      remoteControllerStateInfo.values.toList.foreach(removeExistingBroker)
    }
    removeBrokerResponseSensors()
  }

  def initBrokerResponseSensors(): Unit = {
    Array(ApiKeys.STOP_REPLICA, ApiKeys.LEADER_AND_ISR, ApiKeys.UPDATE_METADATA, ApiKeys.LI_COMBINED_CONTROL).foreach { k: ApiKeys =>
      brokerResponseSensors.put(k, new BrokerResponseTimeStats(k))
    }
  }

  def removeBrokerResponseSensors(): Unit = {
    brokerResponseSensors.keySet.foreach { k: ApiKeys =>
      brokerResponseSensors(k).removeMetrics()
      brokerResponseSensors.remove(k)
    }
  }

  def sendRequest(brokerId: Int, request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit = {
    // GRR FIXME: should we instrument the time spent waiting for this lock (and its other 5 call sites)?
    //   (would love to see histogram in leader-controller)
    brokerLock synchronized {
      var stateInfoOpt = brokerStateInfo.get(brokerId)
      stateInfoOpt match {
        case Some(stateInfo) =>
          stateInfo.messageQueue.put(QueueItem(request.apiKey, request, callback, time.milliseconds()))
        case None =>
          stateInfoOpt = remoteControllerStateInfo.get(brokerId)
          stateInfoOpt match {
            case Some(stateInfo) =>
              stateInfo.messageQueue.put(QueueItem(request.apiKey, request, callback, time.milliseconds()))
            case None =>
              warn(s"Not sending request $request to broker $brokerId, since it is offline.")
          }
      }
    }
  }

  // [non-federation only]
  def addBroker(broker: Broker): Unit = {
    // be careful here. Maybe the startup() API has already started the request send thread
    brokerLock synchronized {
      if (!brokerStateInfo.contains(broker.id)) {
        addNewBroker(broker, true)
        startRequestSendThread(broker.id, brokerStateInfo(broker.id).requestSendThread)
      }
    }
  }

  // [non-federation only]
  def removeBroker(brokerId: Int): Unit = {
    brokerLock synchronized {
      removeExistingBroker(brokerStateInfo(brokerId))
    }
  }

  /**
   * [Federation only] Get the Node struct (basic connection details) for the specified <em>local</em> broker ID.
   * This is sent to a <em>remote</em> controller so it can, in turn, send its cluster updates to us.
   */
  // [might be a test-only method; will depend on how "real" configuration/discovery/recovery works out]
//GRR FIXME:  make package-private
  def getBrokerNode(brokerId: Int): Option[Node] = {
    brokerLock synchronized {
      val stateInfoOpt = brokerStateInfo.get(brokerId)
      stateInfoOpt match {
        case Some(stateInfo) =>
          val node = stateInfo.brokerNode
          info(s"GRR DEBUG:  controller ${config.brokerId}'s Node info for brokerId=${brokerId} = ${node}")
          Some(node)
        case None =>
          info(s"GRR DEBUG:  ControllerBrokerStateInfo on controllerId=${config.brokerId} for brokerId=${brokerId} DOES NOT EXIST ('offline'?)")
          None
      }
    }
  }

  /**
   * [Federation only] Add the specified broker as a remote controller, i.e., a target for local
   * metadata updates but not for rewritten remote ones.  Loosely speaking, this is the other side
   * of getBrokerNode(), i.e., this is what the other side does when it receives the getBrokerNode()
   * info from another controller.
   */
  // [might be a test-only method; will depend on how "real" configuration/discovery/recovery works out]
//GRR FIXME:  make package-private
  def addRemoteController(remoteBroker: Broker): Unit = {
    info(s"GRR DEBUG:  controllerId=${config.brokerId} adding remote controller [${remoteBroker}] for FEDERATION INTER-CLUSTER REQUESTS and starting its RequestSendThread")
    brokerLock synchronized {
      if (!remoteControllerStateInfo.contains(remoteBroker.id)) {
        addNewBroker(remoteBroker, false)
        startRequestSendThread(remoteBroker.id, remoteControllerStateInfo(remoteBroker.id).requestSendThread)
      }
    }
  }

  // called under brokerLock except at startup()
  private def addNewBroker(broker: Broker): Unit = {
    addNewBroker(broker, true)
  }

  // called under brokerLock except at startup()
  private def addNewBroker(broker: Broker, isLocal: Boolean): Unit = {
    val messageQueue = new LinkedBlockingQueue[QueueItem]
    debug(s"Controller ${config.brokerId} trying to connect to broker ${broker.id}")
    val controllerToBrokerListenerName = config.controlPlaneListenerName.getOrElse(config.interBrokerListenerName)
    val controllerToBrokerSecurityProtocol = config.controlPlaneSecurityProtocol.getOrElse(config.interBrokerSecurityProtocol)
    val brokerNode = broker.node(controllerToBrokerListenerName)
    val idType = if (isLocal) "targetBrokerId" else "remoteControllerId"
    val logContext = new LogContext(s"[Controller id=${config.brokerId}, ${idType}=${brokerNode.idString}] ")
    val (networkClient, reconfigurableChannelBuilder) = {
      val channelBuilder = ChannelBuilders.clientChannelBuilder(
        controllerToBrokerSecurityProtocol,
        JaasContext.Type.SERVER,
        config,
        controllerToBrokerListenerName,
        config.saslMechanismInterBrokerProtocol,
        time,
        config.saslInterBrokerHandshakeRequestEnable
      )
      val reconfigurableChannelBuilder = channelBuilder match {
        case reconfigurable: Reconfigurable =>
          config.addReconfigurable(reconfigurable)
          Some(reconfigurable)
        case _ => None
      }
      val selector = new Selector(
        NetworkReceive.UNLIMITED,
        Selector.NO_IDLE_TIMEOUT_MS,
        metrics,
        time,
        "controller-channel",
        Map("broker-id" -> brokerNode.idString).asJava,
        false,
        channelBuilder,
        logContext
      )
      val networkClient = new NetworkClient(
        selector,
        new ManualMetadataUpdater(Seq(brokerNode).asJava),
        config.brokerId.toString,
        1,
        0,
        0,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        config.requestTimeoutMs,
        ClientDnsLookup.DEFAULT,
        time,
        false,
        new ApiVersions,
        logContext
      )
      (networkClient, reconfigurableChannelBuilder)
    }
    val threadName = threadNamePrefix match {
      case None => s"Controller-${config.brokerId}-to-broker-${broker.id}-send-thread"
      case Some(name) => s"$name:Controller-${config.brokerId}-to-broker-${broker.id}-send-thread"
    }

    val requestRateAndQueueTimeMetrics = newTimer(
      RequestRateAndQueueTimeMetricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS, brokerMetricTags(broker.id)
    )

    val requestThread = new RequestSendThread(config.brokerId, controllerContext, messageQueue, networkClient,
      brokerNode, config, time, requestRateAndQueueTimeMetrics, stateChangeLogger, threadName, this)
    requestThread.setDaemon(false)

    val queueSizeGauge = newGauge(
      QueueSizeMetricName,
      new Gauge[Int] {
        def value: Int = messageQueue.size
      },
      brokerMetricTags(broker.id)
    )

    //GRR FIXME:  do sanity check whether same ID exists within sibling map (brokerStateInfo/remoteControllerStateInfo)
    if (isLocal) {
      brokerStateInfo.put(broker.id, ControllerBrokerStateInfo(networkClient, brokerNode, messageQueue,
        requestThread, queueSizeGauge, requestRateAndQueueTimeMetrics, reconfigurableChannelBuilder))
    } else {
      info(s"GRR DEBUG:  adding ${brokerNode} info (network client, message queue, request thread, etc.) to new remoteControllerStateInfo map for federation inter-cluster requests")
      remoteControllerStateInfo.put(broker.id, ControllerBrokerStateInfo(networkClient, brokerNode, messageQueue,
        requestThread, queueSizeGauge, requestRateAndQueueTimeMetrics, reconfigurableChannelBuilder))
    }
  }

  private def brokerMetricTags(brokerId: Int) = Map("broker-id" -> brokerId.toString)

  private def removeExistingBroker(brokerState: ControllerBrokerStateInfo): Unit = {
    try {
      // Shutdown the RequestSendThread before closing the NetworkClient to avoid the concurrent use of the
      // non-threadsafe classes as described in KAFKA-4959.
      // The call to shutdownLatch.await() in ShutdownableThread.shutdown() serves as a synchronization barrier that
      // hands off the NetworkClient from the RequestSendThread to the ZkEventThread.
      brokerState.reconfigurableChannelBuilder.foreach(config.removeReconfigurable)
      brokerState.requestSendThread.shutdown()
      brokerState.networkClient.close()
      brokerState.messageQueue.clear()
      removeMetric(QueueSizeMetricName, brokerMetricTags(brokerState.brokerNode.id))
      removeMetric(RequestRateAndQueueTimeMetricName, brokerMetricTags(brokerState.brokerNode.id))
      brokerStateInfo.remove(brokerState.brokerNode.id)
//GRR FIXME?
      remoteControllerStateInfo.remove(brokerState.brokerNode.id)  // make conditional on "None" return from prev line?
    } catch {
      case e: Throwable => error("Error while removing broker by the controller", e)
    }
  }

  protected def startRequestSendThread(brokerId: Int, requestThread: RequestSendThread): Unit = {
    if (requestThread.getState == Thread.State.NEW) {
      info(s"GRR DEBUG:  controllerId=${config.brokerId} starting RequestSendThread for brokerId=${brokerId}")
      requestThread.start()
    }
  }
}

case class QueueItem(apiKey: ApiKeys, request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                     callback: AbstractResponse => Unit, enqueueTimeMs: Long)

case class LatestRequestStatus(isInFlight: Boolean, isInQueue: Boolean, enqueueTimeMs: Long)

class RequestSendThread(val controllerId: Int,
                        val controllerContext: ControllerContext,
                        val queue: BlockingQueue[QueueItem],
                        val networkClient: NetworkClient,
                        val brokerNode: Node,
                        val config: KafkaConfig,
                        val time: Time,
                        val requestRateAndQueueTimeMetrics: Timer,
                        val stateChangeLogger: StateChangeLogger,
                        name: String,
                        val controllerChannelManager: ControllerChannelManager)
extends ShutdownableThread(name = name) with KafkaMetricsGroup {

  logIdent = s"[RequestSendThread controllerId=$controllerId -> brokerId=${brokerNode.id}] "

  private val MaxRequestAgeMetricName = "maxRequestAge"

  private val socketTimeoutMs = config.controllerSocketTimeoutMs

  private val controllerRequestMerger = new ControllerRequestMerger

  private var firstUpdateMetadataWithPartitionsSent = false

  @volatile private var latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = false, 0)

  // This metric reports the queued time of the latest request from the queue
  // Case 1: if there is an inflight request, the metric need to report the age of the inflight request.
  // Case 2: if there is no inflight request and there are requests inside the queue, the metric need to report
  //         the age of the oldest item in the queue, which is the one that should be dequeued next.
  // Case 3: if there is no inflight request and there are no requests inside the queue, the metric should report 0.
  val queueTimeGauge = newGauge(
    MaxRequestAgeMetricName,
    new Gauge[Long] {
      def value: Long =
        if (latestRequestStatus.isInFlight || latestRequestStatus.isInQueue) time.milliseconds() - latestRequestStatus.enqueueTimeMs
        else 0
    },
    brokerMetricTags(brokerNode.id())
  )

  private def brokerMetricTags(brokerId: Int) = Map("broker-id" -> brokerId.toString)

  def backoff(): Unit = pause(100, TimeUnit.MILLISECONDS)

  override def doWork(): Unit = {
    val (requestBuilder, callback) = nextRequestAndCallback()
    sendAndReceive(requestBuilder, callback)
  }

  private def nextRequestAndCallback(): (AbstractControlRequest.Builder[_ <: AbstractControlRequest], AbstractResponse => Unit) = {
    if (controllerRequestMerger.hasPendingRequests() ||
      (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1 &&
        config.liCombinedControlRequestEnable &&
        firstUpdateMetadataWithPartitionsSent)) {
      // Only start the merging logic after the first UpdateMetadata request with partitions,
      // since the first UpdateMetadata request with partitions may contain hundreds of thousands of partitions,
      // and thus needs to be cached and shared by all brokers in order to prevent OOM

      // there are 4 cases regarding the state of the queue and the controllerRequestMerger
      // case 1: queue not empty, merger not empty {action: merge and send first merged request}
      // case 2: queue not empty, merger empty {action: merge and send first merged request}
      // case 3: queue empty, merger not empty {action: send latest merged request}
      // case 4: queue empty, merger empty {action: block and wait until queue becomes non-empty, then transition to case 1 or 3}

      // handle case 4 first
      if (!controllerRequestMerger.hasPendingRequests()) {
        val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
        latestRequestStatus = LatestRequestStatus(isInFlight = true, isInQueue = false, enqueueTimeMs)
        mergeControlRequest(enqueueTimeMs, apiKey, requestBuilder, callback)
      }

      // now we are guaranteed that the controllerRequestMerger is not empty (case 1 or 3)
      // drain the queue until the queue is empty
      // one concurrent access case considering the producer of the queue:
      // an item is put to the queue right after the condition check below.
      // That behavior does not change correctness since the inserted item will be picked up in the next round
      while (!queue.isEmpty) {
        val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
        mergeControlRequest(enqueueTimeMs, apiKey, requestBuilder, callback)
      }

      val requestBuilder = controllerRequestMerger.pollLatestRequest()
      (requestBuilder, controllerRequestMerger.triggerCallback _)
    } else {
      // use the old behavior of sending each item in the queue as a separate request
      val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
      latestRequestStatus = LatestRequestStatus(isInFlight = true, isInQueue = false, enqueueTimeMs)
      updateMetrics(apiKey, enqueueTimeMs)
      (requestBuilder, callback)
    }
  }

  private def sendAndReceive(requestBuilder: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
    callback: AbstractResponse => Unit): Unit = {
    var remoteTimeMs: Long = 0

    var clientResponse: ClientResponse = null
    try {
      var isSendSuccessful = false
      while (isRunning && !isSendSuccessful) {
        // if a broker goes down for a long time, then at some point the controller's zookeeper listener will trigger a
        // removeBroker which will invoke shutdown() on this thread. At that point, we will stop retrying.
        try {
          if (!brokerReady()) {
            isSendSuccessful = false
            backoff()
          }
          else {
            val clientRequest = networkClient.newClientRequest(brokerNode.idString, requestBuilder,
              time.milliseconds(), true)
            val remoteTimeStartMs = time.milliseconds()
            stateChangeLogger.withControllerEpoch(controllerContext.epoch).trace(s"sending request to broker $brokerNode: $requestBuilder")

            clientResponse = NetworkClientUtils.sendAndReceive(networkClient, clientRequest, time)
            isSendSuccessful = true
            remoteTimeMs = time.milliseconds() - remoteTimeStartMs

            val nextRequest = queue.peek()
            if (nextRequest != null) {
              latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = true, nextRequest.enqueueTimeMs)
            } else {
              latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = false, 0)
            }
          }
        } catch {
          case e: Throwable => // if the send was not successful, reconnect to broker and resend the message
            warn(s"Controller $controllerId epoch ${controllerContext.epoch} fails to send request $requestBuilder " +
              s"to broker $brokerNode. Reconnecting to broker.", e)
            networkClient.close(brokerNode.idString)
            isSendSuccessful = false
            backoff()
        }
      }
      if (clientResponse != null) {
        val requestHeader = clientResponse.requestHeader
        val api = requestHeader.apiKey
        if (api != ApiKeys.LEADER_AND_ISR && api != ApiKeys.STOP_REPLICA && api != ApiKeys.UPDATE_METADATA &&
          api != ApiKeys.LI_COMBINED_CONTROL)
          throw new KafkaException(s"Unexpected apiKey received: $api")


        if (api == ApiKeys.UPDATE_METADATA && !requestBuilder.asInstanceOf[UpdateMetadataRequest.Builder].partitionStates().isEmpty) {
          firstUpdateMetadataWithPartitionsSent = true
        }

        val response = clientResponse.responseBody

        stateChangeLogger.withControllerEpoch(controllerContext.epoch).trace(s"Received response " +
          s"${response.toString(requestHeader.apiVersion)} for request $api with correlation id " +
          s"${requestHeader.correlationId} sent to broker $brokerNode")

        if (callback != null) {
          callback(response)
        }
        controllerChannelManager.brokerResponseSensors(api).updateRemoteTime(remoteTimeMs)
      }
    } catch {
      case e: Throwable =>
        error(s"Controller $controllerId fails to send a request to broker $brokerNode", e)
        // If there is any socket error (eg, socket timeout), the connection is no longer usable and needs to be recreated.
        networkClient.close(brokerNode.idString)
    }
  }

  /**
   * merge a control request
   * @param enqueueTimeMs
   * @param apiKey
   * @param requestBuilder
   * @param callback
   */
  def mergeControlRequest(enqueueTimeMs: Long, apiKey: ApiKeys, requestBuilder: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
    callback: AbstractResponse => Unit): Unit = {
    updateMetrics(apiKey, enqueueTimeMs)
    controllerRequestMerger.addRequest(requestBuilder, callback)
  }

  private def updateMetrics(apiKey: ApiKeys, enqueueTimeMs: Long) = {
    val queueTimeMs = time.milliseconds() - enqueueTimeMs
    requestRateAndQueueTimeMetrics.update(queueTimeMs, TimeUnit.MILLISECONDS)
    controllerChannelManager.brokerResponseSensors(apiKey).updateQueueTime(queueTimeMs)
  }

  private def brokerReady(): Boolean = {
    try {
      if (!NetworkClientUtils.isReady(networkClient, brokerNode, time.milliseconds())) {
        if (!NetworkClientUtils.awaitReady(networkClient, brokerNode, time, socketTimeoutMs))
          throw new SocketTimeoutException(s"Failed to connect within $socketTimeoutMs ms")

        info(s"Controller $controllerId connected to $brokerNode for sending state change requests")
      }

      true
    } catch {
      case e: Throwable =>
        warn(s"Controller $controllerId's connection to broker $brokerNode was unsuccessful", e)
        networkClient.close(brokerNode.idString)
        false
    }
  }

  override def initiateShutdown(): Boolean = {
    if (super.initiateShutdown()) {
      networkClient.initiateClose()
      removeMetric(MaxRequestAgeMetricName, brokerMetricTags(brokerNode.id()))
      true
    } else
      false
  }
}

class ControllerBrokerRequestBatch(config: KafkaConfig,
                                   clusterId: String,
                                   controllerChannelManager: ControllerChannelManager,
                                   controllerEventManager: ControllerEventManager,
                                   controllerContext: ControllerContext,
                                   stateChangeLogger: StateChangeLogger)
  extends AbstractControllerBrokerRequestBatch(config, clusterId, controllerContext, stateChangeLogger) {

  def sendEvent(event: ControllerEvent): Unit = {
    controllerEventManager.put(event)
  }

  def sendRequest(brokerId: Int,
                  request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit = {
    controllerChannelManager.sendRequest(brokerId, request, callback)
  }

}

case class StopReplicaRequestInfo(replica: PartitionAndReplica, deletePartition: Boolean)

abstract class AbstractControllerBrokerRequestBatch(config: KafkaConfig,
                                                    val clusterId: String,
                                                    controllerContext: ControllerContext,
                                                    stateChangeLogger: StateChangeLogger) extends  Logging {
  val controllerId: Int = config.brokerId
  val leaderAndIsrRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, LeaderAndIsrPartitionState]]
  val stopReplicaRequestMap = mutable.Map.empty[Int, ListBuffer[StopReplicaRequestInfo]]
  val updateMetadataRequestBrokerSet = mutable.Set.empty[Int]
  val updateMetadataRequestPartitionInfoMap = mutable.Map.empty[TopicPartition, UpdateMetadataPartitionState]

  def sendEvent(event: ControllerEvent): Unit

  def sendRequest(brokerId: Int,
                  request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit

  def newBatch(): Unit = {
    // raise error if the previous batch is not empty
    if (leaderAndIsrRequestMap.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating " +
        s"a new one. Some LeaderAndIsr state changes $leaderAndIsrRequestMap might be lost ")
    if (stopReplicaRequestMap.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        s"new one. Some StopReplica state changes $stopReplicaRequestMap might be lost ")
    if (updateMetadataRequestBrokerSet.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        s"new one. Some UpdateMetadata state changes to brokers $updateMetadataRequestBrokerSet with partition info " +
        s"$updateMetadataRequestPartitionInfoMap might be lost ")
  }

  def clear(): Unit = {
    leaderAndIsrRequestMap.clear()
    stopReplicaRequestMap.clear()
    updateMetadataRequestBrokerSet.clear()
    updateMetadataRequestPartitionInfoMap.clear()
  }

  def addLeaderAndIsrRequestForBrokers(brokerIds: Seq[Int],
                                       topicPartition: TopicPartition,
                                       leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch,
                                       replicaAssignment: ReplicaAssignment,
                                       isNew: Boolean): Unit = {

    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val result = leaderAndIsrRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty)
      val alreadyNew = result.get(topicPartition).exists(_.isNew)
      val leaderAndIsr = leaderIsrAndControllerEpoch.leaderAndIsr
      result.put(topicPartition, new LeaderAndIsrPartitionState()
        .setTopicName(topicPartition.topic)
        .setPartitionIndex(topicPartition.partition)
        .setControllerEpoch(leaderIsrAndControllerEpoch.controllerEpoch)
        .setLeader(leaderAndIsr.leader)
        .setLeaderEpoch(leaderAndIsr.leaderEpoch)
        .setIsr(leaderAndIsr.isr.map(Integer.valueOf).asJava)
        .setZkVersion(leaderAndIsr.zkVersion)
        .setReplicas(replicaAssignment.replicas.map(Integer.valueOf).asJava)
        .setAddingReplicas(replicaAssignment.addingReplicas.map(Integer.valueOf).asJava)
        .setRemovingReplicas(replicaAssignment.removingReplicas.map(Integer.valueOf).asJava)
        .setIsNew(isNew || alreadyNew))
    }

    addUpdateMetadataRequestForBrokers(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set(topicPartition))
  }

  def addStopReplicaRequestForBrokers(brokerIds: Seq[Int],
                                      topicPartition: TopicPartition,
                                      deletePartition: Boolean): Unit = {
    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val stopReplicaInfos = stopReplicaRequestMap.getOrElseUpdate(brokerId, ListBuffer.empty[StopReplicaRequestInfo])
      stopReplicaInfos.append(StopReplicaRequestInfo(PartitionAndReplica(topicPartition, brokerId), deletePartition))
    }
  }

  /** Send UpdateMetadataRequest to the given brokers for the given partitions and partitions that are being deleted */
  def addUpdateMetadataRequestForBrokers(brokerIds: Seq[Int],
                                         partitions: collection.Set[TopicPartition]): Unit = {
    val controllerContextSnapshot = ControllerContextSnapshot(controllerContext)
    def updateMetadataRequestPartitionInfo(partition: TopicPartition, beingDeleted: Boolean): Unit = {
      controllerContext.partitionLeadershipInfo.get(partition) match {
        case Some(LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)) =>
          val replicas = controllerContext.partitionReplicaAssignment(partition)
          val offlineReplicas = replicas.filter(!controllerContextSnapshot.isReplicaOnline(_, partition))
          val updatedLeaderAndIsr =
            if (beingDeleted) LeaderAndIsr.duringDelete(leaderAndIsr.isr)
            else leaderAndIsr

          val partitionStateInfo = new UpdateMetadataPartitionState()
            .setTopicName(partition.topic)
            .setPartitionIndex(partition.partition)
            .setControllerEpoch(controllerEpoch)
            .setLeader(updatedLeaderAndIsr.leader)
            .setLeaderEpoch(updatedLeaderAndIsr.leaderEpoch)
            .setIsr(updatedLeaderAndIsr.isr.map(Integer.valueOf).asJava)
            .setZkVersion(updatedLeaderAndIsr.zkVersion)
            .setReplicas(replicas.map(Integer.valueOf).asJava)
            .setOfflineReplicas(offlineReplicas.map(Integer.valueOf).asJava)
          updateMetadataRequestPartitionInfoMap.put(partition, partitionStateInfo)

        case None =>
          info(s"Leader not yet assigned for partition $partition. Skip sending UpdateMetadataRequest.")
      }
    }

    updateMetadataRequestBrokerSet ++= brokerIds.filter(_ >= 0)
    partitions.foreach(partition => updateMetadataRequestPartitionInfo(partition,
      beingDeleted = controllerContext.topicsToBeDeleted.contains(partition.topic)))
  }

  private def sendLeaderAndIsrRequest(controllerEpoch: Int, stateChangeLog: StateChangeLogger): Unit = {
    val leaderAndIsrRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 5
      else if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV0) 4
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_1_0_IV0) 1
      else 0

    val maxBrokerEpoch = controllerContext.maxBrokerEpoch
    leaderAndIsrRequestMap.filterKeys(controllerContext.liveOrShuttingDownBrokerIds.contains).foreach {
      case (broker, leaderAndIsrPartitionStates) =>
        if (stateChangeLog.isTraceEnabled) {
          leaderAndIsrPartitionStates.foreach { case (topicPartition, state) =>
            val typeOfRequest =
              if (broker == state.leader) "become-leader"
              else "become-follower"
            stateChangeLog.trace(s"Sending $typeOfRequest LeaderAndIsr request $state to broker $broker for partition $topicPartition")
          }
        }
        val leaderIds = leaderAndIsrPartitionStates.map(_._2.leader).toSet
        val leaders = controllerContext.liveOrShuttingDownBrokers.filter(b => leaderIds.contains(b.id)).map {
          _.node(config.interBrokerListenerName)
        }
        val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(broker)

        val leaderAndIsrRequestBuilder = new LeaderAndIsrRequest.Builder(leaderAndIsrRequestVersion, controllerId,
          controllerEpoch, brokerEpoch, maxBrokerEpoch, leaderAndIsrPartitionStates.values.toBuffer.asJava, leaders.asJava)
        sendRequest(broker, leaderAndIsrRequestBuilder, (r: AbstractResponse) => {
          val leaderAndIsrResponse = r.asInstanceOf[LeaderAndIsrResponse]
          sendEvent(LeaderAndIsrResponseReceived(leaderAndIsrResponse, broker))
        })
    }
    leaderAndIsrRequestMap.clear()
  }

  private def sendUpdateMetadataRequests(controllerEpoch: Int, stateChangeLog: StateChangeLogger): Unit = {
    updateMetadataRequestPartitionInfoMap.foreach { case (tp, partitionState) =>
      stateChangeLog.trace(s"Sending UpdateMetadata request $partitionState to brokers $updateMetadataRequestBrokerSet " +
        s"for partition $tp")
    }

    val partitionStates = updateMetadataRequestPartitionInfoMap.values.toBuffer
    val updateMetadataRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 7
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 6
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 5
      else if (config.interBrokerProtocolVersion >= KAFKA_1_0_IV0) 4
      else if (config.interBrokerProtocolVersion >= KAFKA_0_10_2_IV0) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_0_10_0_IV1) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_0_9_0) 1
      else 0

    val liveBrokers = controllerContext.liveOrShuttingDownBrokers.iterator.map { broker =>
      val endpoints = if (updateMetadataRequestVersion == 0) {
        // Version 0 of UpdateMetadataRequest only supports PLAINTEXT
        val securityProtocol = SecurityProtocol.PLAINTEXT
        val listenerName = ListenerName.forSecurityProtocol(securityProtocol)
        val node = broker.node(listenerName)
        Seq(new UpdateMetadataEndpoint()
          .setHost(node.host)
          .setPort(node.port)
          .setSecurityProtocol(securityProtocol.id)
          .setListener(listenerName.value))
      } else {
        broker.endPoints.map { endpoint =>
          new UpdateMetadataEndpoint()
            .setHost(endpoint.host)
            .setPort(endpoint.port)
            .setSecurityProtocol(endpoint.securityProtocol.id)
            .setListener(endpoint.listenerName.value)
        }
      }
      new UpdateMetadataBroker()
        .setId(broker.id)
        .setEndpoints(endpoints.asJava)
        .setRack(broker.rack.orNull)
    }.toBuffer

    if (updateMetadataRequestVersion >= 6) {
      // NOTE:  new flexible versions thing is for 7+ (which we don't check here), but UpdateMetadataRequest.Builder
      //   does check for it before attempting to call data.setClusterId(clusterId)
      val conditionalClusterId: String = if (config.liFederationEnable) clusterId else null
      // We should create only one copy of UpdateMetadataRequest[.Builder] that should apply to all brokers.
      // The goal is to reduce memory footprint on the controller.
      val maxBrokerEpoch = controllerContext.maxBrokerEpoch
      val updateMetadataRequest = new UpdateMetadataRequest.Builder(updateMetadataRequestVersion, controllerId, controllerEpoch,
        AbstractControlRequest.UNKNOWN_BROKER_EPOCH, maxBrokerEpoch, partitionStates.asJava, liveBrokers.asJava,
        conditionalClusterId)

      updateMetadataRequestBrokerSet.intersect(controllerContext.liveOrShuttingDownBrokerIds).foreach { broker =>
        sendRequest(broker, updateMetadataRequest, (r: AbstractResponse) => {
          val updateMetadataResponse = r.asInstanceOf[UpdateMetadataResponse]
          sendEvent(UpdateMetadataResponseReceived(updateMetadataResponse, broker))
        })
      }

      // if we're part of a multi-cluster federation, we need to send our (local) updates to controllers in the
      // other physical clusters
      if (config.liFederationEnable) {
        // [note confusing variable names:  "broker" = brokerId, "updateMetadataRequest" = updateMetadataRequestBuilder]
        // FIXME:  need to keep list of remote (active) controllers up to date
        //   - implies some kind of configuration pointing at the remote ZKs (or all ZKs, from which we subtract
        //     our own)
        //   - implies some kind of ZK-watcher setup + callback to maintain the list in realtime (potentially like
        //     updateMetadataRequestBrokerSet above, which filters out IDs < 0, but could also tweak state info
        //     to include "isRemoteController" and "isActive" states and filter on latter)
        // FIXME:  the sendRequest() calls to remote controllers below need some kind of reasonable timeout/retry setup
        //   (since we probably don't know about shutting-down states, etc., of remote controllers...or would our
        //   ZK-watcher get that for free?):  what's reasonable here?  and if we exhaust retries (or avoid retrying),
        //   do we have some kind of "deferred update" list like elsewhere in the code, or ...?
        //   (all of this could be wrapped up in a method call to elsewhere, but not clear where would be best)
        controllerContext.getLiveOrShuttingDownRemoteControllerIds.foreach { remoteControllerId =>
          info(s"GRR DEBUG:  local controllerId=${config.brokerId} sending updateMetadataRequest to remote controllerId=${remoteControllerId}")
          sendRequest(remoteControllerId, updateMetadataRequest, (r: AbstractResponse) => {
          val updateMetadataResponse = r.asInstanceOf[UpdateMetadataResponse]
          sendEvent(UpdateMetadataResponseReceived(updateMetadataResponse, remoteControllerId))
        })
        }
      }

    } else {
      updateMetadataRequestBrokerSet.intersect(controllerContext.liveOrShuttingDownBrokerIds).foreach { broker =>
        val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(broker)
        val updateMetadataRequest = new UpdateMetadataRequest.Builder(updateMetadataRequestVersion, controllerId, controllerEpoch,
          brokerEpoch, AbstractControlRequest.UNKNOWN_BROKER_EPOCH, partitionStates.asJava, liveBrokers.asJava,
          clusterId)
        sendRequest(broker, updateMetadataRequest, (r: AbstractResponse) => {
          val updateMetadataResponse = r.asInstanceOf[UpdateMetadataResponse]
          sendEvent(UpdateMetadataResponseReceived(updateMetadataResponse, broker))
        })
      }
    }

    updateMetadataRequestBrokerSet.clear()
    updateMetadataRequestPartitionInfoMap.clear()
  }

  private def sendStopReplicaRequests(controllerEpoch: Int): Unit = {
    val stopReplicaRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 1
      else 0

    def stopReplicaPartitionDeleteResponseCallback(brokerId: Int)(response: AbstractResponse): Unit = {
      val stopReplicaResponse = response.asInstanceOf[StopReplicaResponse]
      val partitionErrorsForDeletingTopics = stopReplicaResponse.partitionErrors.asScala.iterator.filter { pe =>
        controllerContext.isTopicDeletionInProgress(pe.topicName)
      }.map(pe => new TopicPartition(pe.topicName, pe.partitionIndex) -> Errors.forCode(pe.errorCode)).toMap

      if (partitionErrorsForDeletingTopics.nonEmpty)
        sendEvent(TopicDeletionStopReplicaResponseReceived(brokerId, stopReplicaResponse.error, partitionErrorsForDeletingTopics))
    }

    def createStopReplicaRequest(brokerEpoch: Long, maxBrokerEpoch: Long, requests: Seq[StopReplicaRequestInfo], deletePartitions: Boolean): StopReplicaRequest.Builder = {
      val partitions = requests.map(_.replica.topicPartition).asJava
      new StopReplicaRequest.Builder(stopReplicaRequestVersion, controllerId, controllerEpoch,
        brokerEpoch, maxBrokerEpoch, deletePartitions, partitions)
    }

    val maxBrokerEpoch = controllerContext.maxBrokerEpoch
    stopReplicaRequestMap.filterKeys(controllerContext.liveOrShuttingDownBrokerIds.contains).foreach { case (brokerId, replicaInfoList) =>
      val (stopReplicaWithDelete, stopReplicaWithoutDelete) = replicaInfoList.partition(r => r.deletePartition)
      val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(brokerId)

      if (stopReplicaWithDelete.nonEmpty) {
        debug(s"The stop replica request (delete = true) sent to broker $brokerId is ${stopReplicaWithDelete.mkString(",")}")
        val stopReplicaRequest = createStopReplicaRequest(brokerEpoch, maxBrokerEpoch, stopReplicaWithDelete, deletePartitions = true)
        val callback = stopReplicaPartitionDeleteResponseCallback(brokerId) _
        sendRequest(brokerId, stopReplicaRequest, callback)
      }

      if (stopReplicaWithoutDelete.nonEmpty) {
        debug(s"The stop replica request (delete = false) sent to broker $brokerId is ${stopReplicaWithoutDelete.mkString(",")}")
        val stopReplicaRequest = createStopReplicaRequest(brokerEpoch, maxBrokerEpoch, stopReplicaWithoutDelete, deletePartitions = false)
        sendRequest(brokerId, stopReplicaRequest)
      }
    }
    stopReplicaRequestMap.clear()
  }

  def sendRequestsToBrokers(controllerEpoch: Int): Unit = {
    try {
      val stateChangeLog = stateChangeLogger.withControllerEpoch(controllerEpoch)
      sendLeaderAndIsrRequest(controllerEpoch, stateChangeLog)
      sendUpdateMetadataRequests(controllerEpoch, stateChangeLog)
      sendStopReplicaRequests(controllerEpoch)
    } catch {
      case e: Throwable =>
        if (leaderAndIsrRequestMap.nonEmpty) {
          error("Haven't been able to send leader and isr requests, current state of " +
            s"the map is $leaderAndIsrRequestMap. Exception message: $e")
        }
        if (updateMetadataRequestBrokerSet.nonEmpty) {
          error(s"Haven't been able to send metadata update requests to brokers $updateMetadataRequestBrokerSet, " +
            s"current state of the partition info is $updateMetadataRequestPartitionInfoMap. Exception message: $e")
        }
        if (stopReplicaRequestMap.nonEmpty) {
          error("Haven't been able to send stop replica requests, current state of " +
            s"the map is $stopReplicaRequestMap. Exception message: $e")
        }
        throw new IllegalStateException(e)
    }
  }

  /**
   * [Federation only] Send the topic-partition metadata from a remote physical cluster to the specified <em>local</em>
   * brokers (only) so they can correctly respond to metadata requests for the entire federation.
   *
   * @param brokers  the brokers that the update metadata request should be sent to
   * @param umr      the (rewritten) remote update metadata request itself
   */
  def sendRemoteRequestToBrokers(brokerIds: Seq[Int], umr: UpdateMetadataRequest): Unit = {
    updateMetadataRequestBrokerSet ++= brokerIds.filter(_ >= 0)
    try {
/*
      GRR FIXME:  do we want/need any kind of trace-level logging like this for remote requests?
      val stateChangeLog = stateChangeLogger.withControllerEpoch(controllerEpoch)
      <loop over partition states>
        stateChangeLog.trace(s"Sending remote UpdateMetadataRequest $partitionState to " +
          s"brokers $updateMetadataRequestBrokerSet for partition $tp")
      <end loop over partition states>
 */

      // note that our caller already updated umr's controllerEpoch field (as well as others), so no need for that here
      val updateMetadataRequestBuilder = new UpdateMetadataRequest.WrappingBuilder(umr)
      updateMetadataRequestBrokerSet.intersect(controllerContext.liveOrShuttingDownBrokerIds).foreach {
        broker => sendRequest(broker, updateMetadataRequestBuilder)
      }

    } catch {
      case e: Throwable =>
        if (updateMetadataRequestBrokerSet.nonEmpty) {
          // GRR FIXME:  do we need any kind of detailed "current state" info from umr here (as in
          //   sendRequestsToBrokers() above)?
          error(s"Haven't been able to forward remote metadata update requests to brokers " +
            s"$updateMetadataRequestBrokerSet. Exception message: $e")
        }
        throw new IllegalStateException(e)
    }
    updateMetadataRequestBrokerSet.clear()
  }
} // end of abstract class AbstractControllerBrokerRequestBatch

case class ControllerBrokerStateInfo(networkClient: NetworkClient,
                                     brokerNode: Node,
                                     messageQueue: BlockingQueue[QueueItem],
                                     requestSendThread: RequestSendThread,
                                     queueSizeGauge: Gauge[Int],
                                     requestRateAndTimeMetrics: Timer,
                                     reconfigurableChannelBuilder: Option[Reconfigurable])


class BrokerResponseTimeStats(val key: ApiKeys) extends KafkaMetricsGroup {
  // Records time for request waits on local send thread queue
  val brokerRequestQueueTime = newHistogram("brokerRequestQueueTimeMs", true, responseTimeTags)
  // Records time for controller to send request and receive response
  val brokerRequestRemoteTime = newHistogram("brokerRequestRemoteTimeMs", true, responseTimeTags)

  def responseTimeTags = Map("request" -> key.toString)

  def update(queueTime: Long, remoteTime: Long): Unit = {
    brokerRequestQueueTime.update(queueTime)
    brokerRequestRemoteTime.update(remoteTime)
  }

  def updateQueueTime(queueTime: Long): Unit = {
    brokerRequestQueueTime.update(queueTime)
  }

  def updateRemoteTime(queueTime: Long): Unit = {
    brokerRequestRemoteTime.update(queueTime)
  }

  def removeMetrics(): Unit = {
    removeMetric("brokerRequestQueueTimeMs", responseTimeTags)
    removeMetric("brokerRequestRemoteTimeMs", responseTimeTags)
  }
}
