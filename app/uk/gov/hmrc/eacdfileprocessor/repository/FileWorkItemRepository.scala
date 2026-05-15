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

package uk.gov.hmrc.eacdfileprocessor.repository

import org.bson.types.ObjectId
import play.api.libs.json.Json
import uk.gov.hmrc.eacdfileprocessor.models.FileWorkItem
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemFields, WorkItemRepository}

import java.time.{Clock, Duration, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileWorkItemRepository @Inject()(mongoComponent: MongoComponent, clock: Clock)(using ExecutionContext)
  extends WorkItemRepository[FileWorkItem](
    collectionName = "file-work-item",
    mongoComponent = mongoComponent,
    itemFormat = Json.format[FileWorkItem],
    workItemFields = WorkItemFields.default,
    replaceIndexes = true
  ) {

  override def inProgressRetryAfter: Duration = Duration.ofMinutes(30)

  override def now(): Instant = Instant.now(clock)

  def pullOutstandingBatch(limit: Int): Future[Seq[WorkItem[FileWorkItem]]] = {
    val failedBefore = now()
    val availableBefore = now()

    def loop(acc: Vector[WorkItem[FileWorkItem]], remaining: Int): Future[Vector[WorkItem[FileWorkItem]]] =
      if (remaining <= 0) {
        Future.successful(acc)
      } else {
        pullOutstanding(failedBefore = failedBefore, availableBefore = availableBefore).flatMap {
          case Some(workItem) => loop(acc :+ workItem, remaining - 1)
          case None => Future.successful(acc)
        }
      }

    loop(Vector.empty, limit).map(_.toSeq)
  }

  def markAsSucceeded(id: ObjectId): Future[Boolean] =
    complete(id, ProcessingStatus.Succeeded)
}





