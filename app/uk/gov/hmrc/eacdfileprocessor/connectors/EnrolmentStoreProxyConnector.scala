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
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Connector for the enrolment-store-proxy service.
 *
 * Burst orchestration (chunking and in-flight caps) is handled by
 * [[uk.gov.hmrc.eacdfileprocessor.services.EnrolmentStoreProxyWorkItemService]]
 * using Mongo WorkItems. This connector is intentionally a thin outbound HTTP layer.
 */
@Singleton
class EnrolmentStoreProxyConnector @Inject()(
  httpClient:         HttpClientV2,
  appConfig:          AppConfig
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Sends a single file notification to enrolment-store-proxy.
   *
   * @param fileReference  the unique reference for the file being forwarded
   * @param stubDelayMs    optional: if > 0, appends `?delayMs=N` to the request URL so
   *                       the test-only stub holds the connection open for N milliseconds.
   *                       This is used by the max-concurrency simulation script to prove
   *                       that the semaphore saturates when the downstream is slow.
   *                       Has no effect against the real enrolment-store-proxy.
   * @param hc             implicit [[HeaderCarrier]] forwarded to the downstream service
   * @return               a [[Future]] that completes when the downstream service responds
   */
  def sendFileNotification(fileReference: String, stubDelayMs: Long = 0L)(implicit hc: HeaderCarrier): Future[Unit] =
    {
      val base = s"${appConfig.enrolmentStoreProxyBaseUrl}/test-only/enrolment-store-proxy/file-notification/$fileReference"
      val url  = if (stubDelayMs > 0L) s"$base?delayMs=$stubDelayMs" else base
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
