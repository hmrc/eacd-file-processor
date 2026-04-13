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

import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * Test-only stub that simulates the enrolment-store-proxy service.
 *
 * This controller MUST NOT be wired in prod.routes. It is only available when the
 * application is started with:
 *   -Dapplication.router=testOnlyDoNotUseInAppConf.Routes
 *
 * Purpose:
 *   Provides a local target for [[uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector]]
 *   so that connector-level throttling can be exercised in isolation without
 *   requiring the real enrolment-store-proxy to be running.
 *
 * Route:
 *   GET /test-only/enrolment-store-proxy/file-notification/:fileReference
 *
 * Response:
 *   200 OK — always, to simulate a healthy downstream
 */
@Singleton
class EnrolmentStoreProxyStubController @Inject()(
  cc:          ControllerComponents,
  actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends BackendController(cc) with Logging {

  /**
   * Stub endpoint: accepts any fileReference and returns 200 OK.
   *
   * Supports an optional `?delayMs=N` query parameter.  When supplied, the
   * response is deliberately held open for N milliseconds before replying.
   * This simulates a slow or timing-out downstream and is used by
   * `simulate_max_concurrency_timeout.sh` to saturate the concurrency semaphore:
   *
   *   GET /test-only/enrolment-store-proxy/file-notification/ref-001?delayMs=8000
   *
   * Each in-flight connector call holds a semaphore permit for the full delay
   * duration, demonstrating the max-concurrency saturation scenario.
   *
   * The delay is implemented via Akka's scheduler — it consumes NO dispatcher
   * thread while waiting.  This prevents thread starvation when all concurrency
   * slots are held simultaneously by in-process stub calls.
   *
   * Without `delayMs` (or delayMs=0) the stub responds immediately as before.
   */
  def receiveFileNotification(fileReference: String): Action[AnyContent] = Action.async { request =>
    val delayMs = request.queryString
      .get("delayMs")
      .flatMap(_.headOption)
      .flatMap(s => scala.util.Try(s.toLong).toOption)
      .filter(_ > 0L)
      .getOrElse(0L)

    logger.info(
      s"[EnrolmentStoreProxyStub][receiveFileNotification] " +
      s"reference=$fileReference delayMs=$delayMs"
    )

    val body = Json.obj(
      "status"        -> "accepted",
      "fileReference" -> fileReference,
      "delayMs"       -> delayMs,
      "message"       -> "Stub: enrolment-store-proxy received file notification"
    )

    if (delayMs <= 0L) {
      Future.successful(Ok(body))
    } else {
      // Use Akka scheduler: zero threads are blocked during the delay.
      // All N concurrent stub requests can therefore be in-flight at the same
      // time even though they share the same dispatcher.
      val promise = Promise[play.api.mvc.Result]()
      actorSystem.scheduler.scheduleOnce(delayMs.millis) {
        logger.info(
          s"[EnrolmentStoreProxyStub][receiveFileNotification] " +
          s"Delay elapsed for reference=$fileReference — responding now"
        )
        promise.success(Ok(body))
      }
      promise.future
    }
  }
}

