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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.repository.LockingRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LockServiceSpec extends PlaySpec {

  val mockJobLockRepo = mock[LockingRepository]
  val testService: LockService = new LockService(mockJobLockRepo)

  def testFuture: Future[String] = Future("testString")

  "lockAndRelease" should {
    "return a MongoLocked" in {
      when(mockJobLockRepo.lockJob(any()))
        .thenReturn(Future.successful(false))

      val result = await(testService.lockAndRelease("testJob") {
        testFuture
      })

      result mustBe Right(MongoLocked)
    }

    "return a UnlockingFailed" in {
      when(mockJobLockRepo.lockJob(any()))
        .thenReturn(Future.successful(true))

      when(mockJobLockRepo.releaseLock(any()))
        .thenReturn(Future.successful(false))

      val result = await(testService.lockAndRelease("testJob") {
        testFuture
      })

      result mustBe Right(UnlockingFailed)
    }

    "return a String" in {
      when(mockJobLockRepo.lockJob(any()))
        .thenReturn(Future.successful(true))

      when(mockJobLockRepo.releaseLock(any()))
        .thenReturn(Future.successful(true))

      val result = await(testService.lockAndRelease("testJob") {
        testFuture
      })

      result mustBe Left("testString")
    }
  }
}
