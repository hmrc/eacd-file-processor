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

import org.apache.pekko.actor.ActorSystem
import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.libs.json.{JsNull, Json}
import play.api.mvc.*
import play.api.test.Helpers.{GET, INTERNAL_SERVER_ERROR, NO_CONTENT, OK, contentAsJson, contentAsString, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.FileDetailService
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileDetailsControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {
  private val mockRepository = mock[FileRepository]
  private val mockFileDetailService = mock[FileDetailService]
  private val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  private val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  private val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  private val mockObjectStoreClient = mock[PlayObjectStoreClient]

  implicit lazy val actorSystem: ActorSystem = ActorSystem()

  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))

  class TestTestController extends FileDetailsController(
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

  private val successResponse = UploadedDetails(
    id = new ObjectId(),
    reference = Reference("test-ref-123"),
    status = FileStatus.SCANNED,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    details = Some(Details.UploadedSuccessfully(
      name = "bulk-de-enrol.csv",
      mimeType = "text/csv",
      downloadUrl = new URI("http://localhost:9570/upscan/download/some-file").toURL,
      size = Some(32270L),
      checksum = "abc123"
    )),
    approverDetails = None,
    totalEntryCount = Some(0),
    uploadedDateTime = Some(createdAt),
    lastUpdatedDateTime = Some(createdAt),
    approvedAtDateTime = None,
    creationDateTime = createdAt,
    totalFailureCount = Some(0),
    totalSuccessCount = Some(0)
  )
  private val failedResponse = UploadedDetails(
    id = new ObjectId(),
    reference = Reference("test-ref-456"),
    status = FileStatus.FAILED,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    details = Some(Details.UploadedFailed(
      failureReason = "REJECTED",
      message = "MIME type application/pdf is not allowed for service"
    )),
    approverDetails = None,
    totalEntryCount = Some(0),
    uploadedDateTime = None,
    lastUpdatedDateTime = None,
    approvedAtDateTime = None,
    creationDateTime = createdAt,
    totalFailureCount = Some(0),
    totalSuccessCount = Some(0)
  )
  private val approvedResponse = successResponse.copy(
    reference = Reference("test-ref-123"),
    status = FileStatus.APPROVED,
    approverDetails = Some(ApproverDetails(
      approverEmail = Some("approverTest@hmrc.gov.uk"),
      approverPID = Some("87654321"),
      approverName = Some("Approver1")
    )),
    approvedAtDateTime = Some(createdAt),
    totalEntryCount = Some(100),
    totalSuccessCount = Some(95),
    totalFailureCount = Some(5)
  )

  "TestController#getFileDetail" should {

    "return 200 OK with file detail string for a successfully uploaded file" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(successResponse)))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.obj(
        "id" -> successResponse.id.toHexString,
        "reference" -> "test-ref-123",
        "status" -> "scanned",
        "requestorPID" -> "12345678",
        "requestorEmail" -> "test@hmrc.gov.uk",
        "requestorName" -> "Test User",
        "details" -> Json.obj(
          "name" -> "bulk-de-enrol.csv",
          "mimeType" -> "text/csv",
          "downloadUrl" -> "http://localhost:9570/upscan/download/some-file",
          "size" -> 32270,
          "checksum" -> "abc123"
        ),
        "approverDetails" -> JsNull,
        "totalEntryCount" -> 0,
        "uploadedDateTime" -> "2026-02-18T12:43:58.342Z",
        "lastUpdatedDateTime" -> "2026-02-18T12:43:58.342Z",
        "approvedAtDateTime" -> JsNull,
        "creationDateTime" -> "2026-02-18T12:43:58.342Z",
        "totalFailureCount" -> 0,
        "totalSuccessCount" -> 0
      )
    }

    "return 200 OK with file detail string for a failed file" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(failedResponse)))

      val result = controller.getFileDetail("test-ref-456")(FakeRequest(GET, "/test-only/file-detail/test-ref-456"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.obj(
        "id" -> failedResponse.id.toHexString,
        "reference" -> "test-ref-456",
        "status" -> "failed",
        "requestorPID" -> "12345678",
        "requestorEmail" -> "test@hmrc.gov.uk",
        "requestorName" -> "Test User",
        "details" -> Json.obj(
          "failureReason" -> "REJECTED",
          "message" -> "MIME type application/pdf is not allowed for service"
        ),
        "approverDetails" -> JsNull,
        "totalEntryCount" -> 0,
        "uploadedDateTime" -> JsNull,
        "lastUpdatedDateTime" -> JsNull,
        "approvedAtDateTime" -> JsNull,
        "creationDateTime" -> "2026-02-18T12:43:58.342Z",
        "totalFailureCount" -> 0,
        "totalSuccessCount" -> 0
      )
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
      when(mockFileDetailService.getFileDetail(eqTo(specificRef))).thenReturn(Future.successful(Some(successResponse)))

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

    "return 200 OK with file detail string for an approved file with approver details" in {
      when(mockFileDetailService.getFileDetail(any())).thenReturn(Future.successful(Some(approvedResponse)))

      val result = controller.getFileDetail("test-ref-123")(FakeRequest(GET, "/test-only/file-detail/test-ref-123"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.obj(
        "id" -> approvedResponse.id.toHexString,
        "reference" -> "test-ref-123",
        "status" -> "approved",
        "requestorPID" -> "12345678",
        "requestorEmail" -> "test@hmrc.gov.uk",
        "requestorName" -> "Test User",
        "details" -> Json.obj(
          "name" -> "bulk-de-enrol.csv",
          "mimeType" -> "text/csv",
          "downloadUrl" -> "http://localhost:9570/upscan/download/some-file",
          "size" -> 32270,
          "checksum" -> "abc123"
        ),
        "approverDetails" -> Json.obj(
          "approverEmail" -> "approverTest@hmrc.gov.uk",
          "approverPID" -> "87654321",
          "approverName" -> "Approver1"
        ),
        "totalEntryCount" -> 100,
        "uploadedDateTime" -> "2026-02-18T12:43:58.342Z",
        "lastUpdatedDateTime" -> "2026-02-18T12:43:58.342Z",
        "approvedAtDateTime" -> "2026-02-18T12:43:58.342Z",
        "creationDateTime" -> "2026-02-18T12:43:58.342Z",
        "totalFailureCount" -> 5,
        "totalSuccessCount" -> 95
      )
    }
  }
}