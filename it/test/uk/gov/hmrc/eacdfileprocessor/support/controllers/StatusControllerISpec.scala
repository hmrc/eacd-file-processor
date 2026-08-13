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
import org.bson.types.ObjectId
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers.{GET, await, contentAsJson, route, status, writeableOf_AnyContentAsJson}
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.Reference

import java.time.Instant.now
import java.time.temporal.ChronoUnit.DAYS
import scala.concurrent.Future

class StatusControllerISpec extends TestData with DefaultAwaitTimeout with IntegrationSpec with Eventually:

  val reference = "08aad019-7f66-4456-8d52-93f12109876f"

  override def beforeEach(): Unit = {
    await(fileRepository.dropCollection())
    await(fileRepository.ensureIndexes())
  }

  "GET /file-status-count" should {
    "return 204 when no file within fileExpiryDays" in {
      val request = FakeRequest(GET, routes.StatusController.getAllStatusCounts.url)
        .withJsonBody(Json.obj())
        .withHeaders("Authorization" -> "Bearer test-token")
      val result = route(app, request).get
      status(result) shouldBe NO_CONTENT
    }
    "return 200 when some file status found" in {
      val request = FakeRequest(GET, routes.StatusController.getAllStatusCounts.url)
        .withJsonBody(Json.obj())
        .withHeaders("Authorization" -> "Bearer test-token")
      eventually {
        await(fileRepository.createFileRecord(scannedUploadedDetails.copy(lastUpdatedDateTime = Some(now().minus(20, DAYS)))))
        await(fileRepository.createFileRecord(failedUploadedDetails.copy(reference = Reference("ref1"), lastUpdatedDateTime = Some(now().minus(5, DAYS)))))
        await(fileRepository.createFileRecord(scannedUploadedDetails.copy(id = ObjectId.get(), reference = Reference("ref3"), status = PROCESSING, lastUpdatedDateTime = Some(now().minus(90, DAYS)))))
        await(fileRepository.createFileRecord(scannedUploadedDetails.copy(id = ObjectId.get(), reference = Reference("ref4"), status = PROCESSEDWITHCOUNTMISMATCH, lastUpdatedDateTime = Some(now().minus(10, DAYS)))))
        val result = route(app, request).get
        status(result) shouldBe OK
        contentAsJson(result) shouldBe expectedFileStatusCounts
      }
    }
  }

  val expectedFileStatusCounts: JsValue = Json.parse(
    """
      |[
      |  {
      |    "status": "uploadRejected",
      |    "count": 0
      |  },
      |  {
      |    "status": "uploaded",
      |    "count": 0
      |  },
      |  {
      |    "status": "failed",
      |    "count": 1
      |  },
      |  {
      |    "status": "scanned",
      |    "count": 1
      |  },
      |  {
      |    "status": "stored",
      |    "count": 0
      |  },
      |  {
      |    "status": "rejected",
      |    "count": 0
      |  },
      |  {
      |    "status": "approved",
      |    "count": 0
      |  },
      |  {
      |    "status": "processing",
      |    "count": 0
      |  },
      |  {
      |    "status": "processedWithErrors",
      |    "count": 0
      |  },
      |  {
      |    "status": "processedSuccessfully",
      |    "count": 0
      |  },
      |  {
      |    "status": "processedWithCountMismatch",
      |    "count": 1
      |  }
      |]
      |""".stripMargin
  )
