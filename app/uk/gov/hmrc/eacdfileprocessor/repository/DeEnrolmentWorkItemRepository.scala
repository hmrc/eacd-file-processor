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
import org.mongodb.scala.bson.conversions.Bson
import org.bson.types.ObjectId
import org.mongodb.scala.model.*
import org.mongodb.scala.model.Filters.{and, equal, lte}
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex, descending}
import org.mongodb.scala.model.Filters.{and, equal, lte}
import org.mongodb.scala.model.*
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ToDo, InProgress}
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import org.mongodb.scala.model.Filters.{and, equal, in}
import uk.gov.hmrc.mongo.play.json.Codecs

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[DeEnrolmentWorkItemMongoRepository])
trait DeEnrolmentWorkItemRepository {
  def saveRecordDetails(deEnrolmentWorkItems: Seq[DeEnrolmentWorkItem], reference: String): Future[Seq[WorkItem[DeEnrolmentWorkItem]]]

  def incompleteWorkItemsCountForRef(reference: String): Future[Int]

  def deleteWorkItemsByReference(reference: String): Future[Unit]

  def pullOutstandingBatch(limit: Int): Future[Seq[WorkItem[DeEnrolmentWorkItem]]]
  def markAsInProgress(id: ObjectId): Future[Boolean]
  def markAsComplete(id: ObjectId): Future[Boolean]
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

  override def incompleteWorkItemsCountForRef(reference: String): Future[Int] = {
    val selector = and(
      equal(
        s"${WorkItemFields.default.item}.reference", Codecs.toBson(reference)
      ),
      in(workItemFields.status, ToDo, InProgress)
    )

    collection.countDocuments(selector).toFuture().map(_.toInt)
  }

  override def deleteWorkItemsByReference(reference: String): Future[Unit] = {
    collection.deleteMany(Filters.eq(s"${WorkItemFields.default.item}.reference", reference)).toFuture().map(_ => ())
  }


  override def saveRecordDetails(deEnrolmentWorkItems: Seq[DeEnrolmentWorkItem], reference: String): Future[Seq[WorkItem[DeEnrolmentWorkItem]]] =
    pushNewBatch(deEnrolmentWorkItems, now(), _ => ToDo)

  override def pullOutstandingBatch(limit: Int): Future[Seq[WorkItem[DeEnrolmentWorkItem]]] = {
    if (limit <= 0) {
      Future.successful(Seq.empty)
    } else {
      val availableBefore = now()
      val WORK_ITEM_STATUS = WorkItemFields.default.status
      val WORK_ITEM_AVAILABLE_AT = WorkItemFields.default.availableAt

      def pullToDoItems(): Future[Seq[WorkItem[DeEnrolmentWorkItem]]] =
        collection
          .find(
            and(
              equal(WORK_ITEM_STATUS, ProcessingStatus.ToDo.name),
              lte(WORK_ITEM_AVAILABLE_AT, availableBefore)
            )
          )
          .limit(limit)
          .toFuture()

      pullToDoItems().flatMap { workItems =>
        workItems.foldLeft(Future.successful(Vector.empty[WorkItem[DeEnrolmentWorkItem]])) {
          (accF, workItem) =>
            accF.flatMap { acc =>
              markAsInProgress(workItem.id).map {
                case true => acc :+ workItem.copy(status = ProcessingStatus.InProgress)
                case false => acc
              }
            }
        }
      }
    }
  }

  override def markAsInProgress(id: ObjectId): Future[Boolean] = {
    collection
      .findOneAndUpdate(
        and(
          equal(WorkItemFields.default.id, id),
          equal(WorkItemFields.default.status, ProcessingStatus.ToDo.name),
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

  override def markAsComplete(id: ObjectId): Future[Boolean] = {
    collection
      .findOneAndUpdate(
        and(
          equal(WorkItemFields.default.id, id),
          equal(WorkItemFields.default.status, ProcessingStatus.InProgress.name)
        ),
        Updates.combine(
          Updates.set(WorkItemFields.default.status, ProcessingStatus.Succeeded.name),
          Updates.set(WorkItemFields.default.updatedAt, now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()
      .map(_.isDefined)
  }


}
