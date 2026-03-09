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

package uk.gov.hmrc.eacdfileprocessor.utils

import com.codahale.metrics.MetricRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter.MongoMetricReporter
import scala.concurrent.{ExecutionContext, Future}

class MetricsReporterSpec extends AnyWordSpec with Matchers with ScalaFutures {
  implicit val ec: ExecutionContext = ExecutionContext.global

  trait Setup {
    val registry = new MetricRegistry
    val reporter = new MetricsReporter(registry)
  }

  "MetricsReporter" should {
    "increment counters for success and failure" in new Setup {
      reporter.markSuccessfulWrite()
      reporter.markFailedWrite()
      reporter.markSuccessfulRead()
      reporter.markFailedRead()
      registry.counter("mongo.writeSuccess").getCount shouldBe 1
      registry.counter("mongo.writeFail").getCount shouldBe 1
      registry.counter("mongo.readSuccess").getCount shouldBe 1
      registry.counter("mongo.readFail").getCount shouldBe 1
    }

    "timeCompletionOfFuture should time a future" in new Setup {
      for {
        f <- Future { Thread.sleep(10); 42 }
        timed <- reporter.timeCompletionOfFuture("timer", Future.successful(f))
        count <- Future { registry.timer("timer").getCount }
      } yield {
        timed shouldBe 42
        count shouldBe 1
      }
    }

    "MongoMetricReporter should mark metrics on future completion" in new Setup {
      for {
        f1 <- Future.successful(1).withMetrics(reporter, MetricsReporter.MongoRead)
        f2 <- Future.failed(new Exception).withMetrics(reporter, MetricsReporter.MongoWrite)
        successCount <- Future { registry.counter("mongo.readSuccess").getCount }
        failCount <- Future { registry.counter("mongo.writeFail").getCount }
      } yield {
        f1 shouldBe successCount
        f2 shouldBe failCount
      }
    }
  }
}
