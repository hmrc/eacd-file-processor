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

package uk.gov.hmrc.eacdfileprocessor.repository

import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.DuplicateReferenceException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.{Reference, UploadedDetails}

import scala.concurrent.ExecutionContext

class FileRepositorySpec extends TestSupport with TestData:
  implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.timeToLive).thenReturn("3")
  lazy val repository = app.injector.instanceOf[FileRepository]

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "repository" can {
    "save a uploaded details" when {
      "correct details has been supplied" in {
        await(repository.createFileRecord(initiateUploadDetails))

        val actual = await(repository.findByReference(Reference("08aad019-7f66-4456-8d52-93f12109876f"))).get
        actual shouldBe initiateUploadDetails
      }
      "missing reference" in {
        val missingRefUploadDetails = initiateUploadDetails.copy(reference = Reference(null))
        val exception = intercept[IllegalStateException] {
          await(repository.createFileRecord(missingRefUploadDetails))
        }

        exception.getMessage contains "Value can not be null" shouldBe true
      }
      "duplicate reference" in {
        await(repository.createFileRecord(initiateUploadDetails))
        val exception = intercept[DuplicateReferenceException] {
          await(repository.createFileRecord(initiateUploadDetails))
        }

        exception.getMessage contains "Duplicate external file reference" shouldBe true
      }
      "correctly update status and failed details" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndDetails(reference, "failed", failedUploadedDetails))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = "failed", details = Some(failedUploadedDetails), createdAt = actual.createdAt)
      }
      "correctly update status and successful details" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndDetails(reference, "scanned", scannedUploadedDetails))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = "scanned", details = Some(scannedUploadedDetails), createdAt = actual.createdAt)
      }
      "correctly update status" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatus(reference, "stored"))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = "stored", createdAt = actual.createdAt)
      }
    }
  }