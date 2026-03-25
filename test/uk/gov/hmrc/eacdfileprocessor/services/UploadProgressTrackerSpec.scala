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
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify, when}
import play.api.http.Status.CREATED
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.*

import java.net.URL
import java.time.Instant
import scala.concurrent.{Future, TimeoutException}

class UploadProgressTrackerSpec extends TestSupport with TestData:
  private lazy val mockAppConfig = mock[AppConfig]
  private lazy val reference = initiateUploadDetails.reference
  
  trait Setup {
    val repository = mock[FileRepository]
    val objectStoreClient = mock[PlayObjectStoreClient]
    val mockHttpClientV2: HttpClientV2 = Mockito.mock(classOf[HttpClientV2])
    val mockRequestBuilder: RequestBuilder = Mockito.mock(classOf[RequestBuilder])

    val progressTracker = UploadProgressTracker(repository, mockAppConfig, mockHttpClientV2, objectStoreClient)

    when(mockHttpClientV2.post(any())(any())).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.withBody(any())(any(), any(), any())).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
      .thenReturn(Future.successful(HttpResponse(CREATED, body = "")))
  }
  
  when(mockAppConfig.internalAuthService).thenReturn("http://localhost:8470")
  when(mockAppConfig.internalAuthToken).thenReturn("12345678")
  when(mockAppConfig.appName).thenReturn("eacd-file-processor")

  "UploadProgressTracker" should {
    "update successful upload file details and correctly update status" in new Setup {
      when(repository.updateStatusAndDetails(any(), any(), any())).thenReturn(Future.successful(Some(initiateUploadDetails)))
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
      when(progressTracker.transferToObjectStore(successfulUploadedDetails.downloadUrl,successfulUploadedDetails.mimeType,
        successfulUploadedDetails.checksum, successfulUploadedDetails.name, reference)).thenReturn(Future.unit)

      progressTracker.registerUploadResult(reference, successfulUploadedDetails)
      verify(repository, times(1)).updateStatus(any(), any())
    }

    "update failed upload file details" in new Setup {
      when(repository.updateStatusAndDetails(any(), any(), any())).thenReturn(Future.successful(Some(initiateUploadDetails)))
      
      progressTracker.registerUploadResult(reference, failedFileDetails)
      verify(repository, times(0)).updateStatus(any(), any())
    }

    "Failed to upload file to object store and status remained scanned" in new Setup {
      when(repository.updateStatusAndDetails(any(), any(), any())).thenReturn(Future.successful(Some(initiateUploadDetails)))
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

      progressTracker.registerUploadResult(reference, successfulUploadedDetails)

      verify(repository, times(0)).updateStatus(any(), any())
    }
  }