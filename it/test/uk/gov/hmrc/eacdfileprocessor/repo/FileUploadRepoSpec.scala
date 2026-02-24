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

package uk.gov.hmrc.eacdfileprocessor.repo

import org.bson.types.ObjectId
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.upscan.{Details, Reference, UploadedDetails}

import java.time.Instant
import scala.concurrent.ExecutionContext

class FileUploadRepoSpec extends TestSupport with TestData:
  implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.timeToLive).thenReturn("3")
  lazy val repository = app.injector.instanceOf[FileUploadRepo]

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "repository" can {
    "save a uploaded details" when {
      "correct details has been supplied" in {
        await(repository.insert(failedUploadedDetails))

        val actual = await(repository.findByReference(Reference("747c4a0c-a442-4c72-baeb-efb758462602"))).get
        actual shouldBe failedUploadedDetails
      }
      "missing reference" in {
        val missingRefUploadDetails = failedUploadedDetails.copy(reference = Reference(null))
        val exception = intercept[IllegalStateException] {
          await(repository.insert(missingRefUploadDetails))
        }

        exception.getMessage contains "Value can not be null" shouldBe true
      }
      "correctly update status" in {
        val reference = Reference("3b8f08a6-c1fd-45d4-9af0-94a583b505cf")
        await(repository.insert(scannedUploadedDetails))
        await(repository.updateStatus(reference, "stored"))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe scannedUploadedDetails.copy(status = "stored", createdAt = actual.createdAt)
      }
    }
  }