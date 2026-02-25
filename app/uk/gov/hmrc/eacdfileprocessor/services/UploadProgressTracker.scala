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

import org.bson.types.ObjectId
import play.api.Logging
import play.api.http.Status.CREATED
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.mvc.Results.BadRequest
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.upscan.Details.UploadedSuccessfully
import uk.gov.hmrc.eacdfileprocessor.models.upscan.{Details, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repo.FileUploadRepo
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Path, RetentionPeriod, Sha256Checksum}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UploadProgressTracker @Inject()(repository: FileUploadRepo,
                                      appConfig: AppConfig,
                                      httpClient: HttpClientV2,
                                      osClient: PlayObjectStoreClient)(implicit ec: ExecutionContext) extends Logging {

  def registerUploadResult(fileReference: Reference, details: Details)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      status <- details match {
        case f: Details.UploadedFailed => Future.successful("failed")
        case s: Details.UploadedSuccessfully => Future.successful("scanned")
      }
      _ <- repository.insert(UploadedDetails(ObjectId.get(), fileReference, status, details)).map {
        case true if status == "scanned" =>
          val uploadedDetails = details.asInstanceOf[UploadedSuccessfully]
          transferToObjectStore(downloadUrl = uploadedDetails.downloadUrl,
            mimeType = uploadedDetails.mimeType,
            checksum = uploadedDetails.checksum,
            fileName = uploadedDetails.name,
            fileReference = fileReference)
        case _ =>
          Future.unit
      }
    } yield
      ()

  def getUploadResult(reference: Reference): Future[Option[UploadedDetails]] =
    repository.findByReference(reference)

  private[services] def createClientAuthToken(): Future[Unit] = {
    logger.info("[InternalAuthTokenInitialiser][createClientAuthToken] Initialising auth token")
    httpClient
      .post(url"${appConfig.internalAuthService}/test-only/token")(HeaderCarrier())
      .withBody(
        Json.obj(
          "token" -> appConfig.internalAuthToken,
          "principal" -> appConfig.appName,
          "permissions" -> Seq(
            Json.obj(
              "resourceType" -> "object-store",
              "resourceLocation" -> "eacd-file-processor",
              "actions" -> List("READ", "WRITE", "DELETE")
            ),
            Json.obj(
              "resourceType" -> "eacd-bulk-de-enrol",
              "resourceLocation" -> "*",
              "actions" -> List("*")
            )
          )
        )
      )
      .execute
      .flatMap { response =>
        if (response.status == CREATED) {
          logger.info(
            "[InternalAuthTokenInitialiser][createClientAuthToken] Auth token initialised"
          )
          Future.successful("")
        } else {
          logger.error(
            "[InternalAuthTokenInitialiser][createClientAuthToken] Unable to initialise internal-auth token"
          )
          Future.failed(new RuntimeException("Unable to initialise internal-auth token"))
        }
      }
  }

  private[services] def transferToObjectStore(
                                               downloadUrl: URL,
                                               mimeType: String,
                                               checksum: String,
                                               fileName: String,
                                               fileReference: Reference
                                             )(implicit hc: HeaderCarrier): Future[Unit] = {
    val fileLocation = Path.File(s"${fileReference.value}/$fileName")
    val contentSha256 = Sha256Checksum.fromHex(checksum)
    createClientAuthToken()
    osClient
      .uploadFromUrl(
        from = url"$downloadUrl",
        to = fileLocation,
        retentionPeriod = RetentionPeriod.SixMonths,
        contentType = Some(mimeType),
        contentSha256 = Some(contentSha256)
      )(hc.withExtraHeaders("Authorization" -> appConfig.internalAuthToken))
      .transformWith {
        case scala.util.Failure(exception) =>
          logger.error(s"Failure to upload to object store because of $exception")
          logger.warn("FAILED_OBJECT_STORE_UPDATE Failed upload file to object store")
          exception.printStackTrace()
          Future.successful(BadRequest(s"Failure to upload to object store because of $exception"))
        case scala.util.Success(objectWithMD5) =>
          Future.successful(
            for {
              uploadedDetails <- repository.updateStatus(fileReference, "stored")
              success <- uploadedDetails match {
                case Some(result) =>
                  Future.successful(result)
                case None =>
                  Future.successful(new Exception(s"Could not update file status for reference: ${fileReference.value}"))
              }
            } yield success
          )
      }
  }
}
