/*
 * Copyright 2024 HM Revenue & Customs
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
import org.mongodb.scala.model.*
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex, descending}
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[DeEnrolmentWorkItemMongoRepository])
trait DeEnrolmentWorkItemRepository {
  def saveRecordDetails(deEnrolmentWorkItems: Seq[DeEnrolmentWorkItem], reference: String): Future[Seq[WorkItem[DeEnrolmentWorkItem]]]
}

@Singleton
class DeEnrolmentWorkItemMongoRepository @Inject()(mongo: MongoComponent,
                                                   appConfig: AppConfig)
                                                  (implicit ec: ExecutionContext)
  extends WorkItemRepository[DeEnrolmentWorkItem](
    collectionName = "de-enrolment-work-item",
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
}
