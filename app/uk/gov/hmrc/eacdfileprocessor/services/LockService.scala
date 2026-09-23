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

import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.repository.JobLockRepository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

sealed trait LockResponse
case object MongoLocked extends LockResponse
case object UnlockingFailed extends LockResponse

@Singleton
class LockService @Inject()(lockRepository: JobLockRepository, appConfig: AppConfig) extends Logging {

  private val instanceId: String = appConfig.instanceId

  def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] = {
    val attemptStartedAt = Instant.now()
    logger.info(s"[$job] [instance=$instanceId] Attempting to acquire lock at $attemptStartedAt")

    lockRepository.lockJob(job).flatMap {
      case false =>
        logger.warn(s"[$job] [instance=$instanceId] Lock already held, skipping run at ${Instant.now()}")
        Future.successful(Right(MongoLocked))

      case true =>
        val executionStartedAt = Instant.now()
        val startedNanos = System.nanoTime()
        logger.info(s"[$job] [instance=$instanceId] Lock acquired, job started at $executionStartedAt")

        f.flatMap { result =>
          val completedAt = Instant.now()
          val durationMs = (System.nanoTime() - startedNanos) / 1000000

          lockRepository.releaseLock(job).map {
            case true =>
              logger.info(s"[$job] [instance=$instanceId] Job finished at $completedAt after ${durationMs}ms, lock released")
              Left(result)
            case false =>
              logger.error(s"[$job] [instance=$instanceId] Job finished at $completedAt after ${durationMs}ms, but lock release failed")
              Right(UnlockingFailed)
          }
        }.recoverWith { case e =>
          val failedAt = Instant.now()
          val durationMs = (System.nanoTime() - startedNanos) / 1000000

          logger.error(
            s"[$job] [instance=$instanceId] Job failed at $failedAt after ${durationMs}ms, attempting lock release: ${e.getMessage}",
            e
          )

          lockRepository.releaseLock(job).map { released =>
            if (released) {
              logger.info(s"[$job] [instance=$instanceId] Lock released during failure recovery at ${Instant.now()}")
            } else {
              logger.error(s"[$job] [instance=$instanceId] Failed to release lock during failure recovery at ${Instant.now()}")
            }
            throw e
          }
        }
    }
  }
}
