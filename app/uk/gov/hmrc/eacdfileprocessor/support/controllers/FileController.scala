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

package uk.gov.hmrc.eacdfileprocessor.support.controllers

import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.Reference
import uk.gov.hmrc.eacdfileprocessor.repository.FileRecordValidationErrorRepository
import uk.gov.hmrc.eacdfileprocessor.services.AuditService
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class FileController @Inject()(fileRecordValidationErrorRepository: FileRecordValidationErrorRepository,
                               val cc: ControllerComponents,
                               val configuration: Configuration,
                               val auth: BackendAuthComponents,
                               val auditService: AuditService,
                               val objectStoreClient: PlayObjectStoreClient
                              )(implicit ec: ExecutionContext) extends BackendController(cc) with InternalAuthBuilders with Logging {
  val emacSupportPermission = Predicate.Permission(
    Resource(ResourceType("eacd-file-processor"), ResourceLocation("file")),
    IAAction("ADMIN")
  )

  val helpdeskPermission = Predicate.Permission(
    Resource(ResourceType("services-enrolments-helpdesk-frontend"), ResourceLocation("file")),
    IAAction("ADMIN")
  )

  private val allowedCallersPredicate: Predicate =
    Predicate.or(emacSupportPermission, helpdeskPermission)

  def getFileErrors(reference: String): Action[AnyContent] =
    authorisedEntity(allowedCallersPredicate, "getFileErrors")
      .async { implicit request: Request[AnyContent] =>
        fileRecordValidationErrorRepository.findByReference(Reference(reference)).map { errors =>
          if (errors.isEmpty) {
            NoContent
          } else {
            Ok(toCsv(errors))
              .as("text/csv; charset=utf-8")
              .withHeaders(
                "Content-Disposition" -> s"""attachment; filename="file-errors-$reference.csv""""
              )
          }
        }
      }

  private def toCsv(errors: Seq[uk.gov.hmrc.eacdfileprocessor.models.FileRecordValidationError]): String = {
    val header = "reference,fileName,recordDetail,errorMessage,creationDateTime"

    val rows = errors.map { error =>
      Seq(
        error.reference.value,
        error.fileName,
        error.recordDetail,
        error.errorMessage,
        error.creationDateTime.toString
      ).map(csvEscape).mkString(",")
    }

    (header +: rows).mkString("\n")
  }

  private def csvEscape(value: String): String = {
    val escaped = Option(value).getOrElse("").replace("\"", "\"\"")
    val mustQuote = escaped.exists(ch => ch == ',' || ch == '"' || ch == '\n' || ch == '\r')
    if (mustQuote) s""""$escaped"""" else escaped
  }

}
