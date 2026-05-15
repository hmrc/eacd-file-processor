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
import uk.gov.hmrc.eacdfileprocessor.repository.LockingRepository

import javax.inject.Inject
import scala.concurrent.{Future, ExecutionContext}

sealed trait LockResponse
case object MongoLocked extends LockResponse
case object UnlockingFailed extends LockResponse

class DefaultLockService @Inject()(val metricsService: MetricsService,
                                   val lockingRepository: LockingRepository) extends LockService

trait LockService extends Logging {

  val metricsService: MetricsService
  val lockingRepository: LockingRepository

  def lockAndRelease[T](job: String)(f: => Future[T])(implicit ec: ExecutionContext): Future[Either[T, LockResponse]] = {
    lockingRepository.lockJob(job) flatMap {
      case false => Future(Right(MongoLocked))
      case true  => metricsService.setScheduleFileInstance[T](f).flatMap { x =>
        lockingRepository.releaseLock(job) map {
          if(_) Left(x) else Right(UnlockingFailed)
        }
      }
    }
  }
}