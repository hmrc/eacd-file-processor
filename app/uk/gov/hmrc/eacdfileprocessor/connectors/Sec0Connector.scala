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

import play.api.Logging
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.libs.json.JsValue
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Sec0Connector @Inject()(httpClient: HttpClientV2, appConfig: AppConfig, val servicesConfig: ServicesConfig)(using ExecutionContext) extends Logging {

  private val readRaw: HttpReads[HttpResponse] = HttpReads.Implicits.readRaw
  
  lazy val serviceUrl: String = s"${servicesConfig.baseUrl("service-enrolment-config")}${appConfig.sec0GetServicesPath}?affinityGroup=agent"

  def getAgentServiceKeys()(using HeaderCarrier): Future[Set[String]] =
    httpClient
      .get(url"$serviceUrl")
      .execute(readRaw)
      .map { response =>
        response.status match {
          case OK =>
            logger.info(s"SEC0 lookup returned 200 OK; extracting service list with \\ ${response.body}")
            extractServices(response.json)
          case BAD_REQUEST =>
            logger.warn("SEC0 lookup returned 400 Bad Request; check affinityGroup parameter")
            Set.empty[String]
          case status =>
            logger.warn(s"SEC0 lookup returned unexpected status $status; using empty service list")
            Set.empty[String]
        }
      }

  private def extractServices(json: JsValue): Set[String] =
    (json \ "serviceNames")
      .asOpt[Seq[String]]
      .getOrElse(Seq.empty)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
}

