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
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, FileWorkItem}
import uk.gov.hmrc.eacdfileprocessor.repository.{FileRecordValidationErrorRepository, FileRepository, FileWorkItemRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileWorkItemSchedulerService @Inject()(
  appConfig: AppConfig,
  fileWorkItemRepository: FileWorkItemRepository,
  fileRecordValidationErrorRepository: FileRecordValidationErrorRepository,
  fileRepository: FileRepository,
  lockService: LockService,
  agentServiceCache: AgentServiceCache,
  validator: FileWorkItemValidator
) extends ScheduledService[Unit] with Logging {

  private given HeaderCarrier = HeaderCarrier()

  override def invoke(using ExecutionContext): Future[Unit] =
    lockService.lockAndRelease("FileWorkItemPullJob") {
      processBatch()
    }.map {
      case Left(_) =>
        logger.info("File work-item batch processed")
      case Right(MongoLocked) =>
        logger.info("File work-item scheduler skipped due to lock")
      case Right(UnlockingFailed) =>
        logger.warn("File work-item scheduler completed, but lock release failed")
    }

  private def processBatch()(using ExecutionContext): Future[Unit] =
    for {
      agentServices <- agentServiceCache.getAgentServices()
      pulled <- fileWorkItemRepository.pullOutstandingBatch(appConfig.fileWorkItemConcurrency)
      _ <- Future.sequence(pulled.map(processWorkItem(_, agentServices)))
    } yield ()

  private def processWorkItem(workItem: WorkItem[FileWorkItem], agentServices: Set[String])(using ExecutionContext): Future[Unit] = {
    val item = workItem.item
    val maybeError = validator.validate(item.recordDetail, agentServices)

    val validationEffect: Future[Unit] = maybeError match {
      case Some(errorMessage) =>
        val errorRecord = FileRecordValidationError(
          id = ObjectId.get(),
          reference = item.reference,
          fileName = item.fileName,
          recordDetail = item.recordDetail,
          errorMessage = errorMessage
        )
        for {
          _ <- fileRecordValidationErrorRepository.create(errorRecord)
          _ <- fileRepository.incrementFailureCount(item.reference)
        } yield ()
      case None =>
        Future.unit
    }

    validationEffect
      .flatMap(_ => fileWorkItemRepository.markAsSucceeded(workItem.id).map(_ => ()))
      .recover { case e =>
        logger.error(s"Failed to process work item ${workItem.id.toHexString}: ${e.getMessage}", e)
      }
  }
}


