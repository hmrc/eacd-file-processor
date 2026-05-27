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

import helper.IntegrationSpec
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.mongo.MongoComponent

import java.time.{Clock, Instant, ZoneId}

class JobLockRepositoryISpec extends IntegrationSpec {

  private final class MutableClock(var now: Instant, zone: ZoneId = ZoneId.of("UTC")) extends Clock {
    override def getZone: ZoneId = zone
    override def withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override def instant(): Instant = now
  }

  private val mutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
  private lazy val mongoComponent: MongoComponent = app.injector.instanceOf[MongoComponent]
  lazy val repository: JobLockRepository = new JobLockRepository(mongoComponent, appConfig, mutableClock)

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "JobLockRepository" should {

    "acquire lock for a new job and reject immediate reacquire" in {
      await(repository.lockJob("job-a")) shouldBe true
      await(repository.lockJob("job-a")) shouldBe false
    }

    "allow reacquire when lock has expired" in {
      await(repository.lockJob("job-b")) shouldBe true
      mutableClock.now = mutableClock.now.plusSeconds((appConfig.lockTimeoutMinutes.toLong * 60) + 60)

      await(repository.lockJob("job-b")) shouldBe true

      val updated = await(repository.collection.find(org.mongodb.scala.model.Filters.equal("job", "job-b")).headOption()).value
      updated.lockCreatedAt.isAfter(Instant.EPOCH) shouldBe true
    }

    "release lock" in {
      await(repository.lockJob("job-c")) shouldBe true
      await(repository.releaseLock("job-c")) shouldBe true
    }
  }
}






