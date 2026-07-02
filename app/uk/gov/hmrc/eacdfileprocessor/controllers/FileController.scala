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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.models.{Details, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.AuditService
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits.*
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileController @Inject()(
                                fileUploadRepo: FileRepository,
                                val cc: ControllerComponents,
                                val configuration: Configuration,
                                val auth: BackendAuthComponents,
                                val auditService: AuditService,
                                val objectStoreClient: PlayObjectStoreClient
                              )(implicit ec: ExecutionContext) extends BackendController(cc) with InternalAuthBuilders with Logging {
  val providedPermission = Predicate.Permission(
    Resource(ResourceType("eacd-file-processor"), ResourceLocation("file")),
    IAAction("ADMIN")
  )

  def getFile(reference: String): Action[AnyContent] = authorisedEntity(providedPermission, "file")
    .async { implicit request: AuthRequest[AnyContent] =>
      fileUploadRepo.findByReference(Reference(reference)).flatMap {
        case Some(uploadDetails) =>
          uploadDetails.details.collect { case Details.UploadedSuccessfully(name, _, _, _, _) => name } match {
            case Some(fileName) =>
              val fileLocation = Path.Directory(reference).file(fileName)
              objectStoreClient.getObject[Source[ByteString, NotUsed]](fileLocation).map {
                _.map { o =>
                  auditService.auditDownloadFileEvent(uploadDetails, fileName)
                  Ok.chunked(o.content)
                }.getOrElse {
                  logger.warn("No record found for the requested reference in Object Store")
                  NoContent
                }
              }
            case None =>
              logger.warn("No uploaded file details found for the requested reference")
              Future.successful(NoContent)
          }
        case None =>
          logger.warn("No record found for the requested reference")
          Future.successful(NoContent)
      }
    }
}
