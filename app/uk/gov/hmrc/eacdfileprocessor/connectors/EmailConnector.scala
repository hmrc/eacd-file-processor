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

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Json, OWrites}
import play.api.Configuration
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

case class SendEmailRequest(to: Seq[String], templateId: String, parameters: Map[String, String])

object SendEmailRequest {
  implicit val writes: OWrites[SendEmailRequest] = Json.writes[SendEmailRequest]
}

trait EmailConnector {

  def sendEmail(requestorName: String, fileName: String, uploadDateTime: Instant, to: String,
                reference: String, failureReason: String, failureMessage: String, templateId: String)(
    implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean]

}

@Singleton
class EmailConnectorImpl @Inject()(http: HttpClientV2, val runModeConfiguration: Configuration,
                                   val servicesConfig: ServicesConfig)
  extends EmailConnector {
  lazy val serviceUrl: String = s"${servicesConfig.baseUrl("email")}/hmrc/email"

  def sendEmail(requestorName: String, fileName: String, uploadDateTime: Instant, to: String,
                reference: String, failureReason: String, failureMessage: String, templateId: String)
               (implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] = {


    val params = Map(
      "requestorName" -> requestorName,
      "fileName" -> fileName,
      "uploadedDateTime" -> uploadDateTime.toString,
      "reference " -> reference ,
      "failureReason" -> failureReason,
      "failureMessage" -> failureMessage
    )

    http.post(url"$serviceUrl").withBody(Json.toJson(SendEmailRequest(Seq(to), templateId, params))).execute.map { resp =>
      resp.status match {
        case 202 => true
        case _ => false
      }
    }.recover {
      case _ => false
    }
  }
}