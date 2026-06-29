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
import uk.gov.hmrc.eacdfileprocessor.models.{Details, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ExpiredFileDeletionService @Inject()(
                                          appConfig: AppConfig,
                                          fileRepository: FileRepository,
                                          osClient: PlayObjectStoreClient,
                                          lockService: LockService
                                        ) extends ScheduledService[Either[Unit, LockResponse]] with Logging {

  private given HeaderCarrier = HeaderCarrier()

  override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] =
    lockService.lockAndRelease("ExpiredFileDeletionJob") {
      deleteExpiredFiles()
    }

  private def deleteExpiredFiles()(using ExecutionContext): Future[Unit] =
    for {
      expiredInitial <- fileRepository.findExpiredInitialFiles
      _ <- Future.traverse(expiredInitial)(f => fileRepository.deleteByReference(f.reference))
      expiredActive <- fileRepository.findExpiredActiveFiles
      _ <- Future.traverse(expiredActive)(deleteActiveFileRecord)
    } yield ()

  private def deleteActiveFileRecord(file: UploadedDetails)(using ExecutionContext): Future[Unit] =
    file.details match {
      case Some(Details.UploadedSuccessfully(fileName, _, _, _, _)) =>
        val path = Path.Directory(file.reference.value).file(fileName)
        osClient.deleteObject(path, owner = appConfig.appName)
          .flatMap(_ => fileRepository.deleteByReference(file.reference))
          .map(_ => ())
          .recover { case e =>
            logger.warn(
              s"Failed deleting object-store file and/or DB record for reference ${file.reference.value}: ${e.getMessage}", e
            )
          }

      case _ =>
        // If no usable file name is present, skip object-store delete and remove DB record only.
        fileRepository.deleteByReference(file.reference)
          .map(_ => ())
    }
}