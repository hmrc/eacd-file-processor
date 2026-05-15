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

package uk.gov.hmrc.eacdfileprocessor.services

import com.codahale.metrics.{Counter, Gauge, MetricRegistry}
import org.bson.conversions.Bson
import org.mongodb.scala.model.{Filters, Sorts}
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.ScheduledService

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class DefaultMetricsService @Inject()(protected val fileRepository: FileRepository,
                                      protected val metrics: MetricRegistry) extends MetricsService {
  override protected val scheduleMetric: Counter = metrics.counter("instance-running-file-sch")
}

trait MetricsService extends Logging with ScheduledService[Unit] {

  protected val metrics: MetricRegistry
  protected val fileRepository: FileRepository

  protected val scheduleMetric: Counter

  override def invoke(implicit ec: ExecutionContext): Future[Unit] = {
    publishMongoStats map { json =>
      logger.info(s"[MetricsJob] - Collected document count metrics => ${Json.prettyPrint(Json.toJson(json))}")
    }
  }

  def setScheduleFileInstance[T](f: => Future[T]): Future[T] = {
    logger.debug("Incrementing schedule file metric")
    scheduleMetric.inc(1)
    val result = f
    logger.debug("Decrementing schedule file metric")
    scheduleMetric.dec(1)
    result
  }

  private val metricsToCollect: Map[String, Bson] = Map(
    "approvedFiles" -> Filters.and(Filters.equal("status", APPROVED.value), Sorts.ascending("lastUpdatedDateTime"))
  )

  def collectMongoStats(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    Future.sequence(metricsToCollect map { case (key, value) => fileRepository.countDocuments(value) map (key -> _) }) map (_.toMap)
  }

  def publishMongoStats(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    collectMongoStats map { metricsMap =>
      metricsMap foreach { case (name, value) => Try(publishMetric(name, value)) }
      metricsMap
    }
  }

  def createMetricsForSupport(implicit ec: ExecutionContext): Future[JsValue] = {
    collectMongoStats map (map => Json.toJson(map))
  }

  private def publishMetric(name: String, value: Int): Unit = {
    logger.debug("[collectMongoStatistics] - attempting to post metrics")
    metrics.remove(name)
    metrics.register(name, makeGauge(value))
  }

  private def makeGauge(value: Int): Gauge[Int] = new Gauge[Int] {
    override def getValue: Int = value
  }
}