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

import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.models.ApiErrorResponse
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.{FileRepository, MongoResponses}
import uk.gov.hmrc.http.{HeaderCarrier, Authorization}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag

class InitiateFileStorageControllerSpec extends AnyWordSpec with Matchers with MockitoSugar {

  val mockRepo: FileRepository = mock[FileRepository]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]

  class TestInitiateFileStorageController
    extends initiateFileStorageController(mockRepo, mockCC, mockConfig, mockAuth) {
    override def authorisedEntity(
      providedPermission: Predicate.Permission,
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
      when(mockRepo.createFileRecord(any())).thenReturn(Future.successful(MongoResponses.MongoSuccessCreate))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withJsonBody(json)
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe CREATED
    }

    "return 400 for missing fields" in {
      val json = Json.obj("reference" -> "ref1")
      val request = FakeRequest(POST, "/initiate").withJsonBody(json)
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing"))
    }

    "return 400 for invalid email" in {
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "invalid-email",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withJsonBody(json)
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_REQUESTOR_EMAIL", "Invalid requestor email"))
    }

    "return 400 for duplicate reference" in {
      when(mockRepo.createFileRecord(any())).thenReturn(Future.successful(MongoResponses.MongoDuplicateKey))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withJsonBody(json)
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("DUPLICATE_EXTERNAL_FILE_REF", "Duplicate external file reference"))
    }

    "return 400 for non-JSON body" in {
      val request = FakeRequest(POST, "/initiate").withTextBody("not json")
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing"))
    }

    "return 500 for unknown repository error" in {
      when(mockRepo.createFileRecord(any())).thenReturn(Future.successful(null))
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "test@example.com",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/initiate").withJsonBody(json)
      val result: Future[Result] = controller.initiateFileRecordStore()(request)
      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsJson(result) shouldBe Json.toJson(ApiErrorResponse("UNKNOWN_ERROR", "Unknown error occurred"))
    }
  }
}
