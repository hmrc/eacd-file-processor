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
    (for {
      pulled <- deEnrolmentWorkItemRepository.pullOutstandingBatch(appConfig.DeEnrolmentWorkItemConcurrency)
      _ = logger.info(s"[processBatch] Pulled ${pulled.size} outstanding de-enrolment work item(s)")
      _ = logger.info(s"[processBatch] About to fetch agent services: pulledNonEmpty=${pulled.nonEmpty}")
      agentServices <- if pulled.nonEmpty then agentServiceCache.getAgentServices() else Future.successful(Set.empty[String])
      _ = logger.info(s"[processBatch] agentServices fetched: size=${agentServices.size}")
      _ = logger.info(s"[processBatch] About to process ${pulled.size} item(s)")
      _ <- Future.traverse(pulled)(processItem(_, agentServices))
      _ = logger.info(s"[processBatch] Completed processing batch of ${pulled.size} item(s)")
    } yield ()).recoverWith { case e =>
      logger.error(s"[processBatch] Batch failed: ${e.getMessage}", e)
      Future.unit
    }

  // Unified processing: validate then handle result
  private def processItem(workItem: WorkItem[DeEnrolmentWorkItem], agentServices: Set[String])(using ExecutionContext): Future[Unit] = {
    val item = workItem.item
    val reference = Reference(item.reference)

    logger.info(s"[processItem] Processing work item ${workItem.id.toHexString} with reference ${item.reference}")

    val validationResult = validator.validate(item.recordDetail, agentServices)

    validationResult match {
      case Left(errorMessage) =>
        logger.warn(s"[processItem] Validation failed for work item ${workItem.id.toHexString}: $errorMessage")
        recordError(reference, item.recordDetail, errorMessage)

      case Right((enrolmentKey, actionType)) =>
        logger.debug(s"[processItem] Validation succeeded for work item ${workItem.id.toHexString}. EnrolmentKey: $enrolmentKey, ActionType: $actionType")
        handleValidatedWorkItem(enrolmentKey, actionType, reference, item.recordDetail, workItem.id)
    }
  }

  private def handleValidatedWorkItem(
                                       enrolmentKey: String,
                                       actionType: String,
                                       reference: Reference,
                                       recordDetail: String,
                                       workItemId: ObjectId
                                     )(using ExecutionContext): Future[Unit] = {
    actionType.toLowerCase match {
      case "principal" | "delegated" | "agent" =>
        logger.info(s"[handleValidatedWorkItem] Calling ES1 for work item ${workItemId.toHexString} with enrolmentKey $enrolmentKey and actionType $actionType")
        callES1AndProcessResult(enrolmentKey, actionType, reference, recordDetail, workItemId)
      case "both" =>
        logger.error(s"[handleValidatedWorkItem] 'both' actionType not yet implemented for work item ${workItemId.toHexString}. Manual review required.")
        recordError(reference, recordDetail, "Action type 'both' not yet implemented - manual review required")
      case unknown =>
        logger.error(s"[handleValidatedWorkItem] Unexpected actionType '$unknown' for work item ${workItemId.toHexString}")
        recordError(reference, recordDetail, s"Unexpected action type: $unknown")
    }
  }

  private def callES1AndProcessResult(
                                       enrolmentKey: String,
                                       actionType: String,
                                       reference: Reference,
                                       recordDetail: String,
                                       workItemId: ObjectId
                                     )(using ExecutionContext): Future[Unit] = {
    espConnector.callES1(enrolmentKey, actionType).flatMap { es1Response =>
      logger.debug(s"[callES1AndProcessResult] ES1 response for work item $workItemId: status=${es1Response.status}")
      es1Response.status match {
        case NO_CONTENT =>
          logger.info(s"[callES1AndProcessResult] ES1 completed for reference ${reference.value}. Incrementing success count.")
          for {
            _ <- fileRepository.incrementSuccessCount(reference)
            _ <- deEnrolmentWorkItemRepository.markAsComplete(workItemId)
          } yield ()
        case OK =>
          logger.debug(s"[callES1AndProcessResult] ES1 returned groups for reference ${reference.value}. Processing de-enrolments.")
          handleES1Success(enrolmentKey, es1Response.json, reference, recordDetail, workItemId)
        case _ =>
          val errorMessage = extractErrorMessage(es1Response.json)
          logger.warn(s"[callES1AndProcessResult] ES1 failed with status ${es1Response.status} for reference ${reference.value}: $errorMessage")
          recordError(reference, recordDetail, errorMessage)
      }
    }.recover { case e =>
      logger.error(s"[callES1AndProcessResult] Unexpected error processing work item ${workItemId.toHexString}: ${e.getMessage}", e)
      // Rethrow to allow the scheduler to handle retries if configured
      throw e
    }
  }

  private def handleES1Success(
                                enrolmentKey: String,
                                jsonResponse: play.api.libs.json.JsValue,
                                reference: Reference,
                                recordDetail: String,
                                workItemId: ObjectId
                              )(using ExecutionContext): Future[Unit] = {
    val groupIds = extractGroupIds(jsonResponse)
    logger.debug(s"[handleES1Success] Found ${groupIds.size} group(s) to de-enrol for reference ${reference.value}")

    if (groupIds.isEmpty) {
      logger.info(s"[handleES1Success] No groups found for reference ${reference.value}. Marking work item as complete.")
      deEnrolmentWorkItemRepository.markAsComplete(workItemId).map(_ => ())
    } else {
      processGroupDeEnrolments(enrolmentKey, groupIds, reference, recordDetail, workItemId)
    }
  }

  private def processGroupDeEnrolments(
                                        enrolmentKey: String,
                                        groupIds: Seq[String],
                                        reference: Reference,
                                        recordDetail: String,
                                        workItemId: ObjectId
                                      )(using ExecutionContext): Future[Unit] = {
    Future.sequence(groupIds.map { groupId =>
      espConnector.callES9(groupId, enrolmentKey).flatMap { response =>
        logger.debug(s"[processGroupDeEnrolments] ES9 response for groupId $groupId and reference ${reference.value}: status=${response.status}")

        response.status match {
          case NO_CONTENT =>
            logger.info(s"[processGroupDeEnrolments] Successfully de-enrolled groupId $groupId for reference ${reference.value}")
            fileRepository.incrementSuccessCount(reference)
              .flatMap(_ => deEnrolmentWorkItemRepository.markAsComplete(workItemId))
              .map(_ => ())
              .recover { case e =>
                logger.error(s"[processGroupDeEnrolments] Failed to mark work item as complete for reference ${reference.value}: ${e.getMessage}", e)
                throw e
              }
          case _ =>
            val errorMessage = if (groupIds.size > 1) then
              "Partial processing due to unknown error, review manually"
            else
              extractErrorMessage(response.json)
            logger.warn(s"[processGroupDeEnrolments] ES9 failed for groupId $groupId and reference ${reference.value}: $errorMessage")
            recordError(reference, recordDetail, errorMessage).map(_ => ()) // <-- Added .map(_ => ()) here
        }
      }
    }).map(_ => ()).recover { case e =>
      logger.error(s"[processGroupDeEnrolments] Error during batch ES9 processing for reference ${reference.value}: ${e.getMessage}", e)
      throw e
    }
  }

  private def recordError(reference: Reference, recordDetail: String, errorMessage: String)(using ExecutionContext): Future[Unit] = {
    logger.debug(s"[recordError] Recording validation error for reference ${reference.value}: $errorMessage")
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

  private def extractGroupIds(json: play.api.libs.json.JsValue): Seq[String] =
    (json \ "principalGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty) ++
      (json \ "delegatedAgentGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty)

  private def extractErrorMessage(json: play.api.libs.json.JsValue): String =
    (json \ "message").asOpt[String].getOrElse("Unknown error")

}