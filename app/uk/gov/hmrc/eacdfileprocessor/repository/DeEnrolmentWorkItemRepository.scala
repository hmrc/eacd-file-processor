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

import com.google.inject.ImplementedBy
import org.bson.types.ObjectId
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex, descending}
import org.mongodb.scala.model.Filters.{and, equal, lte}
import org.mongodb.scala.model.*
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[DeEnrolmentWorkItemMongoRepository])
trait DeEnrolmentWorkItemRepository {
  def saveRecordDetails(deEnrolmentWorkItems: Seq[DeEnrolmentWorkItem], reference: String): Future[Seq[WorkItem[DeEnrolmentWorkItem]]]
  def pullOutstandingBatch(limit: Int): Future[Seq[WorkItem[DeEnrolmentWorkItem]]]
  def markAsInProgress(id: ObjectId): Future[Boolean]
}

@Singleton
class DeEnrolmentWorkItemMongoRepository @Inject()(mongo: MongoComponent,
                                                   appConfig: AppConfig)
                                                  (implicit ec: ExecutionContext)
  extends WorkItemRepository[DeEnrolmentWorkItem](
    collectionName = "de-enrolment-work-items",
    mongoComponent = mongo,
    itemFormat = DeEnrolmentWorkItem.format,
    workItemFields = WorkItemFields.default,
    replaceIndexes = false
  ) with DeEnrolmentWorkItemRepository with Logging {

  override def now(): Instant = Instant.now()

  override lazy val inProgressRetryAfter: Duration = Duration.ofSeconds(appConfig.retryInProgressAfter)

  override def ensureIndexes(): Future[Seq[String]] = {
    lazy val ttlInHours = appConfig.workItemTimeToLive
    val WORK_ITEM_STATUS = WorkItemFields.default.status
    val WORK_ITEM_UPDATED_AT = WorkItemFields.default.updatedAt
    lazy val deEnrolmentWorkItemIndexes = {
      indexes ++ Seq(
        IndexModel(
          keys = descending("creationDateTime"),
          indexOptions = IndexOptions()
            .name("creationDateTime")
            .unique(false)
            .expireAfter(ttlInHours.toLong, TimeUnit.HOURS)
        ),
        IndexModel(
          keys = ascending("reference"),
          indexOptions = IndexOptions()
            .name("reference")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending("reference"),
            descending(WORK_ITEM_STATUS)
          ),
          indexOptions = IndexOptions()
            .name(s"reference-$WORK_ITEM_STATUS-index")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending(WORK_ITEM_STATUS),
            descending(WORK_ITEM_UPDATED_AT)
          ),
          indexOptions = IndexOptions()
            .name(s"$WORK_ITEM_STATUS-$WORK_ITEM_UPDATED_AT-index")
            .unique(false)
        )
      )
    }
    MongoUtils.ensureIndexes(collection, deEnrolmentWorkItemIndexes, true)
  }

  override def saveRecordDetails(deEnrolmentWorkItems: Seq[DeEnrolmentWorkItem], reference: String): Future[Seq[WorkItem[DeEnrolmentWorkItem]]] =
    pushNewBatch(deEnrolmentWorkItems, now(), _ => ToDo)

  override def pullOutstandingBatch(limit: Int): Future[Seq[WorkItem[DeEnrolmentWorkItem]]] = {
    val availableBefore = now()
    val WORK_ITEM_STATUS = WorkItemFields.default.status
    val WORK_ITEM_AVAILABLE_AT = WorkItemFields.default.availableAt

    def pullToDoItem(): Future[Option[WorkItem[DeEnrolmentWorkItem]]] =
      collection
        .find(
          and(
            equal(WORK_ITEM_STATUS, ProcessingStatus.ToDo),
            lte(WORK_ITEM_AVAILABLE_AT, availableBefore)
          )
        )
        .headOption()

    def loop(acc: Vector[WorkItem[DeEnrolmentWorkItem]], remaining: Int): Future[Vector[WorkItem[DeEnrolmentWorkItem]]] =
      if (remaining <= 0) {
        Future.successful(acc)
      } else {
        pullToDoItem().flatMap {
          case Some(workItem) =>
            markAsInProgress(workItem.id).flatMap {
              case true  => loop(acc :+ workItem.copy(status = ProcessingStatus.InProgress), remaining - 1)
              case false => loop(acc, remaining)
            }
          case None => Future.successful(acc)
        }
      }

    loop(Vector.empty, limit)
  }

  override def markAsInProgress(id: ObjectId): Future[Boolean] =
    collection
      .findOneAndUpdate(
        and(
          equal(WorkItemFields.default.id, id),
          equal(WorkItemFields.default.status, ProcessingStatus.ToDo.name)
        ),
        Updates.combine(
          Updates.set(WorkItemFields.default.status, ProcessingStatus.InProgress.name),
          Updates.set(WorkItemFields.default.updatedAt, now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()
      .map(_.isDefined)


}
