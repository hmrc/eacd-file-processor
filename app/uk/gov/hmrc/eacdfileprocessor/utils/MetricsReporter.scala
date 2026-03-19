/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.eacdfileprocessor.utils

import com.codahale.metrics.MetricRegistry

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class MetricsReporter @Inject()(metrics: MetricRegistry) {

  private def incrementCounter(counterName: String): Unit = {
    metrics.counter(counterName).inc()
  }

  def markSuccessfulWrite(): Unit = {
    incrementCounter("mongo.writeSuccess")
  }

  def markFailedWrite(): Unit = {
    incrementCounter("mongo.writeFail")
  }

  def markSuccessfulRead(): Unit = {
    incrementCounter("mongo.readSuccess")
  }

  def markFailedRead(): Unit = {
    incrementCounter("mongo.readFail")
  }

  def timeCompletionOfFuture[T](timer: String, future: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val time = metrics.timer(timer).time()
    future.onComplete { _ => time.stop() }
    future
  }

}

object MetricsReporter {

  sealed trait MongoOp
  object MongoRead extends MongoOp
  object MongoWrite extends MongoOp

  implicit class MongoMetricReporter[T](future: Future[T]) {

    def withMetrics(metrics: MetricsReporter, op: MongoOp)(implicit ec: ExecutionContext): Future[T] = {
      future.onComplete {
        case Success(_) => op match {
          case MongoRead => metrics.markSuccessfulRead()
          case MongoWrite => metrics.markSuccessfulWrite()
        }
        case Failure(_) => op match {
          case MongoRead => metrics.markFailedRead()
          case MongoWrite => metrics.markFailedWrite()
        }
      }
      future
    }

  }

}
