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

package uk.gov.hmrc.eacdfileprocessor.controllers

import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.upscan.Reference
import uk.gov.hmrc.eacdfileprocessor.models.{FileStatus, StatusApproverDetails}
import uk.gov.hmrc.eacdfileprocessor.repo.FileUploadRepo
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import java.util.regex.Pattern
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

@Singleton()
class StatusController @Inject()(
                                  fileUploadRepo: FileUploadRepo,
                                  cc: ControllerComponents
                                )(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def updateStatus(reference: String): Action[JsValue] = Action.async(parse.json) {
    implicit request =>

      logger.info(s"Received update status notification [${Json.stringify(request.body)}]")

      withJsonBody[StatusApproverDetails] { statusApproverDetails =>
        fileUploadRepo.findByReference(Reference(reference)).map {
          case Some(uploadDetails) =>
            Try(FileStatus.valueOf(statusApproverDetails.status)) match {
              case Success(newStatus) =>
                val currentStatus = FileStatus.valueOf(uploadDetails.status)
                newStatus match
                  case UPLOAD_REJECTED if currentStatus != INITIATE =>
                    logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
                    BadRequest("Invalid status transition")
                  case UPLOADED if currentStatus != INITIATE =>
                    logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
                    BadRequest("Invalid status transition")
                  case REJECTED if currentStatus != STORED =>
                    logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
                    BadRequest("Invalid status transition")
                  case APPROVED if currentStatus != STORED =>
                    logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
                    BadRequest("Invalid status transition")
                  case _ if newStatus == currentStatus =>
                    logger.warn("ALREADY_AT_STATUS Already at the requested status")
                    BadRequest("Already at the requested status")
                  case _ =>
                    updateStatus(reference, statusApproverDetails, newStatus == UPLOAD_REJECTED || newStatus == UPLOADED)

              case Failure(e) =>
                logger.warn("INVALID_STATUS_TRANSITION Invalid status transition")
                BadRequest("Invalid status transition")
            }

          case None =>
            logger.warn("INVALID_STATUS Status not supported")
            BadRequest("Status not supported")
        }
      }
  }

  private def updateApprovedStatus(currentStatus: FileStatus, requestorPID: String, statusApproverDetails: StatusApproverDetails, reference: String) = {
//    val pidRegex: Pattern = Pattern.compile("^[0-9]{8}$")
    val emailRegex: Pattern = Pattern.compile("^([a-zA-Z0-9.!#$%&’'*+/=?^_{|}~-]+)@([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*)$")
    (statusApproverDetails.approverName, statusApproverDetails.approverPID, statusApproverDetails.approverEmail) match
      case (Some(name), Some(pid), Some(email)) if email.matches(emailRegex.pattern()) =>
        logger.warn("INVALID_APPROVER_EMAIL Approver email address is invalid")
        BadRequest("Approver email address is invalid")
      case (Some(name), Some(pid), Some(email)) if pid == requestorPID =>
        logger.warn("INVALID_PID Request and approver PIDs cannot be the same")
        BadRequest("Request and approver PIDs cannot be the same")
      case (Some(name), Some(pid), Some(email)) =>
        updateStatus(reference, statusApproverDetails)
      case (_, _, _) =>
        logger.warn("APPROVER_FIELDS_MISSING Approver fields are missing for status approved")
        BadRequest("Approver fields are missing for status approved")
  }

  private def updateStatus(reference: String, statusApproverDetails: StatusApproverDetails, isUploadedRelatedStatus: Boolean = false) = {
    val uploadedDateTime = if isUploadedRelatedStatus Some(Instant.now) else None
    fileUploadRepo.updateStatusAndApproverInfo(Reference(reference), statusApproverDetails, uploadedDateTime) map {
      case Some(_) => NoContent
      case None =>
        logger.warn("SERVICE_UNAVAILABLE An unexpected error has occurred")
        ServiceUnavailable("An unexpected error has occurred")
    }
  }
}
