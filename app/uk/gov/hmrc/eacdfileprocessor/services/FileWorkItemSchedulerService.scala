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

import org.bson.types.ObjectId
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, FileRecordValidationError, FileStatus, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.eacdfileprocessor.utils.FileWorkItemValidator
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileWorkItemSchedulerService @Inject()(
  appConfig: AppConfig,
  deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository,
  fileRecordValidationErrorRepository: FileRecordValidationErrorRepository,
  fileRepository: FileRepository,
  lockService: LockService,
  agentServiceCache: AgentServiceCache,
  validator: FileWorkItemValidator
) extends ScheduledService[Either[Unit, LockResponse]] with Logging {

  private given HeaderCarrier = HeaderCarrier()

  override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] =
    lockService.lockAndRelease("FileWorkItemPullJob") {
      processBatch()
    }

  private def processBatch()(using ExecutionContext): Future[Unit] =
    for {
      agentServices <- agentServiceCache.getAgentServices()
      pulled <- deEnrolmentWorkItemRepository.pullOutstandingBatch(appConfig.fileWorkItemConcurrency)
      _ <- Future.traverse(pulled)(processWorkItem(_, agentServices))
    } yield ()

  private def processWorkItem(workItem: WorkItem[DeEnrolmentWorkItem], agentServices: Set[String])(using ExecutionContext): Future[Unit] = {
    val item = workItem.item
    val maybeError = validator.validate(item.recordDetail, agentServices)
    val reference = Reference(item.reference)

    val validationEffect: Future[Boolean] = maybeError match {
      case Some(errorMessage) =>
        for {
          fileName <- fileRepository.getNameOfFile(reference).map(_.getOrElse(""))
          _ <- fileRecordValidationErrorRepository.create(
            FileRecordValidationError(
              id = ObjectId.get(),
              reference = reference,
              fileName = fileName,
              recordDetail = item.recordDetail,
              errorMessage = errorMessage
            )
          )
          _ <- fileRepository.incrementFailureCount(reference)
          result <- deEnrolmentWorkItemRepository.markAsFailed(workItem.id)
        } yield result
      case None =>
        deEnrolmentWorkItemRepository.markAsSucceeded(workItem.id)
    }

    validationEffect
      .map(_ => ())
      .flatMap(_ => updateFileStatusIfComplete(reference, item.reference))
      .recover { case e =>
        logger.error(s"Failed to process work item ${workItem.id.toHexString}: ${e.getMessage}", e)
      }
  }

  private def updateFileStatusIfComplete(reference: Reference, rawReference: String)(using ExecutionContext): Future[Unit] =
    for {
      incomplete <- deEnrolmentWorkItemRepository.countIncompleteByReference(rawReference)
      _ <- if (incomplete == 0) {
        deEnrolmentWorkItemRepository.hasAnyFailedByReference(rawReference).flatMap { anyFailed =>
          val finalStatus = if (anyFailed) FileStatus.FAILED else FileStatus.UPLOADED
          logger.info(s"All work items for reference $rawReference complete. Setting file status to $finalStatus")
          fileRepository.updateStatus(reference, finalStatus).map(_ => ())
        }
      } else {
        Future.unit
      }
    } yield ()
}

