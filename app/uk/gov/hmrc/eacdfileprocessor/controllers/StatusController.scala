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

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents, Request}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, FileStatus, Reference, StatusApproverDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.StatusService
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StatusController @Inject()(
                                  fileUploadRepo: FileRepository,
                                  statusService: StatusService,
                                  val cc: ControllerComponents,
                                  val configuration: Configuration,
                                  val auth: BackendAuthComponents
                                )(implicit ec: ExecutionContext) extends BackendController(cc) with InternalAuthBuilders with Logging {
  val providedPermission = Predicate.or(
    Predicate.Permission(
      Resource(ResourceType("eacd-file-processor"), ResourceLocation("services-enrolments-helpdesk-frontend")),
      IAAction("ADMIN")
    ),
    Predicate.Permission(
      Resource(ResourceType("eacd-file-processor"), ResourceLocation("emac-support-frontend")),
      IAAction("ADMIN")
    )
  )

  def updateStatus(reference: String): Action[JsValue] = authorisedEntity(providedPermission, "status")
    .async(parse.json) { implicit request: Request[JsValue] =>

      logger.info(s"Received update status notification [${Json.stringify(request.body)}]")

      withJsonBody[StatusApproverDetails] { statusApproverDetails =>
        fileUploadRepo.findByReference(Reference(reference)).flatMap {
          case Some(uploadDetails) =>
            statusService.updateStatus(reference, uploadDetails.status, uploadDetails.requestorPID, statusApproverDetails)

          case None =>
            logger.warn("INVALID_FILE_REF File reference doesn't exist")
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_FILE_REF", "File reference doesn't exist"))))
        }
      }
    }

  def getFileStatus(status: String): Action[JsValue] = authorisedEntity(providedPermission, "get-file-status")
    .async(parse.json) { implicit request: Request[JsValue] =>

      logger.info(s"Received get file status request for status: $status")

      FileStatus.values.find(_.toString.equalsIgnoreCase(status)) match {
        case Some(fileStatus) =>
          fileUploadRepo.findByStatus(fileStatus).map {
            case Some(uploadStatusDetails) => Ok(Json.toJson(uploadStatusDetails))
            case None => NoContent
          }.recover {
            case e: Exception =>
              logger.error(s"Error retrieving file status: ${e.getMessage}", e)
              InternalServerError(Json.toJson(ApiErrorResponse("INTERNAL_ERROR", "An error occurred")))
          }
        case None =>
          logger.warn(s"STATUS_INVALID Invalid status: $status")
          Future.successful(BadRequest(Json.toJson(ApiErrorResponse("STATUS_INVALID", "Invalid status"))))
      }
    }
}