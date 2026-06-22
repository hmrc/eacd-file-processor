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
import play.api.libs.json.Json
import play.api.mvc.*
import uk.gov.hmrc.objectstore.client.{ObjectSummaryWithMd5, Path}
import uk.gov.hmrc.objectstore.client.Md5Hash
import play.api.test.Helpers.{DELETE, GET, INTERNAL_SERVER_ERROR, NO_CONTENT, OK, POST, contentAsJson, contentAsString, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.models.{Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED
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

  private val successResponse: UploadedDetails = scannedUploadedDetails.copy(reference = Reference("test-ref-123"))

  private val failedResponse: UploadedDetails = failedUploadedDetails.copy(reference = Reference("test-ref-456"))

  private val approvedResponse: UploadedDetails = scannedUploadedDetails.copy(
    reference = Reference("test-ref-789"),
    status = APPROVED,
    approverDetails = Some(approverDetails),
    approvedAtDateTime = Some(createdAt),
    totalEntryCount = Some(100),
    totalSuccessCount = Some(95),
    totalFailureCount = Some(5)
  )


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

  "TestController#getFileDetail" should {

    "return 200 OK with file detail JSON for a successfully uploaded file" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(successResponse)))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))

      status(result) shouldBe OK
      val body = contentAsJson(result)
      (body \ "reference" \ "value").as[String] shouldBe "test-ref-123"
      (body \ "status").as[String] shouldBe successResponse.status.value
    }

    "return 200 OK with file detail JSON for a failed file" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(failedResponse)))

      val result = controller.getFileDetail("test-ref-456")(FakeRequest(GET, "/test-only/file-detail/test-ref-456"))

      status(result) shouldBe OK
      val body = contentAsJson(result)
      (body \ "reference" \ "value").as[String] shouldBe "test-ref-456"
      (body \ "status").as[String] shouldBe failedResponse.status.value
    }

    "return 200 OK with file detail JSON for an approved file with approver details" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(approvedResponse)))

      val result = controller.getFileDetail("test-ref-789")(FakeRequest(GET, "/test-only/file-detail/test-ref-789"))

      status(result) shouldBe OK
      val body = contentAsJson(result)
      println(Console.MAGENTA + "Response JSON: " + Json.prettyPrint(Json.parse(contentAsString(result))) + Console.RESET)
      (body \ "reference" \ "value").as[String] shouldBe "test-ref-789"
      (body \ "status").as[String] shouldBe APPROVED.value
    }

    "return 204 NoContent when the service returns None for an unknown reference" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(None))

      val result = controller.getFileDetail("unknown-ref")(FakeRequest(GET, "/test-only/file-detail/unknown-ref"))

      status(result) shouldBe NO_CONTENT
    }

    "return 204 NoContent and an empty body when no file detail exists" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(None))

      val result = controller.getFileDetail("missing-ref")(FakeRequest(GET, "/test-only/file-detail/missing-ref"))

      status(result) shouldBe NO_CONTENT
      contentAsString(result) shouldBe ""
    }

    "return 500 InternalServerError with error body when the service throws a RuntimeException" in {
      when(mockFileDetailService.getFileDetail(any()))
        .thenReturn(Future.failed(new RuntimeException("Unexpected DB failure")))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error retrieving details"
    }

    "return 500 InternalServerError with error body when the service throws an IllegalStateException" in {
      when(mockFileDetailService.getFileDetail(any()))
        .thenReturn(Future.failed(new IllegalStateException("Repository unavailable")))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error retrieving details"
    }

    "pass the exact reference string to the service" in {
      val specificRef = "08aad019-7f66-4456-8d52-93f12109876f"
      when(mockFileDetailService.getFileDetail(eqTo(specificRef))).thenReturn(Future.successful(Some(successResponse.copy(reference = Reference(specificRef)))))
      val result = controller.getFileDetail(specificRef)(FakeRequest(GET, s"/test-only/file-detail/$specificRef"))

      status(result) shouldBe OK
      verify(mockFileDetailService, times(1)).getFileDetail(eqTo(specificRef))
    }

    "call the service exactly once per request" in {
      org.mockito.Mockito.reset(mockFileDetailService)
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(successResponse)))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))
      status(result) shouldBe OK

      verify(mockFileDetailService, times(1)).getFileDetail(any())
    }

    "not call the service more than once even when result is None" in {
      org.mockito.Mockito.reset(mockFileDetailService)
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(None))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))
      status(result) shouldBe NO_CONTENT

      verify(mockFileDetailService, times(1)).getFileDetail(any())
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