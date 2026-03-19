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

import org.bson.types.ObjectId
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.models.{Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class InitiateFileStorageControllerISpec
  extends TestSupport with DefaultAwaitTimeout {
  implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  lazy val repository = app.injector.instanceOf[FileRepository]

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  private def createInitialFile =
    await(repository.createFileRecord(
      UploadedDetails(
        id = ObjectId("6994a038d540b44c4403aee3"),
        reference = Reference("ref-dup-1"),
        status = "initial",
        requestorPID = "12345678",
        requestorEmail = "test@hmrc.gov.uk",
        requestorName = "Test User",
        createdAt = Instant.now()
      ))
    )

  "POST /initiate (integration)" should {
    "return 400 for missing fields" in {
      val request = FakeRequest(POST, "/eacd-file-processor/initiate").withJsonBody(Json.obj("reference" -> "ref1"))
      val result = route(app, request).get
      status(result) shouldBe BAD_REQUEST
      (contentAsJson(result) \ "errorCode").as[String] shouldBe "MANDATORY_FIELDS_MISSING"
    }

    "return 400 for invalid email" in {
      val json = Json.obj(
        "reference" -> "ref1",
        "requestorPID" -> "pid1",
        "requestorEmail" -> "invalid-email",
        "requestorName" -> "Test User"
      )
      val request = FakeRequest(POST, "/eacd-file-processor/initiate").withJsonBody(json)
      val result = route(app, request).get
      status(result) shouldBe BAD_REQUEST
      (contentAsJson(result) \ "errorCode").as[String] shouldBe "INVALID_REQUESTOR_EMAIL"
    }

    "return 400 for duplicate reference" in {
      val json = Json.obj(
        "reference" -> "ref-dup-1",
        "requestorPID" -> "pid-dup-1",
        "requestorEmail" -> "dup@example.com",
        "requestorName" -> "Dup User"
      )
      val request = FakeRequest(POST, "/eacd-file-processor/initiate").withJsonBody(json)
      val result = for {
        _ <- Future(createInitialFile) // Insert initial record with reference "ref-dup-1"
        resultF <- route(app, request).get
      } yield resultF

      status(result) shouldBe BAD_REQUEST
      (contentAsJson(result) \ "errorCode").as[String] shouldBe "DUPLICATE_EXTERNAL_FILE_REF"
    }

    "return 400 for non-JSON body" in {
      val request = FakeRequest(POST, "/eacd-file-processor/initiate").withTextBody("not json")
      val result = route(app, request).get
      status(result) shouldBe UNSUPPORTED_MEDIA_TYPE
    }

    "store all fields, fileStatus as 'initial', and creationDateTime in Mongo for valid request" in {
      val json = Json.obj(
        "reference" -> "ref-integration-2",
        "requestorPID" -> "pid-integration-2",
        "requestorEmail" -> "integration2@example.com",
        "requestorName" -> "Integration User 2"
      )
      val request = FakeRequest(POST, "/eacd-file-processor/initiate")
        .withJsonBody(json)
        .withHeaders("Authorization" -> "Bearer test-token")
      val result = route(app, request).get
      status(result) shouldBe CREATED

      val docOpt = await(repository.findByReference(Reference("ref-integration-2")))
      docOpt should not be empty
      val uploadedDetails = docOpt.get

      uploadedDetails.reference.value shouldBe "ref-integration-2"
      uploadedDetails.requestorPID shouldBe "pid-integration-2"
      uploadedDetails.requestorEmail shouldBe "integration2@example.com"
      uploadedDetails.requestorName shouldBe "Integration User 2"
      uploadedDetails.status shouldBe "initial"
      uploadedDetails.createdAt.isBefore(Instant.now()) shouldBe true
    }
  }
}
