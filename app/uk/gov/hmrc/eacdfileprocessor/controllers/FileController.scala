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
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.Reference
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.objectstore.client.play.Implicits.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileController @Inject()(
                                  fileUploadRepo: FileRepository,
                                  val cc: ControllerComponents,
                                  val configuration: Configuration,
                                  val auth: BackendAuthComponents,
                                  val objectStoreClient: PlayObjectStoreClient
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

  def getFile(reference: String): Action[AnyContent] = authorisedEntity(providedPermission, "status")
    .async { implicit request: Request[AnyContent] =>
        fileUploadRepo.getNameOfFile(Reference(reference)).flatMap {
          case Some(fileName) =>
            val fileLocation = Path.Directory(s"$reference/$fileName").file(fileName)
            objectStoreClient.getObject[Source[ByteString, NotUsed]](fileLocation).map {
              _.map { o =>
                Ok.chunked(o.content)
              }.getOrElse {
                logger.warn("No record found for the requested reference in Object Store")
                NoContent
              }
            }
          case _ =>
            logger.warn("No record found for the requested reference")
            Future.successful(NoContent)
        }
    }

}