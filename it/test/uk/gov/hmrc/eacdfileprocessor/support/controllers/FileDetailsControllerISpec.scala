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

import helper.IntegrationSpec
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.libs.json.Json
import play.api.test.Helpers.{GET, await, contentAsString, route, status, writeableOf_AnyContentAsEmpty}
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED
import uk.gov.hmrc.eacdfileprocessor.models.{ApproverDetails, Reference}

import java.time.Instant

class FileDetailsControllerISpec extends IntegrationSpec with TestData with DefaultAwaitTimeout {

  val reference = "08aad019-7f66-4456-8d52-93f12109876f"

  override def beforeEach(): Unit = {
    await(fileRepository.dropCollection())
    await(fileRepository.ensureIndexes())
  }

  "GET /eacd-file-processor/support-tool/file-detail/:reference (integration)" should {

    "return 200 OK with file detail when a scanned record exists" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val resultF = for {
        _ <- fileRepository.createFileRecord(scannedUploadedDetails.copy(reference = Reference(reference)))
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200
    }

    "return 200 OK with file detail when a failed record exists" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val resultF = for {
        _ <- fileRepository.createFileRecord(failedUploadedDetails.copy(reference = Reference(reference)))
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200
    }

    "return 200 OK with file detail when an approved record with approver details exists" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val withApprover = scannedUploadedDetails.copy(
        reference = Reference(reference),
        status = APPROVED,
        approverDetails = Some(approverDetails),
        approvedAtDateTime = Some(Instant.now()),
        totalEntryCount = Some(100),
        totalSuccessCount = Some(95),
        totalFailureCount = Some(5)
      )

      val resultF = for {
        _ <- fileRepository.createFileRecord(withApprover)
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200
    }

    "return 200 OK and the response body contains the reference" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val resultF = for {
        _ <- fileRepository.createFileRecord(scannedUploadedDetails.copy(reference = Reference(reference)))
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200
      contentAsString(resultF) must include(reference)
    }

    "return 204 NoContent when the reference does not exist in MongoDB" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/unknown-ref")
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get
      status(result) shouldBe 204
    }

    "return 204 NoContent when the collection is empty" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get
      status(result) shouldBe 204
    }
    "return a response body with clean JSON format" in {
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/file-detail/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val withApprover = scannedUploadedDetails.copy(
        reference = Reference(reference),
        status = APPROVED,
        approverDetails = Some(ApproverDetails(
          approverEmail = Some("approverTest@hmrc.gov.uk"),
          approverPID = Some("12345678"),
          approverName = Some("Approver1"),
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )),
        approvedAtDateTime = Some(createdAt),
        totalEntryCount = Some(100),
        uploadedDateTime = None,
        lastUpdatedDateTime = None,
        totalSuccessCount = Some(95),
        totalFailureCount = Some(5)
      )

      val resultF = for {
        _ <- fileRepository.createFileRecord(withApprover)
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200

      val json = Json.parse(contentAsString(resultF))

      (json \ "id").asOpt[String] shouldBe Some(withApprover.id.toHexString)
      (json \ "reference").asOpt[String] shouldBe Some(reference)
      (json \ "status").asOpt[String] shouldBe Some("approved")
      (json \ "requestorPID").asOpt[String] shouldBe Some("12345678")
      (json \ "requestorEmail").asOpt[String] shouldBe Some("test@hmrc.gov.uk")
      (json \ "requestorName").asOpt[String] shouldBe Some("Test User")

      (json \ "details" \ "name").asOpt[String] shouldBe Some("bulk-de-enrol.csv")
      (json \ "details" \ "mimeType").asOpt[String] shouldBe Some("text/csv")
      (json \ "details" \ "downloadUrl").asOpt[String] shouldBe Some("http://localhost:9570/upscan/download/c5da3bd6-f118-4cde-afff-93f763bf6448")
      (json \ "details" \ "size").asOpt[Long] shouldBe Some(32270L)
      (json \ "details" \ "checksum").asOpt[String] shouldBe Some("a0acaa6039c1a94c6f5c43f144c5add07de9381f98701cb14c7c6ce2be18020b")

      (json \ "approverDetails" \ "approverEmail").asOpt[String] shouldBe Some("approverTest@hmrc.gov.uk")
      (json \ "approverDetails" \ "approverPID").asOpt[String] shouldBe Some("12345678")
      (json \ "approverDetails" \ "approverName").asOpt[String] shouldBe Some("Approver1")
      (json \ "approverDetails" \ "errorCode").asOpt[String] shouldBe Some("error code")
      (json \ "approverDetails" \ "errorMessage").asOpt[String] shouldBe Some("error message")

      (json \ "totalEntryCount").asOpt[Int] shouldBe Some(100)
      (json \ "totalSuccessCount").asOpt[Int] shouldBe Some(95)
      (json \ "totalFailureCount").asOpt[Int] shouldBe Some(5)

      (json \ "uploadedDateTime").asOpt[String] shouldBe None
      (json \ "lastUpdatedDateTime").asOpt[String] shouldBe None
      (json \ "approvedAtDateTime").asOpt[String] shouldBe Some("2026-02-18T12:43:58.342Z")
      (json \ "creationDateTime").asOpt[String] shouldBe Some("2026-02-18T12:43:58.342Z")

      (json \ "_id").asOpt[String] shouldBe None
      (json \ "reference" \ "value").asOpt[String] shouldBe None
      (json \ "creationDateTime" \ "$date").asOpt[String] shouldBe None
    }
  }
}