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

import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT, UNSUPPORTED_MEDIA_TYPE}
import play.api.libs.json.Json
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import play.api.test.Helpers.{PUT, await, contentAsJson, route, status, writeableOf_AnyContentAsJson, writeableOf_AnyContentAsText}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{APPROVED, FAILED, INITIAL, SCANNED, STORED}
import uk.gov.hmrc.eacdfileprocessor.models.Reference
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository

import scala.concurrent.Future

class StatusControllerISpec extends TestSupport with TestData with DefaultAwaitTimeout:
  lazy val repository = app.injector.instanceOf[FileRepository]
  val reference = "08aad019-7f66-4456-8d52-93f12109876f"

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "POST /status:reference (integration)" should {
    "return 204 when updating status to approved and correct information were supplied" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "23456789"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
        uploadedFileDetails <- repository.findByReference(Reference(reference))
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
        uploadedFileDetails.map(_.uploadedDateTime.isDefined) shouldBe Some(false)
      }
    }
    "return 204 when updating status to upload rejected and correct information were supplied " in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "uploadRejected",
          "errorCode" -> "error code",
          "errorMessage" -> "error message"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = INITIAL))
        result <- route(app, request).get
        uploadedFileDetails <- repository.findByReference(Reference(reference))
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
        uploadedFileDetails.map(_.uploadedDateTime.isDefined) shouldBe Some(true)
      }
    }
    "return 204 when updating status to uploaded" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "uploaded"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = INITIAL))
        result <- route(app, request).get
        uploadedFileDetails <- repository.findByReference(Reference(reference))
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
        uploadedFileDetails.map(_.uploadedDateTime.isDefined) shouldBe Some(true)
      }
    }
    "return 204 when updating status to rejected" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "rejected"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
        uploadedFileDetails <- repository.findByReference(Reference(reference))
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
        uploadedFileDetails.map(_.uploadedDateTime.isDefined) shouldBe Some(false)
      }
    }
    "return 400 when approver pid is the same as requestor pid" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "12345678"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "INVALID_PID"
    }
    "return 400 when approver email is invalid" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1 smith@hmrc.gov.uk",
          "approverPID" -> "23456789"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "INVALID_APPROVER_EMAIL"
    }
    "return 400 when missing approver name, email and pid" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "APPROVER_FIELDS_MISSING"
    }
    "return 400 when updating status to approved but current status is not stored" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "23456789"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = SCANNED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "INVALID_STATUS_TRANSITION"
    }
    "return 400 when missing error code and message" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "uploadRejected"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = INITIAL))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "ERROR_FIELDS_MISSING"
    }
    "return 400 when updating status to upload rejected but current status is not initial" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "uploadRejected",
          "errorCode" -> "error code",
          "errorMessage" -> "error message"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = STORED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "INVALID_STATUS_TRANSITION"
    }
    "return 400 when updating status to uploaded but current status is not initial" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "uploaded"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = FAILED))
        result <- route(app, request).get
      } yield result
        
      status(resultF) shouldBe BAD_REQUEST
    }
    "return 400 when updating status to rejected but current status is not stored" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "rejected"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = SCANNED))
        result <- route(app, request).get
      } yield result
      
      status(resultF) shouldBe BAD_REQUEST
    }
    "return 400 when updating status is the same as current status" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "12345678"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = APPROVED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "ALREADY_AT_STATUS"
    }
    "return 400 when updating status is not recognised" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "weird status",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "12345678"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")
      val resultF = for {
        _ <- repository.createFileRecord(initiateUploadDetails.copy(status = APPROVED))
        result <- route(app, request).get
      } yield result
      status(resultF) shouldBe BAD_REQUEST
      (contentAsJson(resultF) \ "errorCode").as[String] shouldBe "INVALID_STATUS"
    }
    "return 400 when file reference doesn't exist in mongoDB" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url)
        .withJsonBody(Json.obj(
          "status" -> "Approved",
          "approverName" -> "Approver Name",
          "approverEmail" -> "approver1@hmrc.gov.uk",
          "approverPID" -> "12345678"
        ))
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get
      status(result) shouldBe BAD_REQUEST
      (contentAsJson(result) \ "errorCode").as[String] shouldBe "INVALID_FILE_REF"
    }
    "return 400 for non-JSON body" in {
      val request = FakeRequest(PUT, routes.StatusController.updateStatus(reference).url).withTextBody("not json")
      val result = route(app, request).get
      status(result) shouldBe UNSUPPORTED_MEDIA_TYPE
    }
  }
