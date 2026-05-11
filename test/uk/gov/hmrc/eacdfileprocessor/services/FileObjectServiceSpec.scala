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

package uk.gov.hmrc.eacdfileprocessor.services

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.ObjectStoreFileNotFoundException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Future

class FileObjectServiceSpec extends TestSupport with TestData with UnitSpec:
  implicit val mat: Materializer = mock[Materializer]
  private val mockAppConfig = mock[AppConfig]
  private val objectStoreClient = mock[PlayObjectStoreClient]

  val path = java.nio.file.Path.of("test", "resources", "bulk_de_enrolment2.csv")
  val content = Files.readString(path, StandardCharsets.UTF_8)
  val o: Object[String] = Object(
    location = Path.Directory(scannedUploadedDetails.reference.value).file("bulk_de_enrolment2.csv"),
    content = content,
    metadata = ObjectMetadata(
      contentType = ".csv",
      contentLength = content.length,
      contentMd5 = Md5Hash("somemd5"),
      lastModified = Instant.now(),
      userMetadata = Map.empty
    )
  )

  trait Setup {
    val fileRepository = mock[FileRepository]
    val deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository = mock[DeEnrolmentWorkItemRepository]
    val fileObjectService = FileObjectService(mockAppConfig, objectStoreClient, fileRepository, deEnrolmentWorkItemRepository)
  }

  "FileObjectService" must {
    "getOldestFileFromObjectStore" must {
      "totalEntryCount is set when save record details into DeEnrolmentWorkItem successfully" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        when(deEnrolmentWorkItemRepository.saveRecordDetails(any(), any())).thenReturn(Future.successful(deEnrolmentWorkItems))
        await(fileObjectService.getOldestFileFromObjectStore)
        verify(deEnrolmentWorkItemRepository, times(1)).saveRecordDetails(any(), any())
        verify(fileRepository, times(1)).setTotalEntryCount(any(), any())
      }
      "not save record details into DeEnrolmentWorkItem when there is no approved file" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(None))
        fileObjectService.getOldestFileFromObjectStore
        verify(deEnrolmentWorkItemRepository, times(0)).saveRecordDetails(any(), any())
        verify(fileRepository, times(0)).setTotalEntryCount(any(), any())
      }
      "totalEntryCount is not insert when save record details into DeEnrolmentWorkItem failed" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        when(deEnrolmentWorkItemRepository.saveRecordDetails(any(), any())).thenReturn(Future(throw new RuntimeException(s"Only 1 items were saved")))
        await(fileObjectService.getOldestFileFromObjectStore)
        verify(deEnrolmentWorkItemRepository, times(1)).saveRecordDetails(any(), any())
        verify(fileRepository, times(0)).setTotalEntryCount(any(), any())
      }
      "throw RuntimeException when can't find file name" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(Some(failedUploadedDetails.copy(status = APPROVED))))

        val exception = intercept[RuntimeException] {
          await(fileObjectService.getOldestFileFromObjectStore)
        }

        exception.getMessage contains s"Can't find file name for reference: ${failedUploadedDetails.reference.value}" shouldBe true
      }
      "throw RuntimeException when file details is missing" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(Some(initiateUploadDetails.copy(status = APPROVED))))

        val exception = intercept[RuntimeException] {
          await(fileObjectService.getOldestFileFromObjectStore)
        }

        exception.getMessage contains s"Can't find file name for reference: ${initiateUploadDetails.reference.value}" shouldBe true
      }
      "throw ObjectStoreFileNotFoundException when file can't be found in object store" in new Setup {
        when(fileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(None))

        val exception = intercept[ObjectStoreFileNotFoundException] {
          await(fileObjectService.getOldestFileFromObjectStore)
        }

        exception.getMessage contains s"No record found for the requested reference: ${scannedUploadedDetails.reference.value} in Object Store" shouldBe true
      }
    }
  }

