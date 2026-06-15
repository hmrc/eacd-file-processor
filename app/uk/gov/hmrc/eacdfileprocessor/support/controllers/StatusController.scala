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

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.services.StatusService
import uk.gov.hmrc.eacdfileprocessor.utils.InternalAuthBuilders
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StatusController @Inject()(
                                  fileUploadRepo: FileRepository,
                                  val cc: ControllerComponents,
                                  val configuration: Configuration,
                                  val auth: BackendAuthComponents
                                )(implicit ec: ExecutionContext) extends BackendController(cc) with InternalAuthBuilders with Logging {
  val providedPermission: Predicate = Predicate.Permission(
    Resource(ResourceType("eacd-file-processor"), ResourceLocation("getStatusCounts")),
    IAAction("ADMIN")
  )

  val fileStatus: Seq[FileStatus] = Seq(SCANNED, FAILED, STORED, UPLOADED, UPLOADREJECTED, REJECTED, APPROVED,
    PROCESSING, PROCESSEDWITHERRORS, PROCESSEDSUCCESSFULLY)
  
  def getAllStatusCounts: Action[AnyContent] = authorisedEntity(providedPermission, "getStatusCounts")
    .async { implicit request: Request[AnyContent] =>
      fileUploadRepo.getFileStatusCounts.map {
        case fileStatusCounts if fileStatusCounts.nonEmpty =>
          val allStatusCounts = if (fileStatusCounts.size < 10) {
            generateAllStatusCount(fileStatusCounts)
          } else {
            fileStatusCounts
          }
          Ok(Json.toJson(allStatusCounts))
        case _ => NoContent
      }
    }

  private[controllers] def generateAllStatusCount(statusCounts: Seq[FileStatusCount]): Seq[FileStatusCount] = {
    @tailrec
    def allStatusCountAcc(accStatusCount: Seq[FileStatusCount], remainingStatuses: Seq[FileStatus]): Seq[FileStatusCount] = {
      remainingStatuses match {
        case Nil => accStatusCount
        case head :: tail =>
          val countForStatus = statusCounts
            .find(fileStatusCount => FileStatus.valueOf(fileStatusCount.status.toUpperCase) == head)
            .getOrElse(FileStatusCount(head.value, 0))
          allStatusCountAcc(accStatusCount :+ countForStatus, tail)
      }
    }

    allStatusCountAcc(Seq.empty, fileStatus)
  }
}