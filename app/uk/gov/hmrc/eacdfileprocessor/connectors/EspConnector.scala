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

  //ES1 returns 204 then ES9 is not called and given success code
   //ES1 returns single error count as an error and record the "message" from the error response
   //ES1 returns multiple errors count as an error and record the outer "message" from the error response
   //ES1 returns 200 then return success code and record the "message" from the response
   
   def callES1(enrolmentKey:String, actionType:String)(using HeaderCarrier): Future[HttpResponse] =
     httpClient
       .get(url"$serviceUrl/enrolments/$enrolmentKey/groups?type=$actionType")
       .execute[HttpResponse]
  
  //ES9 returns a 500 count as an error status code and record the "message" from the error response and return service unavailable 
  //ES9 receives BOTH which is "principal" and "agent" then 2 ES9 calls need to be made
  //ES9 returns 500 on 1 out of the 2 calls then record the record as an error and use the message "Partial processing due to unknown error, review manually"

   def callES9(groupId:String, enrolmentKey:String)(using HeaderCarrier): Future[HttpResponse] =
    httpClient
      .delete(url"$serviceUrl/groups/$groupId/enrolments/$enrolmentKey")
      .execute[HttpResponse]

}
