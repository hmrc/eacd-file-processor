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

package uk.gov.hmrc.eacdfileprocessor.connectors

import play.api.http.Status.ACCEPTED
import play.api.libs.json.{Json, OWrites}
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class SendEmailRequest(to: Seq[String], templateId: String, parameters: Map[String, String])

object SendEmailRequest {
  implicit val writes: OWrites[SendEmailRequest] = Json.writes[SendEmailRequest]
}

trait EmailConnector {

  def sendEmail(params: Map[String, String], to: String, templateId: String)
               (implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean]
}

@Singleton
class EmailConnectorImpl @Inject()(http: HttpClientV2,
                                   val runModeConfiguration: Configuration,
                                   val servicesConfig: ServicesConfig) extends EmailConnector with Logging {

  lazy val serviceUrl: String = s"${servicesConfig.baseUrl("email")}/hmrc/email"

  def sendEmail(params: Map[String, String], to: String, templateId: String)
               (implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] = {

    http.post(url"$serviceUrl")
      .withBody(Json.toJson(SendEmailRequest(Seq(to), templateId, params)))
      .execute[HttpResponse]
      .map { resp =>
        resp.status match {
          case ACCEPTED => true
          case _ => false
        }
      }.recover {
        case e =>
          logger.error(s"issue encountered while sending email ${e.getMessage}")
          false
      }
  }
}