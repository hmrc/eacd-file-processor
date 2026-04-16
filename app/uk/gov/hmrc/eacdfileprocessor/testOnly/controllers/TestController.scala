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

package uk.gov.hmrc.eacdfileprocessor.testOnly.controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.libs.json.{JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.*
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
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
class TestController @Inject()(
                                  val cc: ControllerComponents,
                                  val configuration: Configuration,
                                  val auth: BackendAuthComponents,
                                  val objectStoreClient: PlayObjectStoreClient
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

  private val streaming: BodyParser[Source[ByteString, _]] =
    BodyParser: _ =>
      Accumulator.source[ByteString].map(Right.apply)

  def putObject(reference: String, fileName: String): Action[Source[ByteString, _]] =
   Action.async(streaming) { implicit request =>
      
      val fileLocation = Path.Directory(s"$reference/$fileName").file(fileName)
      objectStoreClient
        .putObject(fileLocation, request.body)
        .map(_ => Created("Document stored."))
        .recover:
          case UpstreamErrorResponse(message, statusCode, _, _) =>
            logger.error(s"Upstream error with status code '$statusCode' and message: $message")
            InternalServerError("Upstream error encountered")
          case e: Exception =>
            logger.error(s"An error was encountered saving the document.", e)
            InternalServerError("Error saving the document")
  }

}