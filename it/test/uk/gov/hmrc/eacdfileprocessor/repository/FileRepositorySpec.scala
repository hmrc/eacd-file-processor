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

import org.bson.types.ObjectId
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.DuplicateReferenceException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.mongo.logging.ObservableFutureImplicits._
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{APPROVED, FAILED, SCANNED, STORED}
import uk.gov.hmrc.eacdfileprocessor.models.*

import java.time.Instant

class FileRepositorySpec extends TestSupport with TestData:
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.timeToLive).thenReturn("3")
  lazy val repository: FileRepository = app.injector.instanceOf[FileRepository]

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
      "approvedAtDateTime is None by default on creation" in {
        await(repository.createFileRecord(initiateUploadDetails))
        val actual = await(repository.findByReference(initiateUploadDetails.reference)).get
        actual.approvedAtDateTime shouldBe None
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
        await(repository.updateStatusAndDetails(reference, FAILED, failedFileDetails))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = FAILED, details = Some(failedFileDetails), lastUpdatedDateTime = actual.lastUpdatedDateTime)
      }
      "correctly update status and successful details" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndDetails(reference, SCANNED, successfulUploadedDetails))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = SCANNED, details = Some(successfulUploadedDetails), lastUpdatedDateTime = actual.lastUpdatedDateTime)
      }
      "correctly update status" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatus(reference, STORED))

        val actual = await(repository.findByReference(reference)).get
        actual shouldBe initiateUploadDetails.copy(status = STORED, lastUpdatedDateTime = actual.lastUpdatedDateTime)
      }
      "correctly update status approver details and uploadedDateTime" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndApproverDetails(reference, APPROVED, approverDetails, true, Instant.parse("2024-01-01T00:00:00Z")))

        val actual = await(repository.findByReference(reference)).get
        val expected = initiateUploadDetails.copy(status = APPROVED, approverDetails = Some(approverDetails), uploadedDateTime = actual.uploadedDateTime, lastUpdatedDateTime = actual.lastUpdatedDateTime, approvedAtDateTime = Some(Instant.parse("2024-01-01T00:00:00Z")))
        actual shouldBe expected
        actual.uploadedDateTime should not be None
      }
      "correctly update status and approver details" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndApproverDetails(reference, APPROVED, approverDetails, false, Instant.parse("2024-01-01T00:00:00Z")))

        val actual = await(repository.findByReference(reference)).get
        val expected = initiateUploadDetails.copy(status = APPROVED, approverDetails = Some(approverDetails), lastUpdatedDateTime = actual.lastUpdatedDateTime, approvedAtDateTime = Some(Instant.parse("2024-01-01T00:00:00Z")))
        actual shouldBe expected
        actual.uploadedDateTime shouldBe None
      }
      "approvedAtDateTime is unchanged when updating other fields" in {
        val reference = initiateUploadDetails.reference
        await(repository.createFileRecord(initiateUploadDetails))
        await(repository.updateStatusAndApproverDetails(reference, APPROVED, approverDetails, false, Instant.parse("2024-01-01T00:00:00Z")))
        val before = await(repository.findByReference(reference)).get
        await(repository.updateStatus(reference, STORED))
        val after = await(repository.findByReference(reference)).get
        after.approvedAtDateTime shouldBe before.approvedAtDateTime
      }
    }

    "find file by status" when {
      "there is a file matching the file status" in {
        val status: FileStatus = initiateUploadDetails.status
        await(repository.createFileRecord(initiateUploadDetails.copy()))


        val actual = await(repository.findByStatus(status))
        actual shouldBe Seq(statusDetailsModel.copy(fileName = None, fileStatus = initiateUploadDetails.status.value, creationDateTime = None))

      }

      "there is no file matching the file status" in {
        await(repository.createFileRecord(initiateUploadDetails.copy()))

        val actual = await(repository.findByStatus(FileStatus.STORED))
        actual shouldBe Seq.empty[StatusDetailsModel]
      }

      "there are multiple files matching the file status" in {
        val status: FileStatus = initiateUploadDetails.status
        await(repository.createFileRecord(initiateUploadDetails.copy(reference = Reference("ref3"), id = ObjectId("6974a038d540b44c4403aee3"))))
        await(repository.createFileRecord(initiateUploadDetails.copy(reference = Reference("ref4"), id = ObjectId("6984a038d540b44c4403aee3"))))

        val actual = await(repository.findByStatus(status))
        actual shouldBe Seq(
          statusDetailsModel.copy(reference = "ref3", fileName = None, fileStatus = initiateUploadDetails.status.value, creationDateTime = None),
          statusDetailsModel.copy(reference = "ref4", fileName = None, fileStatus = initiateUploadDetails.status.value, creationDateTime = None)
        )
      }

      "there are multiple files with different file status" in {
        await(repository.createFileRecord(initiateUploadDetails.copy(reference = Reference("ref5"), id = ObjectId("6976a038d540b44c4403aee3"))))
        await(repository.createFileRecord(initiateUploadDetails.copy(reference = Reference("ref6"), status = FileStatus.STORED, id = ObjectId("6975a038d540b44c4403aee3"))))

        val actual = await(repository.findByStatus(initiateUploadDetails.copy(reference = Reference("ref5")).status))
        actual shouldBe Seq(statusDetailsModel.copy(reference = "ref5", fileName = None, fileStatus = initiateUploadDetails.status.value, creationDateTime = None))
      }
    }
  }