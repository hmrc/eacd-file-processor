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

package helper

import com.codahale.metrics.{Counter, MetricRegistry}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Injecting
import play.api.Application
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.repository.{FileRepository, JobLockRepository, LockingRepository}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.ExecutionContext
import scala.util.Try

trait IntegrationSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with BeforeAndAfterAll
  with Matchers
  with MockitoSugar
  with Injecting
  with BeforeAndAfterEach
  with ScalaFutures
  with IntegrationPatience {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  lazy val lockingTestTimeout: Int = 25

  lazy val fileRepository = app.injector.instanceOf[FileRepository]
  lazy val jobLockRepository: JobLockRepository = app.injector.instanceOf[JobLockRepository]
  lazy val lockingRepo: LockingRepository = app.injector.instanceOf[LockingRepository]
  lazy val metricRegistry = app.injector.instanceOf[MetricRegistry]
  lazy val counter = app.injector.instanceOf[Counter]
  lazy val mongoRepository: MongoComponent = app.injector.instanceOf[MongoComponent]
  lazy val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  def appConfigMap: Map[String, Any] = Map(
    "appName" -> "eacd-file-processor",
    "microservice.services.internal-auth.protocol" -> "http",
    "microservice.services.internal-auth.host" -> "localhost",
    "microservice.services.internal-auth.port" -> 8470,
    "time-to-live.time" -> "3",
    "internal-auth.token" -> "12345678",
    "object-store.default-retention-period" -> "6-months",
    "internalAuth.enabled" -> false,
    "schedules.ProcessApprovedFileJob.enabled" -> false,
    "schedules.FileWorkItemPullJob.enabled" -> false,
    "work-item.retry-in-progress-after.seconds" -> 30,
    "work-item.ttlInHours" -> 720,
    "locking.timeoutMinutes" -> lockingTestTimeout,
    "fileExpiryDays" -> 60
  )

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(appConfigMap)
    .build()
}
