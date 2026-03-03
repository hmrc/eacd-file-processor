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

import play.api.mvc.{Action, AnyContent, ControllerComponents}
import play.api.{Configuration, Logging}
import play.api.libs.json.Json
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, HelpdeskInitiateRequestModel}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.repository.MongoResponses._
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Resource, ResourceLocation, ResourceType, IAAction}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.Future
import scala.util.matching.Regex

class initiateFileStorageController @Inject()(
  val fileRepo: FileRepository,
  val cc: ControllerComponents,
  val configuration: Configuration,
  val auth: BackendAuthComponents
)(implicit ec: scala.concurrent.ExecutionContext)
  extends BackendController(cc) with InternalAuthBuilders with Logging {

  def initiateFileRecordStore(): Action[AnyContent] = authorisedEntity(
    providedPermission = Predicate.Permission(
      Resource(ResourceType("eacd-file-processor"), ResourceLocation("services-enrolments-helpdesk-frontend")),
      IAAction("ADMIN")
    ),
    apiName = "initiate"
  ).async { implicit request =>
    request.body.asJson match {
      case Some(body) =>
        val referenceOpt = (body \ "reference").asOpt[String]
        val requestorPIDOpt = (body \ "requestorPID").asOpt[String]
        val requestorEmailOpt = (body \ "requestorEmail").asOpt[String]
        val requestorNameOpt = (body \ "requestorName").asOpt[String]

        if (referenceOpt.isEmpty || requestorPIDOpt.isEmpty || requestorEmailOpt.isEmpty || requestorNameOpt.isEmpty) {
          val error = ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing")
          Future.successful(BadRequest(Json.toJson(error)))
        } else if (!isValidEmail(requestorEmailOpt.get)) {
          val error = ApiErrorResponse("INVALID_REQUESTOR_EMAIL", "Invalid requestor email")
          Future.successful(BadRequest(Json.toJson(error)))
        } else {
          val model = HelpdeskInitiateRequestModel(
            referenceOpt.get,
            requestorPIDOpt.get,
            requestorEmailOpt.get,
            requestorNameOpt.get
          )
          fileRepo.createFileRecord(model).map {
            case MongoSuccessCreate => Created
            case MongoDuplicateKey =>
              val error = ApiErrorResponse("DUPLICATE_EXTERNAL_FILE_REF", "Duplicate external file reference")
              BadRequest(Json.toJson(error))
            case _ =>
              val error = ApiErrorResponse("UNKNOWN_ERROR", "Unknown error occurred")
              InternalServerError(Json.toJson(error))
          }
        }
      case None =>
        val error = ApiErrorResponse("MANDATORY_FIELDS_MISSING", "Mandatory fields missing")
        Future.successful(BadRequest(Json.toJson(error)))
    }
  }

  private def isValidEmail(email: String): Boolean = {
    val emailRegex: Regex = """^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$""".r
    emailRegex.matches(email)
  }
}
