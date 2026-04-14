/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.eacdfileprocessor.services

import org.bson.types.ObjectId
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.eacdfileprocessor.models.EnrolmentStoreProxyWorkItemPayload
import uk.gov.hmrc.eacdfileprocessor.repository.EnrolmentStoreProxyWorkItemRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Processes connector bursts via Mongo WorkItems instead of in-memory throttling.
 *
 * A burst is persisted first, then executed in chunks of max-concurrent so the
 * concurrency cap is deterministic and survives process restarts.
 */
@Singleton
class EnrolmentStoreProxyWorkItemService @Inject()(
  appConfig:    AppConfig,
  repository:   EnrolmentStoreProxyWorkItemRepository,
  connector:    EnrolmentStoreProxyConnector
)(implicit ec: ExecutionContext) extends Logging {

  def fireBurst(count: Int, stubDelayMs: Long = 0L)(implicit hc: HeaderCarrier): Future[Unit] = {
    val batchId = UUID.randomUUID().toString
    val items = (1 to count).map { i =>
      EnrolmentStoreProxyWorkItemPayload(
        batchId       = batchId,
        fileReference = s"burst-$i",
        stubDelayMs   = stubDelayMs
      )
    }

    repository.pushNewBatch(items).flatMap { workItems =>
      val maxConcurrent = appConfig.maxConcurrentEnrolmentStoreProxyRequests.max(1)
      val chunks        = workItems.map(_.id).grouped(maxConcurrent).toSeq

      chunks.foldLeft(Future.unit) { (acc, chunk) =>
        acc.flatMap(_ => processChunk(chunk))
      }
    }
  }

  def getThrottlingStatus: Future[ThrottlingStatus] = {
    val maxConcurrent = appConfig.maxConcurrentEnrolmentStoreProxyRequests.max(1)

    repository.count(ProcessingStatus.InProgress).map { inProgressRaw =>
      val inProgress       = inProgressRaw.toInt
      val availablePermits = (maxConcurrent - inProgress).max(0)

      ThrottlingStatus(
        enrolmentStoreProxy = ServiceThrottleState(
          maxConcurrent             = maxConcurrent,
          availablePermits          = availablePermits,
          currentlyProcessing       = inProgress,
          // WorkItem mode does not use token-bucket rate limiting.
          maxPerSecond              = 0,
          tokensRemainingThisSecond = -1
        )
      )
    }
  }

  private def processChunk(ids: Seq[ObjectId])(implicit hc: HeaderCarrier): Future[Unit] =
    Future.traverse(ids) { id =>
      repository.markAs(id, ProcessingStatus.InProgress)
    }.flatMap { _ =>
      Future.traverse(ids)(processWorkItem).map(_ => ())
    }

  private def processWorkItem(id: ObjectId)(implicit hc: HeaderCarrier): Future[Unit] =
    repository.findById(id).flatMap {
      case Some(workItem) =>
        connector
          .sendFileNotification(workItem.item.fileReference, workItem.item.stubDelayMs)
          .flatMap(_ => repository.completeAndDelete(id).map(_ => ()))
          .recoverWith { case e =>
            logger.warn(s"[EnrolmentStoreProxyWorkItemService][processWorkItem] Failed for id=$id", e)
            repository.complete(id, ProcessingStatus.Failed).flatMap(_ => Future.failed(e))
          }

      case None =>
        Future.failed(new RuntimeException(s"Work item $id not found"))
    }
}

