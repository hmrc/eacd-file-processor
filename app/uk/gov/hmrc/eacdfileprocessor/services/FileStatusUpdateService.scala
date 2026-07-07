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

import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{PROCESSEDWITHERRORS, PROCESSEDSUCCESSFULLY, PROCESSING}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileStatusUpdateService @Inject()(
                                         appConfig: AppConfig,
                                         deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository,
                                         fileRecordValidationErrorRepository: FileRecordValidationErrorRepository,
                                         fileRepository: FileRepository,
                                         lockService: LockService
                                       ) extends ScheduledService[Either[Unit, LockResponse]] with Logging {

  private given HeaderCarrier = HeaderCarrier()

  override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] =
    lockService.lockAndRelease("FileStatusUpdateJob") {
      processProcessingFiles()
    }

  private def processProcessingFiles()(using ExecutionContext): Future[Unit] =
    for {
      processingFiles <- fileRepository.findByStatusAsUploadedDetails(PROCESSING)
      _ <- Future.traverse(processingFiles)(processSingleFile)
    } yield ()

  private def processSingleFile(file: UploadedDetails)(using ExecutionContext): Future[Unit] =
    deEnrolmentWorkItemRepository.countRemainingNonCompleteByReference(file.reference.value).flatMap {
      case remaining if remaining >= 1 =>
        Future.unit

      case 0 =>
        reconcileAndFinalize(file)
    }

  private def reconcileAndFinalize(file: UploadedDetails)(using ExecutionContext): Future[Unit] = {
    val totalSuccessCount = file.totalSuccessCount.getOrElse(0)
    val totalFailureCount = file.totalFailureCount.getOrElse(0)
    val totalEntryCount = file.totalEntryCount.getOrElse(0)

    if (totalSuccessCount + totalFailureCount != totalEntryCount) {
      logger.error(s"FILE_RECONCILIATION_ERROR for file reference ${file.reference.value}")
      Future.unit
    } else {
      for {
        errorCount <- fileRecordValidationErrorRepository.countByReference(file.reference)
        targetStatus = if (errorCount == 0) PROCESSEDSUCCESSFULLY else PROCESSEDWITHERRORS
        _ <- fileRepository.updateStatus(file.reference, targetStatus)
        _ <- deEnrolmentWorkItemRepository.deleteByReference(file.reference.value)
      } yield ()
    }
  }
}