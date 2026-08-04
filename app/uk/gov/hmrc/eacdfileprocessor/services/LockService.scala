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
import uk.gov.hmrc.eacdfileprocessor.repository.JobLockRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

sealed trait LockResponse
case object MongoLocked extends LockResponse
case object UnlockingFailed extends LockResponse

@Singleton
class LockService @Inject()(lockRepository: JobLockRepository) extends Logging {

  def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
    lockRepository.lockJob(job).flatMap {
      case false =>
        logger.info(s"[$job] Existing lock found, skipping this run")
        Future.successful(Right(MongoLocked))
      case true =>
        f.flatMap { result =>
          lockRepository.releaseLock(job).map {
            case true => Left(result)
            case false => Right(UnlockingFailed)
          }
        }.recoverWith { case e =>
          lockRepository.releaseLock(job).map(_ => throw e)
        }
    }
}

