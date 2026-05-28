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
import org.mongodb.scala.{Document, SingleObservableFuture}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.AssertionHelpers
import uk.gov.hmrc.eacdfileprocessor.models.JobLock

import java.time.Instant
import java.time.temporal.ChronoUnit

class LockingRepositoryISpec extends AssertionHelpers with IntegrationSpec {

  override def beforeEach(): Unit = {
    await(lockingRepo.collection.deleteMany(filter = Document()).toFuture())
  }

  "lockJob" should {
    "lock a job" when {
      "it is not currently locked" in {
        awaitAndAssert(lockingRepo.lockJob("testJob")) {
          _ mustBe true
        }
      }
      "its lock has expired" in {
        val expiredExpiration = Instant.now().minus(1, ChronoUnit.MINUTES)
        await(lockingRepo.collection.insertOne(JobLock("testJob", expiredExpiration)).toFuture())

        awaitAndAssert(lockingRepo.lockJob("testJob")) {
          _ mustBe true
        }
      }
    }

    "not lock a job" when {
      "its lock has not expired" in {
        val futureExpiration = Instant.now().plus(lockingTestTimeout - 1, ChronoUnit.MINUTES)
        await(lockingRepo.collection.insertOne(JobLock("testJob", futureExpiration)).toFuture())

        awaitAndAssert(lockingRepo.lockJob("testJob")) {
          _ mustBe false
        }
      }
    }
  }

  "isJobLocked" should {
    "state a job is not locked" when {
      "it has no entries in the locking repo" in {
        awaitAndAssert(lockingRepo.isJobLocked("testJob")) {
          _ mustBe false
        }
      }

      "its lock has expired" in {
        val expiredExpiration = Instant.now().minus(1, ChronoUnit.MINUTES)
        await(lockingRepo.collection.insertOne(JobLock("testJob", expiredExpiration)).toFuture())

        awaitAndAssert(lockingRepo.isJobLocked("testJob")) {
          _ mustBe false
        }
      }
    }

    "state a job is still locked" when {
      "its lock has not expired" in {
        val futureExpiration = Instant.now().plus(lockingTestTimeout - 1, ChronoUnit.MINUTES)
        await(lockingRepo.collection.insertOne(JobLock("testJob", futureExpiration)).toFuture())

        awaitAndAssert(lockingRepo.isJobLocked("testJob")) {
          _ mustBe true
        }
      }
    }
  }

  "releaseLock" should {
    "release a job lock" when {
      "it was locked" in {
        await(lockingRepo.lockJob("testJob"))

        awaitAndAssert(lockingRepo.releaseLock("testJob")) {
          _ mustBe true
        }
      }
    }

    "do not release a job" when {
      "the lock does not exist" in {
        awaitAndAssert(lockingRepo.isJobLocked("testJob")) {
          _ mustBe false
        }
      }
    }
  }
}
