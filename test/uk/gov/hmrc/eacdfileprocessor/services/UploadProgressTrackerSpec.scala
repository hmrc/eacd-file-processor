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
import org.mockito.Mockito.when
import play.api.http.Status.CREATED
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.Details
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.*

import java.net.URL
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

class UploadProgressTrackerSpec extends TestSupport with TestData:
  implicit lazy val ec: ExecutionContext = inject[ExecutionContext]
  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.timeToLive).thenReturn("3")
  lazy val repository = app.injector.instanceOf[FileRepository]
  val objectStoreClient = mock[PlayObjectStoreClient]
  lazy val mockHttpClientV2: HttpClientV2 = Mockito.mock(classOf[HttpClientV2])
  val mockRequestBuilder: RequestBuilder = Mockito.mock(classOf[RequestBuilder])

  val progressTracker = UploadProgressTracker(repository, mockAppConfig, mockHttpClientV2, objectStoreClient)
  val reference = initiateUploadDetails.reference
  val sucessfulDetails = Details.UploadedSuccessfully(
    name = "bulk-de-enrol.csv",
    mimeType = "text/csv",
    downloadUrl = URL("http://localhost:9570/upscan/download/c5da3bd6-f118-4cde-afff-93f763bf6448"),
    size = Some(32270),
    checksum = "a0acaa6039c1a94c6f5c43f144c5add07de9381f98701cb14c7c6ce2be18020b"
  )

  when(mockAppConfig.internalAuthService).thenReturn("http://localhost:8470")
  when(mockAppConfig.internalAuthToken).thenReturn("12345678")
  when(mockAppConfig.appName).thenReturn("eacd-file-processor")
  when(mockHttpClientV2.post(any())(any())).thenReturn(mockRequestBuilder)
  when(mockRequestBuilder.withBody(any())(any(), any(), any())).thenReturn(mockRequestBuilder)
  when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
    .thenReturn(Future.successful(HttpResponse(CREATED, body = "")))

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
    await(repository.createFileRecord(initiateUploadDetails))
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
      when(progressTracker.transferToObjectStore(sucessfulDetails.downloadUrl, sucessfulDetails.mimeType, sucessfulDetails.checksum, sucessfulDetails.name, reference)).thenReturn(Future.unit)

      val file = await(repository.findByReference(reference)).get
      file.status mustBe "initial"

      for {
        _ <- progressTracker.registerUploadResult(reference, sucessfulDetails)
        uploadedResult <- repository.findByReference(reference)
      } yield uploadedResult.get.status mustBe "stored"
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
      when(progressTracker.transferToObjectStore(sucessfulDetails.downloadUrl, sucessfulDetails.mimeType, sucessfulDetails.checksum, sucessfulDetails.name, reference)).thenReturn(Future.unit)

      val file = await(repository.findByReference(reference)).get
      file.status mustBe "initial"

      for {
        _ <- progressTracker.registerUploadResult(reference, sucessfulDetails)
        uploadedResult <- repository.findByReference(reference)
      } yield uploadedResult.get.status mustBe "scanned"
    }
  }