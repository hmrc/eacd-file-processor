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
        approverDetails = Some(approverDetails),
        approvedAtDateTime = Some(createdAt),
        totalEntryCount = Some(100),
        totalSuccessCount = Some(95),
        totalFailureCount = Some(5)
      )

      val resultF = for {
        _ <- fileRepository.createFileRecord(withApprover)
        result <- route(app, request).get
      } yield result

      status(resultF) shouldBe 200

      val json = Json.parse(contentAsString(resultF))
      
      (json \ "_id" \ "$oid").asOpt[String] shouldBe Some("6994a038d540b44c4403aee4")

      (json \ "reference" \ "value").asOpt[String] shouldBe Some(reference)

      (json \ "creationDateTime" \ "$date" \ "$numberLong").asOpt[String] shouldBe Some("1771418638342")
      (json \ "approvedAtDateTime" \ "$date" \ "$numberLong").asOpt[String] shouldBe Some("1771418638342")
    }
  }
}