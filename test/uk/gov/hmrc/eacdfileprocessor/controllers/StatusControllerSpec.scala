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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, NO_CONTENT, OK, SERVICE_UNAVAILABLE}
import play.api.libs.json.Json
import play.api.mvc.Results.{BadRequest, NoContent, ServiceUnavailable}
import play.api.mvc.*
import play.api.test.Helpers.{contentAsJson, contentAsString, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, StatusDetailsModel}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.StatusService
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class StatusControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {
  private val repository = mock[FileRepository]
  private val mockStatusService = mock[StatusService]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))


  object TestStatusController extends StatusController(repository, mockStatusService, mockCC, mockConfig, mockAuth) {
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
    "return NO_CONTENT when correctly information is supplied" in {
      when(repository.findByReference(any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
      when(mockStatusService.updateStatus(any(), any(), any(), any())).thenReturn(Future.successful(NoContent))
      val result = TestStatusController.updateStatus("ref1")(FakeRequest(
        routes.StatusController.updateStatus("ref1"))
        .withBody(updateStatusRequestBody)
      )
      status(result) shouldBe NO_CONTENT
    }
    "return BAD_REQUEST when file reference is not found" in {
      when(repository.findByReference(any())).thenReturn(Future.successful(None))
      val result = TestStatusController.updateStatus("ref1")(FakeRequest(
        routes.StatusController.updateStatus("ref1"))
        .withBody(updateStatusRequestBody)
      )
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_FILE_REF", "File reference doesn't exist"))
    }
    "return BAD_REQUEST when status service returns BAD_REQUEST" in {
      when(repository.findByReference(any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
      when(mockStatusService.updateStatus(any(), any(), any(), any())).thenReturn(Future.successful(
        BadRequest(Json.toJson(ApiErrorResponse("APPROVER_FIELDS_MISSING", "Approver fields are missing for status approved")))))
      val result = TestStatusController.updateStatus("ref1")(FakeRequest(
        routes.StatusController.updateStatus("ref1"))
        .withBody(updateStatusRequestBody)
      )
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("APPROVER_FIELDS_MISSING", "Approver fields are missing for status approved"))
    }
    "return SERVICE_UNAVAILABLE when status service returns SERVICE_UNAVAILABLE" in {
      when(repository.findByReference(any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
      when(mockStatusService.updateStatus(any(), any(), any(), any())).thenReturn(Future.successful(
        ServiceUnavailable(Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred")))))
      val result = TestStatusController.updateStatus("ref1")(FakeRequest(
        routes.StatusController.updateStatus("ref1"))
        .withBody(updateStatusRequestBody)
      )
      status(result) shouldBe SERVICE_UNAVAILABLE
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred"))
    }
    "return BAD_REQUEST when status is missing" in {
      when(repository.findByReference(any())).thenReturn(Future.successful(None))
      val result = TestStatusController.updateStatus("ref1")(FakeRequest(
        routes.StatusController.updateStatus("ref1"))
        .withBody(updateStatusRequestBodyWithoutStatus)
      )
      status(result) shouldBe BAD_REQUEST
      contentAsString(result) must include("Invalid StatusApproverDetails payload")
    }

    "getFileStatus return correct responses" when {
      "returning OK for found file with legitimate status" in {
        when(repository.findByStatus(any())).thenReturn(Future.successful(Seq(statusDetailsModel)))
        val result = TestStatusController.getFilesStatus("approved")(FakeRequest(
          routes.StatusController.getFilesStatus("approved"))
        )
        status(result) shouldBe OK

      }

      "returning NO_CONTENT for no file found with legitimate status" in {
        when(repository.findByStatus(any())).thenReturn(Future.successful(Seq.empty[StatusDetailsModel]))
        val result = TestStatusController.getFilesStatus("approved")(FakeRequest(
          routes.StatusController.getFilesStatus("approved"))
        )
        status(result) shouldBe NO_CONTENT

      }

      "returning BAD_REQUEST for request being made with il-legitimate status" in {
        val result = TestStatusController.getFilesStatus("notValidStatus")(FakeRequest(
          routes.StatusController.getFilesStatus("notValidStatus"))
        )
        status(result) shouldBe BAD_REQUEST

      }

      "return INTERNAL_SERVER_ERROR when repository throws an exception" in {
        when(repository.findByStatus(any())).thenReturn(Future.failed(new Exception("Database error")))
        val result = TestStatusController.getFilesStatus("approved")(FakeRequest(
          routes.StatusController.getFilesStatus("approved"))
        )
        status(result) shouldBe INTERNAL_SERVER_ERROR
        contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("INTERNAL_ERROR", "An error occurred"))
      }
    }
  }
}
