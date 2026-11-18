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

package uk.gov.hmrc.eacdfileprocessor.support.controllers

import org.bson.types.ObjectId
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.mvc.*
import play.api.test.Helpers.{GET, contentAsJson, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.DeEnrolmentWorkItemRepository
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class WorkItemControllerSpec extends TestSupport with DefaultAwaitTimeout {
  private val repository = mock[DeEnrolmentWorkItemRepository]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]

  object TestWorkItemController extends WorkItemController(repository, mockCC, mockConfig, mockAuth) {
    override def authorisedEntity(
      providedPermission: Predicate,
      apiName: String
    ): ActionBuilder[AuthRequest, AnyContent] =
      DefaultActionBuilder(mockCC.parsers.defaultBodyParser)(global)
        .andThen(new ActionTransformer[Request, AuthRequest] {
          override protected def executionContext: scala.concurrent.ExecutionContext = global

          override protected def transform[A](request: Request[A]): Future[AuthRequest[A]] =
            scala.concurrent.Future.successful(
              new AuthRequest(
                request,
                HeaderCarrier(),
                Authorization("Bearer test"),
                Retrieval.Username("testuser")
              )
            )
        })
  }

  "WorkItemController" should {
    "return the matching work items for a reference" in {
      val creationDateTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
      val reference = "test-ref"
      val workItems = Seq(
        WorkItem(
          id = ObjectId.get(),
          receivedAt = Instant.now(),
          updatedAt = Instant.now(),
          availableAt = Instant.now(),
          status = ProcessingStatus.ToDo,
          failureCount = 0,
          item = DeEnrolmentWorkItem(reference, "record-1", creationDateTime)
        ),
        WorkItem(
          id = ObjectId.get(),
          receivedAt = Instant.now(),
          updatedAt = Instant.now(),
          availableAt = Instant.now(),
          status = ProcessingStatus.InProgress,
          failureCount = 1,
          item = DeEnrolmentWorkItem(reference, "record-2", creationDateTime)
        )
      )

      val workItemsResponse: Seq[DeEnrolmentWorkItem] = workItems.map(
        workItem => DeEnrolmentWorkItem(workItem.item.reference, workItem.item.recordDetail, creationDateTime)
      )

      when(repository.findByReference(reference)).thenReturn(Future.successful(workItems))

      val request = FakeRequest(GET, s"/work-item/$reference")
      val result = TestWorkItemController.getWorkItem(reference)(request)

      status(result) shouldBe OK
      contentAsJson(result).as[Seq[DeEnrolmentWorkItem]] shouldBe workItemsResponse
    }

    "return NO_CONTENT when no work items exist for the reference" in {
      val reference = "missing-ref"

      when(repository.findByReference(reference)).thenReturn(Future.successful(Seq.empty))

      val request = FakeRequest(GET, s"/work-item/$reference")
      val result = TestWorkItemController.getWorkItem(reference)(request)

      status(result) shouldBe NO_CONTENT
    }
  }
}

