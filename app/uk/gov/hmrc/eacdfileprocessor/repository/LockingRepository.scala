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

import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions, Updates}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.JobLock
import uk.gov.hmrc.eacdfileprocessor.selectors.JobLockSelectors
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs.logger
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LockingRepository @Inject()(mongo: MongoComponent,
                                  config: AppConfig,
                                  implicit val ec: ExecutionContext,
                                  metrics: MetricsReporter)
  extends PlayMongoRepository[JobLock](
    mongoComponent = mongo,
    collectionName = "job-locks",
    domainFormat = JobLock.given_Format_JobLock,
    indexes = Seq(
      IndexModel(
        ascending("job"),
        IndexOptions()
          .name("Job")
          .unique(true)
          .sparse(false)
      )
    )
  ) {

  val lockDuration: Int = config.lockTimeoutMinutes

  def lockJob(job: String): Future[Boolean] = {
    val now = Instant.now()
    val newDocument = Updates.set("lockCreatedAt", Codecs.toBson(now)(using MongoJavatimeFormats.instantFormat))

    metrics.timeCompletionOfFuture("lockJobFindMongoTimer", {
      collection.find(JobLockSelectors.jobLockedOf(job)).toFuture().map(_.toSeq).flatMap {
        case Seq(JobLock(_, time)) if time.plus(lockDuration, ChronoUnit.MINUTES).isBefore(Instant.now()) =>
          metrics.timeCompletionOfFuture("lockJobUpdateMongoTimer", {
            collection.updateOne(JobLockSelectors.jobLockedOf(job), newDocument).toFuture() map { uwr =>
              uwr.wasAcknowledged() -> uwr.getMatchedCount match {
                case (true, 1) => true
                case (_, 0) =>
                  logger.error(s"[lockJob] - $job was not locked")
                  false
                case (_, _) =>
                  logger.error(s"[lockJob] - There was a problem locking $job")
                  false
              }
            }
          })
        case Seq(JobLock(_, _)) =>
          logger.warn(s"[lockJob] - $job is still locked")
          Future.successful(false)
        case _ =>
          metrics.timeCompletionOfFuture("lockJobInsertMongoTimer", {
            collection.insertOne(JobLock(job, Instant.now())).toFuture() map { wr =>
              if (wr.wasAcknowledged()) {
                logger.info(s"[lockJob] - Locking $job")
                true
              } else {
                logger.error(s"[lockJob] - There was a problem locking $job")
                false
              }
            }
          })
      }
    })
  }

  def isJobLocked(job: String): Future[Boolean] = {
    metrics.timeCompletionOfFuture("isJobLockedMongoTimer", {
      collection.find(JobLockSelectors.jobLockedOf(job)).toFuture().map(_.toSeq).map {
        case Seq(JobLock(_, time)) if time.plus(lockDuration, ChronoUnit.MINUTES).isBefore(Instant.now()) =>
          false
        case Seq(JobLock(_, _)) =>
          logger.warn(s"[isJobLocked] - $job is currently locked")
          true
        case _ => false
      }
    })
  }

  def releaseLock(job: String): Future[Boolean] = {
    metrics.timeCompletionOfFuture("releaseLockMongoTimer", {
      collection.deleteOne(JobLockSelectors.jobLockedOf(job)).toFuture().map { wr =>
        if (wr.wasAcknowledged()) {
          logger.info(s"[releaseLock] - Releasing lock on $job")
          true
        } else {
          logger.error(s"[releaseLock] - There was a problem release lock on $job")
          false
        }
      }
    })
  }
}
