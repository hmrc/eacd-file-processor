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
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, NoContent, ServiceUnavailable}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{APPROVED, INITIAL, REJECTED, STORED, UPLOADED, UPLOADREJECTED}
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, ApproverDetails, FileStatus, Reference, StatusApproverDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.ValidationUtil

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import java.time.Instant

@Singleton
class StatusService @Inject()(fileUploadRepo: FileRepository)(implicit ec: ExecutionContext) extends Logging {

  def updateStatus(reference: String, currentStatus: FileStatus, requestorPID: String, statusApproverDetails: StatusApproverDetails): Future[Result] = {
    Try(FileStatus.valueOf(statusApproverDetails.status.toUpperCase)) match {
      case Success(newStatus) =>
        newStatus match
          case _ if newStatus == currentStatus =>
            logger.warn("ALREADY_AT_STATUS Already at the requested status")
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("ALREADY_AT_STATUS", "Already at the requested status"))))
          case UPLOADED if currentStatus != INITIAL =>
            logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_STATUS_TRANSITION", "Invalid status transition"))))
          case REJECTED if currentStatus != STORED =>
            logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_STATUS_TRANSITION", "Invalid status transition"))))
          case UPLOADREJECTED =>
            updateUploadRejectedStatus(currentStatus, statusApproverDetails, reference)
          case APPROVED =>
            updateApprovedStatus(currentStatus, requestorPID, statusApproverDetails, reference)
          case _ =>
            updateStatusToRepo(reference, statusApproverDetails, newStatus == UPLOADED)

      case Failure(e) =>
        logger.warn("INVALID_STATUS Status not supported")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_STATUS", "Status not supported"))))
    }
  }

  private[services] def updateApprovedStatus(currentStatus: FileStatus, requestorPID: String, statusApproverDetails: StatusApproverDetails,
                                             reference: String): Future[Result] = {
    (statusApproverDetails.approverName, statusApproverDetails.approverPID, statusApproverDetails.approverEmail) match
      case (Some(name), Some(pid), Some(email)) if currentStatus != STORED =>
        logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_STATUS_TRANSITION", "Invalid status transition"))))
      case (Some(name), Some(pid), Some(email)) if !ValidationUtil.isEmailValid(email) =>
        logger.warn("INVALID_APPROVER_EMAIL Approver email address is invalid")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_APPROVER_EMAIL", "Approver email address is invalid"))))
      case (Some(name), Some(pid), Some(email)) if pid == requestorPID =>
        logger.warn("INVALID_PID Request and approver PIDs cannot be the same")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_PID", "Request and approver PIDs cannot be the same"))))
      case (Some(name), Some(pid), Some(email)) =>
        updateStatusToRepo(reference, statusApproverDetails, approvedAt = Instant.now())
      case (_, _, _) =>
        logger.warn("APPROVER_FIELDS_MISSING Approver fields are missing for status approved")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("APPROVER_FIELDS_MISSING", "Approver fields are missing for status approved"))))
  }

  private[services] def updateUploadRejectedStatus(currentStatus: FileStatus, statusApproverDetails: StatusApproverDetails, reference: String): Future[Result] = {
    (statusApproverDetails.errorCode, statusApproverDetails.errorMessage) match
      case (Some(code), Some(message)) if currentStatus != INITIAL =>
        logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_STATUS_TRANSITION", "Invalid status transition"))))
      case (Some(code), Some(message)) =>
        updateStatusToRepo(reference, statusApproverDetails, isUploadedRelatedStatus = true)
      case (_, _) =>
        logger.warn("ERROR_FIELDS_MISSING Error fields are missing for status failed")
        Future.successful(BadRequest(Json.toJson(ApiErrorResponse("ERROR_FIELDS_MISSING", "Error fields are missing for status failed"))))
  }

  private[services] def updateStatusToRepo(reference: String, statusApproverDetails: StatusApproverDetails, isUploadedRelatedStatus: Boolean = false, approvedAt: Instant = Instant.MIN): Future[Result] = {
    val approverDetails = (Json.toJson(statusApproverDetails).as[JsObject] - "status").as[ApproverDetails]
    fileUploadRepo.updateStatusAndApproverDetails(Reference(reference), FileStatus.valueOf(statusApproverDetails.status.toUpperCase), approverDetails, isUploadedRelatedStatus, approvedAt) map {
      case Some(_) =>
        NoContent
      case None =>
        logger.warn("SERVICE_UNAVAILABLE An unexpected error has occurred")
        ServiceUnavailable(Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred")))
    }
  }
}
