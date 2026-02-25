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

import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents, Request}
import uk.gov.hmrc.eacdfileprocessor.models.upscan.CallbackBody
import uk.gov.hmrc.eacdfileprocessor.services.UpscanCallbackService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton()
class CallbackController @Inject()(
                                    upscanCallbackService: UpscanCallbackService,
                                    cc: ControllerComponents
                                  )(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  val callback: Action[JsValue] = Action.async(parse.json) { implicit request: Request[JsValue] =>

    logger.info(s"Received callback notification [${Json.stringify(request.body)}]")

    withJsonBody[CallbackBody] { callbackBody =>
      upscanCallbackService.handleCallback(callbackBody)
        .map(_ => NoContent)
        .recover {
          case e =>
            logger.warn(s"${e.getMessage}")
            NoContent
        }
    }.map { result =>
      result.header.status match
        case BAD_REQUEST => NoContent
        case _ => NoContent
    }
  }
}
