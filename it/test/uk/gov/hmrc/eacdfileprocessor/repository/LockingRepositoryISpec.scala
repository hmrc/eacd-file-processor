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
import org.mongodb.scala.model.Updates
import org.mongodb.scala.{Document, ObservableFuture, SingleObservableFuture}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.AssertionHelpers
import uk.gov.hmrc.eacdfileprocessor.selectors.JobLockSelectors
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

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
      s"it was locked over $lockingTestTimeout minutes ago" in {
        await(lockingRepo.lockJob("testJob"))
        await(
          lockingRepo.collection.updateOne(
            JobLockSelectors.jobLockedOf("testJob"),
            Updates.set(
              "lockCreatedAt",
              Codecs.toBson(Instant.now().minus(lockingTestTimeout, ChronoUnit.MINUTES))(using MongoJavatimeFormats.instantFormat)
            )
          ).toFuture()
        )

        awaitAndAssert(lockingRepo.lockJob("testJob")) {
          _ mustBe true
        }
      }
    }

    "not lock a job" when {
      s"it was locked under $lockingTestTimeout minutes ago" in {
        await(lockingRepo.lockJob("testJob"))
        await(
          lockingRepo.collection.updateOne(
            JobLockSelectors.jobLockedOf("testJob"),
            Updates.set(
              "lockCreatedAt",
              Codecs.toBson(Instant.now().minus(lockingTestTimeout - 1, ChronoUnit.MINUTES))(using MongoJavatimeFormats.instantFormat)
            )
          ).toFuture()
        )

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

      s"it was locked over $lockingTestTimeout minutes ago" in {
        await(lockingRepo.lockJob("testJob"))
        await(
          lockingRepo.collection.updateOne(
            JobLockSelectors.jobLockedOf("testJob"),
            Updates.set(
              "lockCreatedAt",
              Codecs.toBson(Instant.now().minus(lockingTestTimeout, ChronoUnit.MINUTES))(using MongoJavatimeFormats.instantFormat)
            )
          ).toFuture()
        )

        awaitAndAssert(lockingRepo.isJobLocked("testJob")) {
          _ mustBe false
        }
      }
    }

    "state a job is still locked" when {
      s"it was locked under $lockingTestTimeout minutes ago" in {
        await(lockingRepo.lockJob("testJob"))
        await(
          lockingRepo.collection.updateOne(
            JobLockSelectors.jobLockedOf("testJob"),
            Updates.set(
              "lockCreatedAt",
              Codecs.toBson(Instant.now().minus(lockingTestTimeout - 1, ChronoUnit.MINUTES))(using MongoJavatimeFormats.instantFormat)
            )
          ).toFuture()
        )
        lockingRepo.collection.find(JobLockSelectors.jobLockedOf("testJob")).toFuture()
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
