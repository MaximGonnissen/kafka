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

package kafka.server

import kafka.utils.Logging
import kafka.cluster.BrokerEndPoint
import kafka.metrics.KafkaMetricsGroup
import com.yammer.metrics.core.Gauge
import org.apache.kafka.common.{KafkaFuture, TopicPartition}
import org.apache.kafka.common.utils.Utils

import scala.collection.{Map, Set, mutable}

abstract class AsyncAbstractFetcherManager[T <: FetcherEventManager](val name: String, clientId: String, numFetchers: Int)
  extends FetcherManagerTrait with Logging with KafkaMetricsGroup {
  // map of (source broker_id, fetcher_id per source broker) => fetcher.
  // package private for test
  private[server] val fetcherThreadMap = new mutable.HashMap[BrokerIdAndFetcherId, T]
  private val lock = new Object
  private val numFetchersPerBroker = numFetchers
  val failedPartitions = new FailedPartitions
  this.logIdent = "[" + name + "] "

  newGauge(
    "MaxLag",
    new Gauge[Long] {
      // current max lag across all fetchers/topics/partitions
      def value: Long = fetcherThreadMap.foldLeft(0L)((curMaxAll, fetcherThreadMapEntry) => {
        fetcherThreadMapEntry._2.fetcherLagStats.stats.foldLeft(0L)((curMaxThread, fetcherLagStatsEntry) => {
          curMaxThread.max(fetcherLagStatsEntry._2.lag)
        }).max(curMaxAll)
      })
    },
    Map("clientId" -> clientId)
  )

  newGauge(
  "MinFetchRate", {
    new Gauge[Double] {
      // current min fetch rate across all fetchers/topics/partitions
      def value: Double = {
        val headRate: Double =
          fetcherThreadMap.headOption.map(_._2.fetcherStats.requestRate.oneMinuteRate).getOrElse(0)

        fetcherThreadMap.foldLeft(headRate)((curMinAll, fetcherThreadMapEntry) => {
          fetcherThreadMapEntry._2.fetcherStats.requestRate.oneMinuteRate.min(curMinAll)
        })
      }
    }
  },
  Map("clientId" -> clientId)
  )

  newGauge(
    "FetchFailureRate", {
      new Gauge[Double] {
        // fetch failure rate sum across all fetchers/topics/partitions
        def value: Double = {
          val headRate: Double =
            fetcherThreadMap.headOption.map(_._2.fetcherStats.requestFailureRate.oneMinuteRate).getOrElse(0)

          fetcherThreadMap.foldLeft(headRate)((curSum, fetcherThreadMapEntry) => {
            fetcherThreadMapEntry._2.fetcherStats.requestRate.oneMinuteRate + curSum
          })
        }
      }
    },
    Map("clientId" -> clientId)
  )

  val failedPartitionsCount = newGauge(
    "FailedPartitionsCount", {
      new Gauge[Int] {
        def value: Int = failedPartitions.size
      }
    },
    Map("clientId" -> clientId)
  )

  newGauge("DeadThreadCount", {
    new Gauge[Int] {
      def value: Int = {
        deadThreadCount
      }
    }
  }, Map("clientId" -> clientId))

  private[server] def deadThreadCount: Int = lock synchronized { fetcherThreadMap.values.count(_.isThreadFailed) }

  /*
  def resizeThreadPool(newSize: Int): Unit = {
    def migratePartitions(newSize: Int): Unit = {
      fetcherThreadMap.foreach { case (id, thread) =>
        val removedPartitions = thread.partitionsAndOffsets
        removeFetcherForPartitions(removedPartitions.keySet)
        if (id.fetcherId >= newSize)
          thread.shutdown()
        addFetcherForPartitions(removedPartitions)
      }
    }
    lock synchronized {
      val currentSize = numFetchersPerBroker
      info(s"Resizing fetcher thread pool size from $currentSize to $newSize")
      numFetchersPerBroker = newSize
      if (newSize != currentSize) {
        // We could just migrate some partitions explicitly to new threads. But this is currently
        // reassigning all partitions using the new thread size so that hash-based allocation
        // works with partition add/delete as it did before.
        migratePartitions(newSize)
      }
      shutdownIdleFetcherThreads()
    }
  }

  // Visible for testing
  private[server] def getFetcher(topicPartition: TopicPartition): Option[T] = {
    lock synchronized {
      fetcherThreadMap.values.find { fetcherThread =>
        fetcherThread.fetchState(topicPartition).isDefined
      }
    }
  }
   */

  // Visibility for testing
  private[server] def getFetcherId(topicPartition: TopicPartition): Int = {
    lock synchronized {
      Utils.abs(31 * topicPartition.topic.hashCode() + topicPartition.partition) % numFetchersPerBroker
    }
  }

  /*
  // This method is only needed by ReplicaAlterDirManager
  def markPartitionsForTruncation(brokerId: Int, topicPartition: TopicPartition, truncationOffset: Long): Unit = {
    lock synchronized {
      val fetcherId = getFetcherId(topicPartition)
      val brokerIdAndFetcherId = BrokerIdAndFetcherId(brokerId, fetcherId)
      fetcherThreadMap.get(brokerIdAndFetcherId).foreach { thread =>
        thread.markPartitionsForTruncation(topicPartition, truncationOffset)
      }
    }
  }
   */

  // to be defined in subclass to create a specific fetcher
  def createFetcherThread(fetcherId: Int, sourceBroker: BrokerEndPoint): T

  def addFetcherForPartitions(partitionAndOffsets: Map[TopicPartition, InitialFetchState]): Unit = {
    lock synchronized {
      val partitionsPerFetcher = partitionAndOffsets.groupBy { case (topicPartition, brokerAndInitialFetchOffset) =>
        BrokerAndFetcherId(brokerAndInitialFetchOffset.leader, getFetcherId(topicPartition))
      }

      def addAndStartFetcherThread(brokerAndFetcherId: BrokerAndFetcherId,
                                   brokerIdAndFetcherId: BrokerIdAndFetcherId): T = {
        val fetcherThread = createFetcherThread(brokerAndFetcherId.fetcherId, brokerAndFetcherId.broker)
        fetcherThreadMap.put(brokerIdAndFetcherId, fetcherThread)
        fetcherThread.start()
        fetcherThread
      }

      val addPartitionFutures = mutable.Buffer[KafkaFuture[Void]]()
      for ((brokerAndFetcherId, initialFetchOffsets) <- partitionsPerFetcher) {
        val brokerIdAndFetcherId = BrokerIdAndFetcherId(brokerAndFetcherId.broker.id, brokerAndFetcherId.fetcherId)
        val fetcherThread = fetcherThreadMap.get(brokerIdAndFetcherId) match {
          case Some(currentFetcherThread) if currentFetcherThread.sourceBroker == brokerAndFetcherId.broker =>
            // reuse the fetcher thread
            currentFetcherThread
          case Some(f) =>
            f.close()
            addAndStartFetcherThread(brokerAndFetcherId, brokerIdAndFetcherId)
          case None =>
            addAndStartFetcherThread(brokerAndFetcherId, brokerIdAndFetcherId)
        }

        val initialOffsetAndEpochs = initialFetchOffsets.map { case (tp, brokerAndInitOffset) =>
          tp -> OffsetAndEpoch(brokerAndInitOffset.initOffset, brokerAndInitOffset.currentLeaderEpoch)
        }

        addPartitionFutures += addPartitionsToFetcherThread(fetcherThread, initialOffsetAndEpochs)
      }

      // wait for all futures to finish
      KafkaFuture.allOf(addPartitionFutures:_*).get()
    }
  }

  protected def addPartitionsToFetcherThread(fetcherThread: T,
                                             initialOffsetAndEpochs: collection.Map[TopicPartition, OffsetAndEpoch]): KafkaFuture[Void] = {
    val future = fetcherThread.addPartitions(initialOffsetAndEpochs)
    info(s"Added fetcher to broker ${fetcherThread.sourceBroker.id} for partitions $initialOffsetAndEpochs")
    future
  }

  def removeFetcherForPartitions(partitions: Set[TopicPartition]): Unit = {
    lock synchronized {
      val futures = mutable.Buffer[KafkaFuture[Void]]()
      for (fetcher <- fetcherThreadMap.values)
        futures += fetcher.removePartitions(partitions)
      KafkaFuture.allOf(futures:_*).get()
      failedPartitions.removeAll(partitions)
    }

    if (partitions.nonEmpty)
      info(s"Removed fetcher for partitions $partitions")
  }

  def shutdownIdleFetcherThreads(): Unit = {
    lock synchronized {
      val futures = mutable.Map[BrokerIdAndFetcherId, KafkaFuture[Int]]()

      for ((key, fetcher) <- fetcherThreadMap) {
        futures.put(key, fetcher.getPartitionsCount())
      }

      for ((key, partitionCountFuture) <- futures) {
        if (partitionCountFuture.get() == 0) {
          fetcherThreadMap.get(key).get.close()
          fetcherThreadMap -= key
        }
      }
    }
  }

  def closeAllFetchers(): Unit = {
    lock synchronized {
      for ( (_, fetcher) <- fetcherThreadMap) {
        fetcher.initShutdown()
      }
      for ( (_, fetcher) <- fetcherThreadMap) {
        fetcher.awaitShutdown()
      }
      fetcherThreadMap.clear()
    }
  }
}
