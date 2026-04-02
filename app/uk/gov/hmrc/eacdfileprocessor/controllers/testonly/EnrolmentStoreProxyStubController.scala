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
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}

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
class EnrolmentStoreProxyStubController @Inject()(cc: ControllerComponents)
    extends BackendController(cc) with Logging {

  /**
   * Stub endpoint: accepts any fileReference and returns 200 OK.
   *
   * Logs the received reference so you can verify requests are arriving
   * at the expected throttled rate when running the simulation script.
   */
  def receiveFileNotification(fileReference: String): Action[AnyContent] = Action { _ =>
    logger.info(s"[EnrolmentStoreProxyStub][receiveFileNotification] Received notification for reference=$fileReference")
    Ok(Json.obj(
      "status"        -> "accepted",
      "fileReference" -> fileReference,
      "message"       -> "Stub: enrolment-store-proxy received file notification"
    ))
  }
}

