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
import play.api.http.Status.{NO_CONTENT, OK}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, FileRecordValidationError, Reference, UploadedDetails}
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
                                                     espConnector: EspConnector,
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
      _ <- Future.traverse(pulled)(validateWorkItem(_, agentServices))
    } yield ()

  private def validateWorkItem(workItem: WorkItem[DeEnrolmentWorkItem], agentServices: Set[String])(using ExecutionContext): Future[Unit] = {
    val item = workItem.item
    val validationResult = validator.validate(item.recordDetail, agentServices)
    val reference = Reference(item.reference)

    (validationResult match {
      case Left(errorMessage) =>
        recordError(reference, item.recordDetail, errorMessage)
      case Right((enrolmentKey, actionType)) =>
        actionType match {
          case "principal" | "agent" => processWorkItem(enrolmentKey, "principal", reference, item.recordDetail, workItem.id)
          case "delegated" => processWorkItem(enrolmentKey, "delegated", reference, item.recordDetail, workItem.id)
          case "both" => Future.successful(()) //need Part 3 here
          case _ => Future.successful(())
        }
    }).recover { case e =>
      logger.error(s"Failed to process work item ${workItem.id.toHexString}: ${e.getMessage}", e)
    }
  }

  private def processWorkItem(enrolmentKey: String, actionType: String, reference: Reference, recordDetail: String, workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    espConnector.callES1(enrolmentKey, actionType).flatMap { es1Response =>
      es1Response.status match {
        case NO_CONTENT => fileRepository.incrementSuccessCount(reference).map(_ => ())
        case OK => deEnrolWorkItem(enrolmentKey, extractGroupIds(es1Response.json), reference, recordDetail, workItemId)
        case _ => recordError(reference, recordDetail, extractErrorMessage(es1Response.json))
      }
    }
  }

  private def deEnrolWorkItem(enrolmentKey: String, groupIds: Seq[String], reference: Reference, recordDetail: String, workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    Future.sequence(groupIds.map { groupId =>
      espConnector.callES9(groupId, enrolmentKey).flatMap { response =>
        if response.status == NO_CONTENT then
          fileRepository.incrementSuccessCount(reference)
            .flatMap(_ => deEnrolmentWorkItemRepository.markAsComplete(workItemId))
            .map(_ => ())
        else
          val errorMessage = if groupIds.size > 1 then "Partial processing due to unknown error, review manually"
          else extractErrorMessage(response.json)
          recordError(reference, recordDetail, errorMessage)
      }
    }).map(_ => ())
  }


  private def recordError(reference: Reference, recordDetail: String, errorMessage: String)(using ExecutionContext): Future[Unit] = {
    for {
      fileName <- fileRepository.getNameOfFile(reference).map(_.getOrElse(""))
      _ <- fileRecordValidationErrorRepository.create(
        FileRecordValidationError(
          id = ObjectId.get(),
          reference = reference,
          fileName = fileName,
          recordDetail = recordDetail,
          errorMessage = errorMessage
        )
      )
      _ <- fileRepository.incrementFailureCount(reference)
    } yield ()
  }

  private def extractGroupIds(json: play.api.libs.json.JsValue): Seq[String] = {
    (json \ "principalGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty) ++
      (json \ "delegatedAgentGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty)
  }

  private def extractErrorMessage(json: play.api.libs.json.JsValue): String = {
    (json \ "message").asOpt[String].getOrElse("Unknown error")
  }

}

