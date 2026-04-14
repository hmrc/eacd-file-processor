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

package uk.gov.hmrc.eacdfileprocessor.controllers.testonly

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.eacdfileprocessor.services.EnrolmentStoreProxyWorkItemService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Test-only endpoint to generate a controlled outbound connector burst.
 *
 * This lets us prove throttling directly on EnrolmentStoreProxyConnector by
 * firing N connector calls concurrently from inside the service.
 */
@Singleton
class EnrolmentStoreProxyThrottlingController @Inject()(
  workItemService: EnrolmentStoreProxyWorkItemService,
  cc:              ControllerComponents
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  private val MaxAllowed = 500

  /**
   * POST /test-only/throttle/enrolment-store-proxy/:count
   *
   * Optional query parameters (used by simulate_max_concurrency_timeout.sh):
   *   stubDelayMs        — forwarded to the stub as ?delayMs=N so each connector call
   *                        holds a semaphore permit for that many milliseconds.
   *   connectorTimeoutMs — logged in the response body for observability; the actual
   *                        HTTP client timeout is governed by application.conf / Play
   *                        defaults and is not overridden here at runtime.
   */
  def fireBurst(count: Int): Action[AnyContent] = Action.async { implicit request =>
    if (count <= 0 || count > MaxAllowed) {
      Future.successful(BadRequest(Json.obj(
        "error" -> s"count must be between 1 and $MaxAllowed"
      )))
    } else {
      val stubDelayMs = request.queryString
        .get("stubDelayMs")
        .flatMap(_.headOption)
        .flatMap(s => scala.util.Try(s.toLong).toOption)
        .filter(_ > 0L)
        .getOrElse(0L)

      val connectorTimeoutMs = request.queryString
        .get("connectorTimeoutMs")
        .flatMap(_.headOption)
        .flatMap(s => scala.util.Try(s.toLong).toOption)
        .filter(_ > 0L)
        .getOrElse(0L)

      logger.info(
        s"[EnrolmentStoreProxyThrottlingController][fireBurst] " +
        s"count=$count stubDelayMs=$stubDelayMs connectorTimeoutMs=$connectorTimeoutMs"
      )

      val start = System.currentTimeMillis()

      workItemService.fireBurst(count, stubDelayMs).map { _ =>
        val elapsedMs = System.currentTimeMillis() - start
        Ok(Json.obj(
          "triggered"          -> count,
          "elapsedMs"          -> elapsedMs,
          "stubDelayMs"        -> stubDelayMs,
          "connectorTimeoutMs" -> connectorTimeoutMs,
          "message"            -> "Connector burst completed through throttled EnrolmentStoreProxyConnector"
        ))
      }
    }
  }
}
