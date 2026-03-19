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

import org.bson.types.ObjectId
import play.api.libs.json.*
import play.api.mvc.{Action, ControllerComponents, Request, Result}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.exceptions.DuplicateReferenceException
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, HelpdeskInitiateRequestModel, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.{InternalAuthBuilders, ValidationUtil}
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.Future

class initiateFileStorageController @Inject()(
                                               val fileRepo: FileRepository,
                                               val cc: ControllerComponents,
                                               val configuration: Configuration,
                                               val auth: BackendAuthComponents
                                             )(implicit ec: scala.concurrent.ExecutionContext)
  extends BackendController(cc) with InternalAuthBuilders with Logging {

  def initiateFileRecordStore(): Action[JsValue] =
    authorisedEntity(
      providedPermission = Predicate.Permission(
        Resource(ResourceType("eacd-file-processor"), ResourceLocation("services-enrolments-helpdesk-frontend")),
        IAAction("ADMIN")
      ),
      apiName = "initiate"
    ).async(parse.json) { implicit request: Request[JsValue] =>
      validateJsonBody { initiateRequestModel =>
        fileRepo.createFileRecord(UploadedDetails(ObjectId.get(), Reference(initiateRequestModel.reference), "initial",
          initiateRequestModel.requestorPID, initiateRequestModel.requestorEmail, initiateRequestModel.requestorName)).map {
          case true =>
            Created
          case _ =>
            InternalServerError(Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred")))
        }.recover {
          case e: DuplicateReferenceException =>
            BadRequest(Json.toJson(ApiErrorResponse("DUPLICATE_EXTERNAL_FILE_REF", "Duplicate external file reference")))
          case e: Exception =>
            logger.error(s"Error creating file record: ${e.getMessage}", e)
            InternalServerError(Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred")))
        }
      }
    }

  private def validateJsonBody(f: HelpdeskInitiateRequestModel => Future[Result])
                              (implicit request: Request[JsValue], reads: Reads[HelpdeskInitiateRequestModel]): Future[Result] = {
    request.body.validate[HelpdeskInitiateRequestModel] match {
      case JsError(errors) =>
        errors.head._1.toString match {
          case "/reference" | "/requestorPID" | "/requestorEmail" | "/requestorName" =>
            logger.warn("MANDATORY_FIELDS_MISSING Mandatory fields missing")
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing"))))
          case _ =>
            Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_JSON", "Invalid JSON payload"))))
        }
      case JsSuccess(payload, _) =>
        if (!ValidationUtil.isEmailValid(payload.requestorEmail)) {
          logger.warn("INVALID_REQUESTOR_EMAIL Invalid requestor email")
          Future.successful(BadRequest(Json.toJson(ApiErrorResponse("INVALID_REQUESTOR_EMAIL", "Invalid requestor email"))))
        } else {
          f(payload)
        }
    }
  }
}
