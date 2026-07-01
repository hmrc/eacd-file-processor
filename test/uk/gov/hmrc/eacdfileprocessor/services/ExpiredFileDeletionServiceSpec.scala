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

import com.mongodb.client.result.DeleteResult.acknowledged
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.{Details, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{FAILED, INITIAL, STORED}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.io.FileNotFoundException
import scala.concurrent.{ExecutionContext, Future}

class ExpiredFileDeletionServiceSpec extends TestSupport with TestData with UnitSpec {

  trait Setup {
    val appConfig: AppConfig = mock[AppConfig]
    val fileRepository: FileRepository = mock[FileRepository]
    val osClient: PlayObjectStoreClient = mock[PlayObjectStoreClient]
    val emailService: EmailService = mock[EmailService]

    when(appConfig.appName).thenReturn("eacd-file-processor")

    val lockService: LockService = new LockService(null) {
      override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
        f.map(Left(_))
    }

    val service = new ExpiredFileDeletionService(
      appConfig = appConfig,
      fileRepository = fileRepository,
      osClient = osClient,
      lockService = lockService,
      emailService = emailService
    )
  }

  "ExpiredFileDeletionService" should {

    "skip processing when lock is already held" in new Setup {
      val lockedLockService: LockService = new LockService(null) {
        override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
          Future.successful(Right(MongoLocked))
      }

      val lockedService = new ExpiredFileDeletionService(
        appConfig = appConfig,
        fileRepository = fileRepository,
        osClient = osClient,
        lockService = lockedLockService,
        emailService = emailService
      )

      val result: Either[Unit, LockResponse] = await(lockedService.invoke)

      result shouldBe Right(MongoLocked)
      verify(fileRepository, never()).deleteExpiredInitialFiles
      verify(fileRepository, never()).findExpiredActiveFiles
    }

    "delete expired initial records directly from mongo" in new Setup {
      val expiredInitial: UploadedDetails = initiateUploadDetails.copy(status = INITIAL)
      when(fileRepository.deleteExpiredInitialFiles).thenReturn(Future.successful(acknowledged(1)))
      when(fileRepository.findExpiredActiveFiles).thenReturn(Future.successful(Seq.empty))

      val result: Either[Unit, LockResponse] = await(service.invoke)

      result shouldBe Left(())
      verify(fileRepository).deleteExpiredInitialFiles
      verify(osClient, never()).deleteObject(any(), any())(any())
    }

    "delete expired active files from object store and then mongo" in new Setup {
      val fileName = "expired.csv"
      val expiredActive: UploadedDetails = failedUploadedDetails.copy(
        status = STORED,
        details = Some(
          Details.UploadedSuccessfully(
            name = fileName,
            mimeType = "text/csv",
            downloadUrl = null,
            size = Some(10L),
            checksum = "abc"
          )
        )
      )

      when(fileRepository.deleteExpiredInitialFiles).thenReturn(Future.successful(acknowledged(0)))
      when(fileRepository.findExpiredActiveFiles).thenReturn(Future.successful(Seq(expiredActive)))
      when(osClient.deleteObject(any(), any())(any())).thenReturn(Future.successful(()))
      when(fileRepository.deleteByReference(expiredActive.reference)).thenReturn(Future.successful(true))

      val result: Either[Unit, LockResponse] = await(service.invoke)

      result shouldBe Left(())
      verify(osClient).deleteObject(
        eqTo(Path.Directory(expiredActive.reference.value).file(fileName)),
        eqTo("eacd-file-processor")
      )(any())
      verify(fileRepository).deleteByReference(expiredActive.reference)
      verify(emailService).sendFileAutoDeletedEmail(any(), any())(any())
    }

    "delete expired active files from object store but fail deleting from mongo and no email is sent" in new Setup {
      val fileName = "expired.csv"
      val expiredActive: UploadedDetails = failedUploadedDetails.copy(
        status = STORED,
        details = Some(
          Details.UploadedSuccessfully(
            name = fileName,
            mimeType = "text/csv",
            downloadUrl = null,
            size = Some(10L),
            checksum = "abc"
          )
        )
      )

      when(fileRepository.deleteExpiredInitialFiles).thenReturn(Future.successful(acknowledged(0)))
      when(fileRepository.findExpiredActiveFiles).thenReturn(Future.successful(Seq(expiredActive)))
      when(osClient.deleteObject(any(), any())(any())).thenReturn(Future.successful(()))
      when(fileRepository.deleteByReference(expiredActive.reference)).thenReturn(Future.successful(false))

      val result: Either[Unit, LockResponse] = await(service.invoke)

      result shouldBe Left(())
      verify(osClient).deleteObject(
        eqTo(Path.Directory(expiredActive.reference.value).file(fileName)),
        eqTo("eacd-file-processor")
      )(any())
      verify(fileRepository).deleteByReference(expiredActive.reference)
      verify(emailService, times(0)).sendFileAutoDeletedEmail(any(), any())(any())
    }

    "failed delete expired active files from object store and no deleted from mongo and no email is sent" in new Setup {
      val fileName = "expired.csv"
      val expiredActive: UploadedDetails = failedUploadedDetails.copy(
        status = STORED,
        details = Some(
          Details.UploadedSuccessfully(
            name = fileName,
            mimeType = "text/csv",
            downloadUrl = null,
            size = Some(10L),
            checksum = "abc"
          )
        )
      )

      when(fileRepository.deleteExpiredInitialFiles).thenReturn(Future.successful(acknowledged(0)))
      when(fileRepository.findExpiredActiveFiles).thenReturn(Future.successful(Seq(expiredActive)))
      when(osClient.deleteObject(any(), any())(any())).thenReturn(Future.failed(new FileNotFoundException("Unable to find the file.")))

      val result: Either[Unit, LockResponse] = await(service.invoke)

      result shouldBe Left(())
      verify(osClient).deleteObject(
        eqTo(Path.Directory(expiredActive.reference.value).file(fileName)),
        eqTo("eacd-file-processor")
      )(any())
      verify(fileRepository, times(0)).deleteByReference(expiredActive.reference)
      verify(emailService, times(0)).sendFileAutoDeletedEmail(any(), any())(any())
    }

    "delete mongo record only when active file has no UploadedSuccessfully details" in new Setup {
      val expiredActiveNoFileName: UploadedDetails = failedUploadedDetails.copy(status = FAILED, details = None)

      when(fileRepository.deleteExpiredInitialFiles).thenReturn(Future.successful(acknowledged(0)))
      when(fileRepository.findExpiredActiveFiles).thenReturn(Future.successful(Seq(expiredActiveNoFileName)))
      when(fileRepository.deleteByReference(expiredActiveNoFileName.reference)).thenReturn(Future.successful(true))

      val result: Either[Unit, LockResponse] = await(service.invoke)

      result shouldBe Left(())
      verify(osClient, never()).deleteObject(any(), any())(any())
      verify(fileRepository).deleteByReference(expiredActiveNoFileName.reference)
    }
  }
}