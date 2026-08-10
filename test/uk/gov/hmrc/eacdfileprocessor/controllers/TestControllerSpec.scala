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

package uk.gov.hmrc.eacdfileprocessor.controllers

import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.CREATED
import play.api.mvc.*
import uk.gov.hmrc.objectstore.client.{ObjectSummaryWithMd5, Path}
import uk.gov.hmrc.objectstore.client.Md5Hash
import play.api.test.Helpers.{GET, INTERNAL_SERVER_ERROR, NO_CONTENT, OK, POST, contentAsString, status, DELETE}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.FileDetailService
import uk.gov.hmrc.eacdfileprocessor.testOnly.controllers.TestController
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TestControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {

  private val mockRepository = mock[FileRepository]
  private val mockFileDetailService = mock[FileDetailService]
  private val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  private val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  private val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  private val mockObjectStoreClient = mock[PlayObjectStoreClient]

  implicit lazy val actorSystem: ActorSystem = ActorSystem()

  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))

  class TestTestController extends TestController(
    mockCC,
    mockConfig,
    mockAuth,
    mockObjectStoreClient,
    mockRepository,
    mockFileDetailService) {
    override def authorisedEntity(
                                   providedPermission: Predicate,
                                   apiName: String
                                 ): ActionBuilder[AuthRequest, AnyContent] =
      DefaultActionBuilder(mockCC.parsers.defaultBodyParser)(global)
        .andThen(new ActionTransformer[Request, AuthRequest] {
          override protected def executionContext: scala.concurrent.ExecutionContext = global

          override protected def transform[A](request: Request[A]): Future[AuthRequest[A]] =
            Future.successful(
              new AuthRequest(
                request,
                HeaderCarrier(),
                Authorization("Bearer test"),
                Retrieval.Username("testuser")
              )
            )
        })
  }

  val controller = new TestTestController
  
  "testController#putObject" should {

    "return 201 Created when the object is successfully stored" in {
      when(mockObjectStoreClient.putObject(any(), any(), any(), any(), any(), any())(any(), any()))
        .thenReturn(Future.successful(ObjectSummaryWithMd5(
          location = Path.File("test-ref-123/test-file.csv/test-file.csv"),
          contentLength = 0,
          contentMd5 = Md5Hash("md5hash"),
          lastModified = java.time.Instant.now()
        )))

      val result = controller.putObject("test-ref-123", "test-file.csv")(FakeRequest(POST, "/test-only/put-object/test-ref-123/test-file.csv"))

      status(result) shouldBe CREATED
      contentAsString(result) shouldBe "Document stored."
    }

    "return 500 InternalServerError when the object store client throws an exception" in {
      when(mockObjectStoreClient.putObject(any(), any(), any(), any(), any(), any())(any(), any()))
        .thenReturn(Future.failed(new RuntimeException("Unexpected error")))

      val result = controller.putObject("test-ref-123", "test-file.csv")(FakeRequest(POST, "/test-only/put-object/test-ref-123/test-file.csv"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error saving the document"
    }
  }
  
  "TestController#deleteAllObjects" should {

    "return 200 OK when all objects are successfully deleted" in {
      when(mockRepository.dropCollection()).thenReturn(Future.successful(()))

      val result = controller.deleteAllObjects()(FakeRequest(DELETE, "/test-only/delete-all"))

      status(result) shouldBe OK
      contentAsString(result) shouldBe "All test records deleted."
    }

    "return 500 InternalServerError when the repository throws an exception" in {
      when(mockRepository.dropCollection()).thenReturn(Future.failed(new RuntimeException("DB error")))

      val result = controller.deleteAllObjects()(FakeRequest(DELETE, "/test-only/delete-all"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error deleting documents"
    }
  }
}