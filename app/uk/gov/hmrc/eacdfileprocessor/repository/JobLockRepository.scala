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

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model.{FindOneAndUpdateOptions, IndexModel, IndexOptions, ReturnDocument}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.JobLock
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JobLockRepository @Inject()(mongoComponent: MongoComponent, appConfig: AppConfig, clock: Clock)(using ExecutionContext)
  extends PlayMongoRepository[JobLock](
    collectionName = "job-locks",
    mongoComponent = mongoComponent,
    domainFormat = {
      import JobLock.given
      summon[play.api.libs.json.Format[JobLock]]
    },
    indexes = Seq(IndexModel(org.mongodb.scala.model.Indexes.ascending("job"), IndexOptions().name("job").unique(true))),
    replaceIndexes = true
  ) {

  private val lockDurationMinutes: Long = appConfig.lockTimeoutMinutes.toLong

  override lazy val requiresTtlIndex: Boolean = false

  def lockJob(job: String): Future[Boolean] =
    collection.find(equal("job", job)).headOption().flatMap {
      case Some(existing) if existing.lockCreatedAt.plus(lockDurationMinutes, ChronoUnit.MINUTES).isAfter(Instant.now(clock)) =>
        Future.successful(false)
      case Some(_) =>
        collection
          .findOneAndUpdate(
            filter = equal("job", job),
            update = set("lockCreatedAt", Codecs.toBson(Instant.now(clock))(using MongoJavatimeFormats.instantFormat)),
            options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
          )
          .toFutureOption()
          .map(_.isDefined)
      case None =>
        collection.insertOne(JobLock(job, Instant.now(clock))).toFuture().map(_.wasAcknowledged())
    }

  def releaseLock(job: String): Future[Boolean] =
    collection.deleteOne(equal("job", job)).toFuture().map(_.wasAcknowledged())
}




