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
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, FileRecordValidationError, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeEnrolmentWorkItemSchedulerService @Inject()(
                                                     appConfig: AppConfig,
                                                     deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository,
                                                     fileRecordValidationErrorRepository: FileRecordValidationErrorRepository,
                                                     fileRepository: FileRepository,
                                                     lockService: LockService,
                                                     agentServiceCache: AgentServiceCache,
                                                     validator: DeEnrolmentWorkItemValidator
                                                   ) extends ScheduledService[Either[Unit, LockResponse]] with Logging {

  private given HeaderCarrier = HeaderCarrier()

  override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] =
    lockService.lockAndRelease("DeEnrolmentWorkItemPullJob") {
      processBatch()
    }

  private def processBatch()(using ExecutionContext): Future[Unit] =
    for {
      pulled <- deEnrolmentWorkItemRepository.pullOutstandingBatch(appConfig.DeEnrolmentWorkItemConcurrency)
      agentServices <- if pulled.nonEmpty then agentServiceCache.getAgentServices() else Future.successful(Set.empty)
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
          result <- fileRepository.incrementFailureCount(reference).map(_ => true)
        } yield result
      case None => Future.successful(false)
    }

    validationEffect
      .map(_ => ())
      .recover { case e =>
        logger.error(s"Failed to process work item ${workItem.id.toHexString}: ${e.getMessage}", e)
      }
  }
}

