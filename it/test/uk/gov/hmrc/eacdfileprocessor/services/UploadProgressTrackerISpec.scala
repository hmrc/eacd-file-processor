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

import helper.IntegrationSpec
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.{spy, when}
import org.scalatest.concurrent.Eventually
import play.api.http.Status.CREATED
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{INITIAL, SCANNED, STORED}
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.objectstore.client.*
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.net.URL
import java.time.Instant
import java.time.Instant.now
import scala.concurrent.{Future, TimeoutException}

class UploadProgressTrackerISpec extends IntegrationSpec with TestData with Eventually:
  val objectStoreClient = mock[PlayObjectStoreClient]
  lazy val mockHttpClientV2: HttpClientV2 = Mockito.mock(classOf[HttpClientV2])
  val mockRequestBuilder: RequestBuilder = Mockito.mock(classOf[RequestBuilder])

  val progressTracker = UploadProgressTracker(fileRepository, appConfig, objectStoreClient, auditService, emailService)
  val reference = initiateUploadDetails.reference
  when(mockHttpClientV2.post(any())(any())).thenReturn(mockRequestBuilder)
  when(mockRequestBuilder.withBody(any())(any(), any(), any())).thenReturn(mockRequestBuilder)
  when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
    .thenReturn(Future.successful(HttpResponse(CREATED, body = "")))

  override def beforeEach(): Unit = {
    await(fileRepository.collection.drop().headOption())
    await(fileRepository.ensureIndexes())
    await(fileRepository.createFileRecord(initiateUploadDetails.copy(uploadedDateTime = Some(now()))))
  }

  "UploadProgressTracker" should {
    "insert upload file details and correctly update status to stored" in {
      when(
        objectStoreClient.uploadFromUrl(
          from = any[URL],
          to = any[Path.File],
          retentionPeriod = any[RetentionPeriod],
          contentType = any[Option[String]],
          contentMd5 = any[Option[Md5Hash]],
          contentSha256 = any[Option[Sha256Checksum]],
          owner = any[String]
        )(using any[HeaderCarrier])
      ).thenReturn(
        Future.successful(
          ObjectSummaryWithMd5(
            location = Path.File("/some/file.txt"),
            contentLength = 100,
            contentMd5 = Md5Hash("md5hash"),
            lastModified = Instant.now()
          )
        )
      )

      val file = await(fileRepository.findByReference(reference)).get
      file.status mustBe INITIAL

      await(progressTracker.registerUploadResult(reference, successfulUploadedDetails))
      eventually {
        val uploadedResult = await(fileRepository.findByReference(reference))
        uploadedResult.get.status mustBe STORED
      }
    }

    "Failed to upload file to object store and status remained scanned" in {
      when(
        objectStoreClient.uploadFromUrl(
          from = any[URL],
          to = any[Path.File],
          retentionPeriod = any[RetentionPeriod],
          contentType = any[Option[String]],
          contentMd5 = any[Option[Md5Hash]],
          contentSha256 = any[Option[Sha256Checksum]],
          owner = any[String]
        )(using any[HeaderCarrier])
      ).thenReturn(Future.failed(new TimeoutException("Unable to upload, time out.")))
      when(progressTracker.transferToObjectStore(successfulUploadedDetails.downloadUrl, successfulUploadedDetails.mimeType,
        successfulUploadedDetails.checksum, successfulUploadedDetails.name, reference)).thenReturn(Future.unit)

      val file = await(fileRepository.findByReference(reference)).get
      file.status mustBe INITIAL

      val uploadedResultF = for {
        _ <- progressTracker.registerUploadResult(reference, successfulUploadedDetails)
        uploadedResult <- fileRepository.findByReference(reference)
      } yield uploadedResult
      await(uploadedResultF).get.status mustBe SCANNED
    }
  }