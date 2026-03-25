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

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.exceptions.DuplicateReferenceException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.ApiErrorResponse
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag

class InitiateFileStorageControllerSpec extends TestData with UnitSpec {

  implicit val mat: Materializer = mock[Materializer]

  val mockRepo: FileRepository = mock[FileRepository]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]

  class TestInitiateFileStorageController
    extends InitiateFileStorageController(mockRepo, mockCC, mockConfig, mockAuth) {
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

  val controller = new TestInitiateFileStorageController

  when(mockConfig.getOptional[Boolean](org.mockito.ArgumentMatchers.eq("internalAuth.enabled"))(any()))
    .thenReturn(Some(true))
  when(mockConfig.getOptional[Boolean](org.mockito.ArgumentMatchers.eq("internalAuth.initiate.enabled"))(any()))
    .thenReturn(Some(true))

  "POST /initiate" should {
    "return 201 Created for valid request" in {
      when(mockRepo.createFileRecord(any())).thenReturn(Future.successful(true))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User",
      )
      val request = FakeRequest(POST, "/initiate").withBody(json)
      val result = await(controller.initiateFileRecordStore()(request))
      status(result) shouldBe CREATED
    }

    "return 400 for missing fields" in {
      val json = Json.obj("reference" -> "ref1")
      val request = FakeRequest(POST, "/initiate").withBody(json)
      val result = await(controller.initiateFileRecordStore()(request))
      status(result) shouldBe BAD_REQUEST
      jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing"))
    }

    "return 400 for invalid email" in {
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "invalid-email",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withBody(json)
      val result = await(controller.initiateFileRecordStore()(request))
      status(result) shouldBe BAD_REQUEST
      jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_REQUESTOR_EMAIL", "Invalid requestor email"))
    }

    "return 400 for duplicate reference" in {
      when(mockRepo.createFileRecord(any())).thenReturn(Future.failed(new DuplicateReferenceException("Duplicate external file reference")))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withBody(json)
      val result = await(controller.initiateFileRecordStore()(request))
      status(result) shouldBe BAD_REQUEST
      jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("DUPLICATE_EXTERNAL_FILE_REF", "Duplicate external file reference"))
    }

    "return 500 for unknown repository error" in {
      when(mockRepo.createFileRecord(any())).thenReturn(Future.successful(new Exception("Unknown error")))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withBody(json)
      val result = await(controller.initiateFileRecordStore()(request))
      status(result) shouldBe INTERNAL_SERVER_ERROR
      jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred"))
    }
  }
}
