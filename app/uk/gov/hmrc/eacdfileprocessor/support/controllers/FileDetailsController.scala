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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.*
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileUploadRepoFormat.mongoFormat
import uk.gov.hmrc.eacdfileprocessor.repository.{FileRepository, FileUploadRepoFormat}
import uk.gov.hmrc.eacdfileprocessor.services.FileDetailService
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits.*
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class FileDetailsController @Inject()(
                                       val cc: ControllerComponents,
                                       val configuration: Configuration,
                                       val auth: BackendAuthComponents,
                                       val objectStoreClient: PlayObjectStoreClient,
                                       repository: FileRepository,
                                       fileDetailService: FileDetailService
                                     )(implicit ec: ExecutionContext, actor: ActorSystem) extends BackendController(cc) with InternalAuthBuilders with Logging {

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

  def getFileDetail(reference: String): Action[AnyContent] = authorisedEntity(providedPermission, "file-detail")
    .async { implicit request: AuthRequest[AnyContent] =>
      fileDetailService.getFileDetail(reference)
        .map {
          case Some(details) => Ok(Json.toJson(details)(mongoFormat))
          case None => NoContent
        }
        .recover {
          case e: Exception =>
            logger.error(s"Error retrieving details for reference '$reference'", e)
            InternalServerError("Error retrieving details")
        }
    }

}