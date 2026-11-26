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

package uk.gov.hmrc.eacdfileprocessor.support.controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.DeEnrolmentWorkItemRepository
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class WorkItemController @Inject()(
                                    workItemRepo: DeEnrolmentWorkItemRepository,
                                    val cc: ControllerComponents,
                                    val configuration: Configuration,
                                    val auth: BackendAuthComponents,
                                 )(implicit ec: ExecutionContext) extends BackendController(cc) with InternalAuthBuilders with Logging {
  val providedPermission = Predicate.Permission(
    Resource(ResourceType("eacd-file-processor"), ResourceLocation("workItem")),
    IAAction("ADMIN")
  )

  def getWorkItem(reference: String): Action[AnyContent] = authorisedEntity(providedPermission, "workItem")
    .async { implicit request: AuthRequest[AnyContent] =>
      workItemRepo.findByReference(reference).map {
        case workItems if workItems.isEmpty =>
          logger.warn(s"No work items found for reference: $reference")
          NoContent
        case workItems =>
          Ok(Json.toJson(workItems.map(_.item))).as("text/csv; charset=utf-8")
            .withHeaders("Content-Disposition" -> s"""attachment; filename="work-items-$reference.csv"""")
      }
    }
}
