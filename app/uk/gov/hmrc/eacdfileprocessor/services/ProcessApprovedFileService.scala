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

import org.apache.pekko.stream.Materializer
import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.ObjectStoreFileNotFoundException
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, Details, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits.*
import uk.gov.hmrc.objectstore.client.play.Implicits.InMemoryReads.stringContentRead
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DefaultProcessApprovedFileService @Inject()(val appConfig: AppConfig,
                                                  val osClient: PlayObjectStoreClient,
                                                  val fileRepository: FileRepository,
                                                  val workItemRepository: DeEnrolmentWorkItemRepository,
                                                  val lockService: LockService,
                                                  implicit val ec: ExecutionContext,
                                                  implicit val mat: Materializer
                                 ) extends ProcessApprovedFileService

trait ProcessApprovedFileService extends Logging with ScheduledService[Either[Unit, LockResponse]] {
  implicit val ec: ExecutionContext
  implicit val mat: Materializer
  implicit val hc: HeaderCarrier = HeaderCarrier()
  val appConfig: AppConfig
  val osClient: PlayObjectStoreClient
  val fileRepository: FileRepository
  val workItemRepository: DeEnrolmentWorkItemRepository
  val lockService: LockService

  override def invoke(implicit ec: ExecutionContext): Future[Either[Unit, LockResponse]] =
    logger.info("ProcessApprovedFileService.invoke - Starting scheduled job")
    lockService.lockAndRelease(this.getClass.getSimpleName) {
      createWorkItemsFromOldestFile
    }

  private def getFileStringFromObjectStore(reference: Reference, fileName: String): Future[Option[String]] = {
    logger.debug(s"Retrieving file from Object Store - Reference: ${reference.value}, FileName: $fileName")
    osClient.getObject[String](
      path = Path.Directory(reference.value).file(fileName),
      owner = appConfig.appName
    ).map(_.map(obj => obj.content))
  }

  private[services] def createWorkItemsFromOldestFile: Future[Unit] = {
    fileRepository.findOldestApprovedFile.flatMap {
      case Some(uploadedDetail) =>
        val reference = uploadedDetail.reference
        logger.info(s"Found oldest approved file with reference: ${reference.value}")
        getFileStringFromObjectStore(reference, getFileName(uploadedDetail)).flatMap {
          case Some(contentStr) =>
            logger.debug(s"Successfully retrieved file content from Object Store for reference: ${reference.value}")
            pushWorkItems(generateWorkItems(contentStr, reference.value), reference)
          case None =>
            logger.warn(s"No record found for the requested reference: ${reference.value} in Object Store")
            throw ObjectStoreFileNotFoundException(s"No record found for the requested reference: ${reference.value} in Object Store")
        }
      case None =>
        logger.info("There is no approved files to be picked up currently.")
        Future.unit
    }
  }

  private def getFileName(uploadedDetail: UploadedDetails): String = {
    val reference = uploadedDetail.reference.value
    logger.debug(s"Extracting file name for reference: $reference")
    uploadedDetail.details.map {
      case s: Details.UploadedSuccessfully =>
        logger.debug(s"File name found: ${s.name}")
        s.name
      case f: Details.UploadedFailed =>
        logger.error(s"Can't find file name for reference: $reference - Status is UploadedFailed")
        throw new RuntimeException(s"Can't find file name for reference: $reference")
    }.getOrElse{
      logger.error(s"Can't find file name for reference: $reference - Details is empty")
      throw new RuntimeException(s"Can't find file name for reference: $reference")
    }
  }

  private val Utf8Bom: String = "\uFEFF"

  private def sanitizeRecordLine(line: String): String =
    line.stripPrefix(Utf8Bom).trim

  private def generateWorkItems(contentStr: String, reference: String) = {
    val createdAt = Instant.now()
    val workItems = ArraySeq.unsafeWrapArray(contentStr.split("\n"))
      .map(sanitizeRecordLine)
      .filter(_.nonEmpty)
      .map(DeEnrolmentWorkItem(reference, _, createdAt))
    logger.info(s"Generated ${workItems.size} work items from file for reference: $reference")
    workItems
  }

  private def pushWorkItems(workItems: Seq[DeEnrolmentWorkItem], reference: Reference): Future[Unit] =
    if (workItems.nonEmpty)
      logger.info(s"Pushing ${workItems.size} work items to repository for reference: ${reference.value}")
      workItemRepository.saveRecordDetails(workItems, reference.value)
        .flatMap(savedWorkItems => {
          logger.info(s"Successfully saved ${savedWorkItems.size} work items for reference: ${reference.value}")
          fileRepository.setTotalEntryCount(reference, savedWorkItems.size).map(_ => ())})
        .recover {
          case ex: RuntimeException =>
            logger.error(s"CANNOT_LOAD_WORKITEMS for file reference $reference - Exception: ${ex.getMessage}", ex)
        }
    else {
      logger.warn(s"No work items to push for reference: ${reference.value}")
      Future.unit
    }
}
