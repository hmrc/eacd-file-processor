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
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{PROCESSEDSUCCESSFULLY, PROCESSEDWITHERRORS, PROCESSING}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.ScheduledService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DefaultUpdateFileStatusService @Inject()(val fileRepository: FileRepository,
                                               val workItemRepository: DeEnrolmentWorkItemRepository,
                                               val lockService: LockService,
                                               implicit val ec: ExecutionContext
                                              ) extends UpdateFileStatusService

trait UpdateFileStatusService extends Logging with ScheduledService[Either[Unit, LockResponse]] {
  implicit val ec: ExecutionContext
  val fileRepository: FileRepository
  val workItemRepository: DeEnrolmentWorkItemRepository
  val lockService: LockService

  override def invoke(implicit ec: ExecutionContext): Future[Either[Unit, LockResponse]] =
    lockService.lockAndRelease(this.getClass.getSimpleName) {
      updateCompletedFileStatuses
    }

  private[services] def updateCompletedFileStatuses: Future[Unit] = {
    fileRepository.findByStatus(PROCESSING).flatMap { processingFiles =>
      if (processingFiles.isEmpty) {
        logger.info("No files currently being processed")
        Future.unit
      } else {
        logger.info(s"Found ${processingFiles.size} file(s) being processed")
        Future.sequence(processingFiles.map(file => processFile(file.reference))).map(_ => ())
      }
    }
  }

  private def processFile(referenceStr: String): Future[Unit] = {
    workItemRepository.incompleteWorkItemsCountForRef(referenceStr).flatMap { incompleteCount =>
      if (incompleteCount == 0) {
        logger.info(s"All work items completed for file reference: $referenceStr")
        updateFileStatusAndCleanup(referenceStr)
      } else {
        logger.info(s"File reference: $referenceStr still has $incompleteCount incomplete work item(s)")
        Future.unit
      }
    }
  }

  private def updateFileStatusAndCleanup(referenceStr: String): Future[Unit] = {
    for {
      succeededCount <- workItemRepository.succeededWorkItemsCountForRef(referenceStr)
      failedCount <- workItemRepository.failedWorkItemsCountForRef(referenceStr)
      file <- fileRepository.findByReference(uk.gov.hmrc.eacdfileprocessor.models.Reference(referenceStr))
      _ <- file match {
        case Some(uploadedDetails) =>
          reconcileAndUpdateStatus(uploadedDetails, succeededCount, failedCount, referenceStr)
        case None =>
          logger.warn(s"FILE_NOT_FOUND for reference: $referenceStr")
          Future.unit
      }
    } yield ()
  }

  private def reconcileAndUpdateStatus(uploadedDetails: UploadedDetails, succeededCount: Int, failedCount: Int, referenceStr: String): Future[Unit] = {
    val totalProcessed = succeededCount + failedCount
    val expectedTotal = uploadedDetails.totalEntryCount.getOrElse(0)

    if (totalProcessed != expectedTotal) {
      logger.error(s"FILE_RECONCILIATION_ERROR for reference: $referenceStr - Expected: $expectedTotal, Processed: $totalProcessed (Succeeded: $succeededCount, Failed: $failedCount)")
    }

    val newStatus = if (failedCount > 0) PROCESSEDWITHERRORS else PROCESSEDSUCCESSFULLY

    fileRepository.updateStatus(uploadedDetails.reference, newStatus).flatMap { _ =>
      logger.info(s"Updated file status to $newStatus for reference: $referenceStr (Succeeded: $succeededCount, Failed: $failedCount)")
      workItemRepository.deleteWorkItemsByReference(referenceStr).map { _ =>
        logger.info(s"Deleted work items for reference: $referenceStr")
      }
    }
  }
}
