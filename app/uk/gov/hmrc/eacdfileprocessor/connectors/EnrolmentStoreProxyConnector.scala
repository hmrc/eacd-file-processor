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
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.services.ThrottlingService
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Connector for the enrolment-store-proxy service.
 *
 * Every outbound call is wrapped in [[ThrottlingService.throttleEnrolmentStoreProxyCall]] so
 * that the downstream service is never bombarded — regardless of how many files arrive in a
 * single submission burst.
 *
 * Throttle limits are configured in application.conf:
 * {{{
 *   throttle.enrolment-store-proxy {
 *     max-concurrent  = 5   # at most 5 simultaneous requests
 *     max-per-second  = 2   # at most 2 new requests started per second
 *   }
 * }}}
 */
@Singleton
class EnrolmentStoreProxyConnector @Inject()(
  httpClient:         HttpClientV2,
  appConfig:          AppConfig,
  throttlingService:  ThrottlingService
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Sends a single file notification to enrolment-store-proxy.
   *
   * The call passes through both throttle gates before the HTTP request is
   * dispatched, ensuring a controlled, predictable outbound rate.
   *
   * @param fileReference  the unique reference for the file being forwarded
   * @param hc             implicit [[HeaderCarrier]] forwarded to the downstream service
   * @return               a [[Future]] that completes when the downstream service responds
   */
  def sendFileNotification(fileReference: String)(implicit hc: HeaderCarrier): Future[Unit] =
    throttlingService.throttleEnrolmentStoreProxyCall {
      val url = s"${appConfig.enrolmentStoreProxyBaseUrl}/test-only/enrolment-store-proxy/file-notification/$fileReference"
      logger.info(s"[EnrolmentStoreProxyConnector][sendFileNotification] Sending notification for reference=$fileReference to $url")

      httpClient
        .get(url"$url")
        .execute[HttpResponse]
        .flatMap { response =>
          logger.info(s"[EnrolmentStoreProxyConnector][sendFileNotification] Response status=${response.status} for reference=$fileReference")
          Future.unit
        }
    }
}
