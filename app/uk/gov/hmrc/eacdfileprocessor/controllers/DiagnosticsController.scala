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

package uk.gov.hmrc.eacdfileprocessor.controllers

import play.api.libs.json.{Json, OWrites}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.eacdfileprocessor.services.{ServiceThrottleState, ThrottlingService, ThrottlingStatus}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/**
 * Controller for monitoring and diagnostics.
 * Provides real-time visibility into the enrolment-store-proxy throttling state.
 */
@Singleton()
class DiagnosticsController @Inject()(
  throttlingService: ThrottlingService,
  cc:                ControllerComponents
)(implicit ec: ExecutionContext) extends BackendController(cc) {

  private implicit val serviceStateWrites: OWrites[ServiceThrottleState] = Json.writes[ServiceThrottleState]
  private implicit val statusWrites:       OWrites[ThrottlingStatus]     = Json.writes[ThrottlingStatus]

  /**
   * GET /admin/throttle/status
   *
   * Returns the current state of the enrolment-store-proxy throttle controls:
   *   - maxConcurrent              → configured concurrency cap
   *   - availablePermits           → unused concurrency slots right now
   *   - currentlyProcessing        → in-flight requests right now
   *   - maxPerSecond               → configured per-second rate cap (0 = unlimited)
   *   - tokensRemainingThisSecond  → tokens still available this second (-1 = unlimited)
   */
  def throttlingStatus: Action[AnyContent] = Action.async { _ =>
    scala.concurrent.Future.successful(
      Ok(Json.toJson(throttlingService.getThrottlingStatus))
    )
  }
}
