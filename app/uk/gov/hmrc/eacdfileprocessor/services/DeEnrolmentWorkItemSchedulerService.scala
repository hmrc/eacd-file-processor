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
import play.api.libs.json.JsValue
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, Details, FileRecordValidationError, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.http.{HeaderCarrier,RequestId}
import uk.gov.hmrc.mongo.workitem.WorkItem
import java.util.UUID

import javax.inject.{Inject, Singleton}
import scala.annotation.tailrec
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
                                                     validator: DeEnrolmentWorkItemValidator,
                                                     auditService: AuditService
                                                   ) extends ScheduledService[Either[Unit, LockResponse]] with Logging {

  private given HeaderCarrier = HeaderCarrier(
    requestId = Some(RequestId(UUID.randomUUID().toString))
  )

  override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] = {
    lockService.lockAndRelease("DeEnrolmentWorkItemPullJob") {
      processBatch()
    }
  }

  private def processBatch()(using ExecutionContext): Future[Unit] =
    (for {
      pulled <- deEnrolmentWorkItemRepository.pullOutstandingBatch(appConfig.DeEnrolmentWorkItemConcurrency)
      _ = logger.info(s"[processBatch] Pulled ${pulled.size} outstanding de-enrolment work item(s)")
      _ = logger.info(s"[processBatch] About to fetch agent services: pulledNonEmpty=${pulled.nonEmpty}")
      agentServices <- if pulled.nonEmpty then
        agentServiceCache.getAgentServices().recover { case e =>
          logger.error(s"[processBatch] Failed to fetch agent services, continuing with empty set: ${e.getMessage}", e)
          Set.empty[String]
        } else Future.successful(Set.empty[String])
      _ = logger.info(s"[processBatch] agentServices fetched: size=${agentServices.size}")
      _ = logger.info(s"[processBatch] About to process ${pulled.size} item(s)")
      _ <- Future.traverse(pulled)(processItem(_, agentServices))
      _ = logger.info(s"[processBatch] Completed processing batch of ${pulled.size} item(s)")
    } yield ()).recoverWith { case e =>
      logger.error(s"[processBatch] Batch failed: ${e.getMessage}", e)
      Future.successful(throw e)
    }
  
  private def processItem(workItem: WorkItem[DeEnrolmentWorkItem], agentServices: Set[String])(using ExecutionContext): Future[Unit] = {
    val item = workItem.item
    val reference = Reference(item.reference)

    logger.info(s"[processItem] Processing work item ${workItem.id.toHexString} with reference ${item.reference}")

    val validationResult = validator.validate(item.recordDetail, agentServices)

    validationResult match {
      case Left(errorMessage) =>
        logger.warn(s"[processItem] Validation failed for work item ${workItem.id.toHexString}: $errorMessage")
        recordErrorAndMarkComplete(reference, item.recordDetail, errorMessage, workItem.id)

      case Right((enrolmentKey, actionType)) =>
        logger.info(s"[processItem] Validation succeeded for work item ${workItem.id.toHexString}. EnrolmentKey: $enrolmentKey, ActionType: $actionType")
        handleValidatedWorkItem(enrolmentKey, actionType, reference, item.recordDetail, workItem.id)
    }
  }

  private def handleValidatedWorkItem(
                                       enrolmentKey: String,
                                       actionType: String,
                                       reference: Reference,
                                       recordDetail: String,
                                       workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    actionType.toLowerCase match {
      case "principal" | "delegated" | "agent" | "both" =>
        logger.info(s"[handleValidatedWorkItem] Calling ES1 for work item ${workItemId.toHexString} with enrolmentKey $enrolmentKey and actionType $actionType")
        callES1AndProcessResult(enrolmentKey, actionType, reference, recordDetail, workItemId)
      case unknown =>
        logger.error(s"[handleValidatedWorkItem] Unexpected actionType '$unknown' for work item ${workItemId.toHexString}")
        recordErrorAndMarkComplete(reference, recordDetail, s"Unexpected action type: $unknown", workItemId)
    }
  }

  private def callES1AndProcessResult(
                                       enrolmentKey: String,
                                       actionType: String,
                                       reference: Reference,
                                       recordDetail: String,
                                       workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    val es1Type = transformActionType(actionType)

    logger.info(s"[callES1AndProcessResult] Calling ES1 for work item ${workItemId.toHexString} with enrolmentKey $enrolmentKey and type $es1Type")

    espConnector.callES1(enrolmentKey, es1Type).flatMap { es1Response =>
      logger.debug(s"[callES1AndProcessResult] ES1 response for work item $workItemId: status=${es1Response.status}")
      es1Response.status match {
        case NO_CONTENT =>
          logger.info(s"[callES1AndProcessResult] ES1 completed for reference ${reference.value}. Incrementing success count.")
          workItemProcessedSuccessfully(reference, workItemId, enrolmentKey, actionType)
        case OK =>
          logger.info(s"[callES1AndProcessResult] ES1 returned groups for reference ${reference.value}. Processing de-enrolments.")
            handleES1Success(enrolmentKey, actionType, es1Response.json, reference, recordDetail, workItemId)
        case _ =>
          val errorMessage = extractErrorMessage(es1Response.json)
          logger.warn(s"[callES1AndProcessResult] ES1 failed with status ${es1Response.status} for reference ${reference.value}: $errorMessage")
          recordErrorAndMarkComplete(reference, recordDetail, errorMessage, workItemId)
      }
    }.recover { case e =>
      logger.error(s"[callES1AndProcessResult] Unexpected error processing work item ${workItemId.toHexString}, reference ${reference.value}: ${e.getMessage}", e)
      // Rethrow to allow the scheduler to handle retries if configured
      throw e
    }
  }

  private def handleES1Success(
                                enrolmentKey: String,
                                actionType: String,
                                jsonResponse: JsValue,
                                reference: Reference,
                                recordDetail: String,
                                workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    val groupIds = extractGroupIds(jsonResponse)
    logger.info(s"[handleES1Success] Found ${groupIds.size} group(s) to de-enrol for reference ${reference.value}")

    if (groupIds.isEmpty) {
      logger.info(s"[handleES1Success] No groups found for reference ${reference.value}. Marking work item as complete and incrementing success count.")
      workItemProcessedSuccessfully(reference, workItemId, enrolmentKey, actionType)
    } else {
      processGroupDeEnrolments(enrolmentKey, actionType, groupIds, reference, recordDetail, workItemId)
    }
  }

  private def processGroupDeEnrolments(
                                        enrolmentKey: String,
                                        actionType: String,
                                        groupIds: Seq[String],
                                        reference: Reference,
                                        recordDetail: String,
                                        workItemId: ObjectId)(using ExecutionContext) = {

    @tailrec
    def processNextGroup(hasError: Future[Boolean], remainingGroupIds: Seq[String]): Future[Unit] = {
      remainingGroupIds match {
        case Nil =>
          for {
            errorOccurred <- hasError
            _ = if !errorOccurred then workItemProcessedSuccessfully(reference, workItemId, enrolmentKey, actionType)
          } yield ()
        case groupId :: tail =>
          val failed = for {
            errorOccurred <- hasError
            isFailed <- if errorOccurred then
              Future.successful(true)
            else
              handleES9Call(groupId, groupIds, enrolmentKey, reference, recordDetail, workItemId)
          } yield isFailed
          processNextGroup(failed, tail)
      }
    }

    processNextGroup(Future.successful(false), groupIds)
  }

  private def handleES9Call(groupId: String,
                            groupIds: Seq[String],
                            enrolmentKey: String,
                            reference: Reference,
                            recordDetail: String,
                            workItemId: ObjectId)(using ExecutionContext): Future[Boolean] = {
    espConnector.callES9(groupId, enrolmentKey).map { response =>
      logger.info(s"[processGroupDeEnrolments] ES9 response for groupId $groupId and reference ${reference.value}: status=${response.status}")

      response.status match {
        case NO_CONTENT => false
        case _ =>
          val errorMessage = if groupIds.size > 1 then
            "Partial processing due to unknown error, review manually"
          else
            extractErrorMessage(response.json)
          logger.error(s"[processGroupDeEnrolments] ES9 failed for groupId $groupId and reference ${reference.value}, error: $errorMessage")
          recordErrorAndMarkComplete(reference, recordDetail, errorMessage, workItemId)
          true
      }
    }
  }

  private def recordErrorAndMarkComplete(reference: Reference, recordDetail: String, errorMessage: String, workItemId: ObjectId)(using ExecutionContext): Future[Unit] = {
    logger.debug(s"[recordError] Recording validation error for reference ${reference.value}: $errorMessage")

    for {
      uploadedDetailsOpt <- deEnrolmentWorkItemRepository.markAsComplete(workItemId).flatMap {
        case true => fileRepository.incrementFailureCount(reference)
        case false => Future.successful(throw RuntimeException(s"[recordError] Failed to mark work item as complete for workItemId ${workItemId.toHexString} reference ${reference.value}"))
      }
      fileName = uploadedDetailsOpt.flatMap(_.details.map(Details.getFileName)).getOrElse("")
      _ <- fileRecordValidationErrorRepository.create(
        FileRecordValidationError(
          id = ObjectId.get(),
          reference = reference,
          fileName = fileName,
          recordDetail = recordDetail,
          errorMessage = errorMessage
        )
      )
    } yield ()
  }

  private def workItemProcessedSuccessfully(
                                             reference: Reference,
                                             workItemId: ObjectId,
                                             enrolmentKey: String,
                                             actionType: String
                                           )(using ExecutionContext): Future[Unit] = {
    logger.info(s"[workItemProcessedSuccessfully] Marking work item as complete and incrementing success count for reference ${reference.value}")
    deEnrolmentWorkItemRepository.markAsComplete(workItemId).flatMap {
      case true =>
        fileRepository.incrementSuccessCount(reference).flatMap {
          case Some(uploadedDetails) =>
            auditService.auditDeallocateEnrolmentEvent(
              uploadedDetails = uploadedDetails,
              enrolmentKey = enrolmentKey,
              enrolmentAction = actionType
            ).map(_ => ())
          case None =>
            logger.warn(s"[workItemProcessedSuccessfully] No UploadedDetails found for reference ${reference.value}, skipping audit")
            Future.unit
        }
      case false =>
        Future.failed(RuntimeException(s"[workItemProcessedSuccessfully] Failed to mark work item as complete for workItemId ${workItemId.toHexString} reference ${reference.value}"))
    }
  }

  private[services] def transformActionType(actionType: String) =
    actionType match {
      case "agent" => "principal"
      case "both" => "all"
      case _ => actionType
    }

  private[services] def extractGroupIds(json: play.api.libs.json.JsValue): Seq[String] =
    (json \ "principalGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty) ++
      (json \ "delegatedGroupIds").asOpt[Seq[String]].getOrElse(Seq.empty)

  private def extractErrorMessage(json: play.api.libs.json.JsValue): String =
    (json \ "message").asOpt[String].getOrElse("Unknown error")

}