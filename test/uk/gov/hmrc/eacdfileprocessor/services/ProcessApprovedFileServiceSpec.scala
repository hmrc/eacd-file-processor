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
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.ObjectStoreFileNotFoundException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import scala.concurrent.{ExecutionContext, Future}

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

class ProcessApprovedFileServiceSpec extends TestSupport with TestData with UnitSpec:

  private val mockAppConfig = mock[AppConfig]
  private val objectStoreClient = mock[PlayObjectStoreClient]
  private val mockLockService: LockService = mock[LockService]
  private val executionContext = ec
  private val materializer = mock[Materializer]

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
    val mockFileRepository = mock[FileRepository]
    val deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository = mock[DeEnrolmentWorkItemRepository]
    val processApprovedFileService = new ProcessApprovedFileService {
      override val appConfig = mockAppConfig
      override val osClient = objectStoreClient
      override val fileRepository = mockFileRepository
      override val workItemRepository = deEnrolmentWorkItemRepository
      override val lockService = mockLockService
      override implicit val ec: ExecutionContext = executionContext
      override implicit val mat: Materializer = materializer
      override implicit val hc: HeaderCarrier = HeaderCarrier()
    }

    private val serviceLogger = LoggerFactory.getLogger(processApprovedFileService.getClass).asInstanceOf[Logger]

    def capturedLogMessages(block: => Unit): Seq[String] = {
      val originalLevel = serviceLogger.getLevel
      val appender = new ListAppender[ILoggingEvent]()
      appender.start()
      serviceLogger.setLevel(Level.WARN)
      serviceLogger.addAppender(appender)
      try {
        block
        appender.list.toArray(new Array[ILoggingEvent](0)).toSeq.map(_.getFormattedMessage)
      } finally {
        serviceLogger.detachAppender(appender)
        serviceLogger.setLevel(originalLevel)
        appender.stop()
      }
    }
  }

  "ProcessApprovedFileService" must {
    "getOldestFileFromObjectStore" must {
      "totalEntryCount is set when save record details into DeEnrolmentWorkItem successfully" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        when(deEnrolmentWorkItemRepository.saveRecordDetails(any(), any())).thenReturn(Future.successful(deEnrolmentWorkItems))
        await(processApprovedFileService.createWorkItemsFromOldestFile)
        verify(deEnrolmentWorkItemRepository, times(1)).saveRecordDetails(any(), any())
        verify(mockFileRepository, times(1)).setTotalEntryCount(any(), any())
      }
      "not save record details into DeEnrolmentWorkItem when there is no approved file" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(None))
        processApprovedFileService.createWorkItemsFromOldestFile
        verify(deEnrolmentWorkItemRepository, times(0)).saveRecordDetails(any(), any())
        verify(mockFileRepository, times(0)).setTotalEntryCount(any(), any())
      }
      "totalEntryCount is not insert when save record details into DeEnrolmentWorkItem failed" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        when(deEnrolmentWorkItemRepository.saveRecordDetails(any(), any())).thenReturn(Future(throw new RuntimeException(s"Only 1 items were saved")))
        await(processApprovedFileService.createWorkItemsFromOldestFile)
        verify(deEnrolmentWorkItemRepository, times(1)).saveRecordDetails(any(), any())
        verify(mockFileRepository, times(0)).setTotalEntryCount(any(), any())
      }
      "throw RuntimeException when can't find file name" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(Some(failedUploadedDetails.copy(status = APPROVED))))

        val exception = intercept[RuntimeException] {
          await(processApprovedFileService.createWorkItemsFromOldestFile)
        }

        exception.getMessage contains s"Can't find file name for reference: ${failedUploadedDetails.reference.value}" shouldBe true
      }
      "throw RuntimeException when file details is missing" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(Some(initiateUploadDetails.copy(status = APPROVED))))

        val exception = intercept[RuntimeException] {
          await(processApprovedFileService.createWorkItemsFromOldestFile)
        }

        exception.getMessage contains s"Can't find file name for reference: ${initiateUploadDetails.reference.value}" shouldBe true
      }
      "throw ObjectStoreFileNotFoundException when file can't be found in object store" in new Setup {
        when(mockFileRepository.findOldestApprovedFile).thenReturn(Future(Some(scannedUploadedDetails.copy(status = APPROVED))))
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(None))

        val exception = intercept[ObjectStoreFileNotFoundException] {
          await(processApprovedFileService.createWorkItemsFromOldestFile)
        }

        exception.getMessage contains s"No record found for the requested reference: ${scannedUploadedDetails.reference.value} in Object Store" shouldBe true
      }
    }

    "checkRecordsWithStaleFileStatus" must {
      "return Future.unit and call findFilesWithStaleStatus with UPLOADED and APPROVED statuses when no stale records exist" in new Setup {
        when(mockFileRepository.findFilesWithStaleStatus(Seq(UPLOADED, APPROVED)))
          .thenReturn(Future.successful(Seq.empty))

        await(processApprovedFileService.checkRecordsWithStaleFileStatus)

        verify(mockFileRepository, times(1)).findFilesWithStaleStatus(Seq(UPLOADED, APPROVED))
      }

      "log NO_UPSCAN_CALLBACK when stale UPLOADED records are found" in new Setup {
        val staleUploadedRecord = initiateUploadDetails.copy(
          status = UPLOADED,
          lastUpdatedDateTime = Some(Instant.now().minus(5, HOURS))
        )
        when(mockFileRepository.findFilesWithStaleStatus(Seq(UPLOADED, APPROVED)))
          .thenReturn(Future.successful(Seq(staleUploadedRecord)))

        val logMessages = capturedLogMessages {
          await(processApprovedFileService.checkRecordsWithStaleFileStatus)
        }

        verify(mockFileRepository, times(1)).findFilesWithStaleStatus(Seq(UPLOADED, APPROVED))
        logMessages.exists(_.contains(s"NO_UPSCAN_CALLBACK for file reference ${staleUploadedRecord.reference}")) shouldBe true
      }

      "log FILE_NOT_COLLECTED when stale APPROVED records are found" in new Setup {
        val staleApprovedRecord = initiateUploadDetails.copy(
          status = APPROVED,
          lastUpdatedDateTime = Some(Instant.now().minus(5, HOURS))
        )
        when(mockFileRepository.findFilesWithStaleStatus(Seq(UPLOADED, APPROVED)))
          .thenReturn(Future.successful(Seq(staleApprovedRecord)))

        val logMessages = capturedLogMessages {
          await(processApprovedFileService.checkRecordsWithStaleFileStatus)
        }

        verify(mockFileRepository, times(1)).findFilesWithStaleStatus(Seq(UPLOADED, APPROVED))
        logMessages.exists(_.contains(s"FILE_NOT_COLLECTED for file reference ${staleApprovedRecord.reference}")) shouldBe true
      }

      "return Future.unit when a mix of stale UPLOADED and APPROVED records are found" in new Setup {
        val staleUploadedRecord = initiateUploadDetails.copy(
          status = UPLOADED,
          lastUpdatedDateTime = Some(Instant.now().minus(6, HOURS))
        )
        val staleApprovedRecord = scannedUploadedDetails.copy(
          status = APPROVED,
          lastUpdatedDateTime = Some(Instant.now().minus(5, HOURS))
        )
        when(mockFileRepository.findFilesWithStaleStatus(Seq(UPLOADED, APPROVED)))
          .thenReturn(Future.successful(Seq(staleUploadedRecord, staleApprovedRecord)))

        await(processApprovedFileService.checkRecordsWithStaleFileStatus)

        verify(mockFileRepository, times(1)).findFilesWithStaleStatus(Seq(UPLOADED, APPROVED))
      }
    }
  }
