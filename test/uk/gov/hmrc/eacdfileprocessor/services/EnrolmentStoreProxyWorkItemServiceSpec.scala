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
import org.mockito.ArgumentMatchers.{any, anyLong, anyString, eq as eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.eacdfileprocessor.models.EnrolmentStoreProxyWorkItemPayload
import uk.gov.hmrc.eacdfileprocessor.repository.EnrolmentStoreProxyWorkItemRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EnrolmentStoreProxyWorkItemServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(
    timeout  = Span(10, Seconds),
    interval = Span(50, Millis)
  )

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private def mkWorkItem(id: ObjectId, payload: EnrolmentStoreProxyWorkItemPayload): WorkItem[EnrolmentStoreProxyWorkItemPayload] =
    WorkItem(
      id           = id,
      receivedAt   = Instant.now(),
      updatedAt    = Instant.now(),
      availableAt  = Instant.now(),
      status       = ProcessingStatus.ToDo,
      failureCount = 0,
      item         = payload
    )

  "EnrolmentStoreProxyWorkItemService" should {

    "process bursts in chunks and never exceed maxConcurrent in-flight connector calls" in {
      val appConfig  = mock[AppConfig]
      val repository = mock[EnrolmentStoreProxyWorkItemRepository]
      val connector  = mock[EnrolmentStoreProxyConnector]
      val service    = new EnrolmentStoreProxyWorkItemService(appConfig, repository, connector)

      when(appConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(2)

      val ids = (1 to 5).map(_ => new ObjectId())
      val workItems = ids.zipWithIndex.map { case (id, idx) =>
        mkWorkItem(
          id,
          EnrolmentStoreProxyWorkItemPayload("batch-1", s"burst-${idx + 1}", 8000L)
        )
      }

      when(repository.pushNewBatch(any[Seq[EnrolmentStoreProxyWorkItemPayload]], any(), any())).thenReturn(Future.successful(workItems))
      when(repository.markAs(any[ObjectId], eqTo(ProcessingStatus.InProgress), any())).thenReturn(Future.successful(true))
      when(repository.findById(any[ObjectId])).thenAnswer((inv: InvocationOnMock) => {
        val id = inv.getArgument[ObjectId](0)
        Future.successful(workItems.find(_.id == id))
      })
      when(repository.completeAndDelete(any[ObjectId])).thenReturn(Future.successful(true))

      val inFlight    = new AtomicInteger(0)
      val maxInFlight = new AtomicInteger(0)
      when(connector.sendFileNotification(anyString(), anyLong())(any())).thenAnswer((_: InvocationOnMock) =>
        Future {
          val current = inFlight.incrementAndGet()
          maxInFlight.accumulateAndGet(current, Math.max)
          Thread.sleep(120)
          inFlight.decrementAndGet()
          ()
        }
      )

      service.fireBurst(count = 5, stubDelayMs = 8000L).futureValue

      maxInFlight.get() should be <= 2
      verify(connector, times(5)).sendFileNotification(anyString(), anyLong())(any())
      verify(repository, times(5)).completeAndDelete(any[ObjectId])
    }

    "report status based on current in-progress WorkItems" in {
      val appConfig  = mock[AppConfig]
      val repository = mock[EnrolmentStoreProxyWorkItemRepository]
      val connector  = mock[EnrolmentStoreProxyConnector]
      val service    = new EnrolmentStoreProxyWorkItemService(appConfig, repository, connector)

      when(appConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(2)
      when(repository.count(ProcessingStatus.InProgress)).thenReturn(Future.successful(1L))

      val status = service.getThrottlingStatus.futureValue.enrolmentStoreProxy

      status.maxConcurrent shouldBe 2
      status.currentlyProcessing shouldBe 1
      status.availablePermits shouldBe 1
      status.maxPerSecond shouldBe 0
      status.tokensRemainingThisSecond shouldBe -1
    }

    "mark a WorkItem as failed when connector call fails" in {
      val appConfig  = mock[AppConfig]
      val repository = mock[EnrolmentStoreProxyWorkItemRepository]
      val connector  = mock[EnrolmentStoreProxyConnector]
      val service    = new EnrolmentStoreProxyWorkItemService(appConfig, repository, connector)

      when(appConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(1)

      val id       = new ObjectId()
      val workItem = mkWorkItem(id, EnrolmentStoreProxyWorkItemPayload("batch-2", "burst-1", 0L))

      when(repository.pushNewBatch(any[Seq[EnrolmentStoreProxyWorkItemPayload]], any(), any())).thenReturn(Future.successful(Seq(workItem)))
      when(repository.markAs(any[ObjectId], eqTo(ProcessingStatus.InProgress), any())).thenReturn(Future.successful(true))
      when(repository.findById(any[ObjectId])).thenReturn(Future.successful(Some(workItem)))
      when(connector.sendFileNotification(anyString(), anyLong())(any())).thenReturn(Future.failed(new RuntimeException("boom")))
      when(repository.complete(id, ProcessingStatus.Failed)).thenReturn(Future.successful(true))

      val ex = service.fireBurst(count = 1, stubDelayMs = 0L).failed.futureValue
      ex.getMessage shouldBe "boom"
      verify(repository, times(1)).complete(id, ProcessingStatus.Failed)
    }

    "fail the burst when a pulled WorkItem cannot be found" in {
      val appConfig  = mock[AppConfig]
      val repository = mock[EnrolmentStoreProxyWorkItemRepository]
      val connector  = mock[EnrolmentStoreProxyConnector]
      val service    = new EnrolmentStoreProxyWorkItemService(appConfig, repository, connector)

      when(appConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(1)

      val id       = new ObjectId()
      val workItem = mkWorkItem(id, EnrolmentStoreProxyWorkItemPayload("batch-3", "burst-1", 0L))

      when(repository.pushNewBatch(any[Seq[EnrolmentStoreProxyWorkItemPayload]], any(), any())).thenReturn(Future.successful(Seq(workItem)))
      when(repository.markAs(any[ObjectId], eqTo(ProcessingStatus.InProgress), any())).thenReturn(Future.successful(true))
      when(repository.findById(any[ObjectId])).thenReturn(Future.successful(None))

      val ex = service.fireBurst(count = 1, stubDelayMs = 0L).failed.futureValue
      ex.getMessage should include("not found")
    }
  }
}

