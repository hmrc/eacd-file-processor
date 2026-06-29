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

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.*
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.test.Helpers.{contentAsJson, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.FileStatusCount
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class StatusControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {
  private val repository = mock[FileRepository]
  private implicit val materializer: Materializer = mock[Materializer]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))

  val statusCounts = Seq(FileStatusCount(UPLOADED.value, 5), FileStatusCount(APPROVED.value, 3))

  object TestStatusController extends StatusController(repository, mockCC, mockConfig, mockAuth) {
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

  "StatusController" should {
    "getAllStatusCounts" should {
      "return NO_CONTENT when there is no files within fileExpiryDays found" in {
        when(repository.getFileStatusCounts).thenReturn(Future.successful(Seq.empty))
        val result = TestStatusController.getAllStatusCounts(FakeRequest())
        status(result) shouldBe NO_CONTENT
      }
      "return OK with all the status counts when some files found" in {
        when(repository.getFileStatusCounts).thenReturn(Future.successful(statusCounts))
        val result = TestStatusController.getAllStatusCounts(FakeRequest())
        status(result) shouldBe OK
        contentAsJson(result) shouldBe expectedStatusCounts
      }
      "return OK with all the status counts when all status files found" in {
        when(repository.getFileStatusCounts).thenReturn(Future.successful(allStatusCounts))
        val result = TestStatusController.getAllStatusCounts(FakeRequest())
        status(result) shouldBe OK
        contentAsJson(result) shouldBe Json.toJson(allStatusCounts)
      }
    }
    "generateAllStatusCount" should {
      "return a complete list of status counts when there are missing status counts" in {
        val expected = Seq(
          FileStatusCount(SCANNED.value, 0),
          FileStatusCount(FAILED.value, 0),
          FileStatusCount(STORED.value, 0),
          FileStatusCount(UPLOADED.value, 5),
          FileStatusCount(UPLOADREJECTED.value, 0),
          FileStatusCount(REJECTED.value, 0),
          FileStatusCount(APPROVED.value, 3),
          FileStatusCount(PROCESSING.value, 0),
          FileStatusCount(PROCESSEDWITHERRORS.value, 0),
          FileStatusCount(PROCESSEDSUCCESSFULLY.value, 0)
        )
        val actual = TestStatusController.generateAllStatusCount(statusCounts)
        actual shouldBe expected
      }
      "return a complete list of status counts when all the status counts are present" in {
        val actual = TestStatusController.generateAllStatusCount(allStatusCounts)
        actual shouldBe allStatusCounts
      }
    }
  }
}
