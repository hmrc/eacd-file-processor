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
import play.api.http.Status.OK
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig


class EspConnector @Inject()(httpClient: HttpClientV2, appConfig: AppConfig, val servicesConfig: ServicesConfig)(using ExecutionContext)  extends Logging {

  lazy val serviceUrl: String = s"${servicesConfig.baseUrl("enrolment-store-proxy")}/enrolment-store-proxy/enrolment-store"
  
   def callES1(enrolmentKey:String, actionType:String)(using HeaderCarrier): Future[HttpResponse] =
     httpClient
       .get(url"$serviceUrl/enrolments/$enrolmentKey/groups?type=$actionType")
       .execute[HttpResponse]
  
   def callES9(groupId:String, enrolmentKey:String)(using HeaderCarrier): Future[HttpResponse] =
    httpClient
      .delete(url"$serviceUrl/groups/$groupId/enrolments/$enrolmentKey")
      .execute[HttpResponse]
}
