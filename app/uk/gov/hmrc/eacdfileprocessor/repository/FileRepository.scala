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

package uk.gov.hmrc.eacdfileprocessor.repository

import com.mongodb.client.model.Indexes.descending
import org.bson.types.ObjectId
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.*
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.{combine, set}
import play.api.Logging
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.DuplicateReferenceException
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.net.{URI, URL}
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object FileUploadRepoFormat {

  given Format[Details] =
    given Format[URL] = summon[Format[String]].inmap(new URI(_).toURL, _.toString)

    given Format[Details.UploadedSuccessfully] = Json.format[Details.UploadedSuccessfully]

    given Format[Details.UploadedFailed] = Json.format[Details.UploadedFailed]

    val read: Reads[Details] =
      (json: JsValue) =>
        val jsObject = json.asInstanceOf[JsObject]
        jsObject.value.get("name") match
          case Some(value) => Json.fromJson[Details.UploadedSuccessfully](jsObject)
          case None =>
            jsObject.value.get("failureReason") match
              case Some(value) => Json.fromJson[Details.UploadedFailed](jsObject)
              case None => JsError("Missing failureReason or name fields")

    val write: Writes[Details] =
      case f: Details.UploadedFailed => Json.toJson(f).as[JsObject]
      case s: Details.UploadedSuccessfully => Json.toJson(s).as[JsObject]

    Format(read, write)

  given Format[FileStatus] =
    val read: Reads[FileStatus] = {
      case JsString(INITIAL.value)        => JsSuccess(INITIAL)
      case JsString(SCANNED.value)        => JsSuccess(SCANNED)
      case JsString(FAILED.value)         => JsSuccess(FAILED)
      case JsString(STORED.value)         => JsSuccess(STORED)
      case JsString(UPLOADREJECTED.value) => JsSuccess(UPLOADREJECTED)
      case JsString(UPLOADED.value)       => JsSuccess(UPLOADED)
      case JsString(REJECTED.value)       => JsSuccess(REJECTED)
      case JsString(APPROVED.value)       => JsSuccess(APPROVED)
      case _ => JsError("Unknown file status")
    }

    val write: Writes[FileStatus] = (status: FileStatus) => JsString(status.value)

    Format(read, write)

  private given Format[Reference] =
    Format.at[String](__ \ "value")
      .inmap[Reference](Reference.apply, _.value)




  private given Format[ObjectId] = MongoFormats.objectIdFormat

  private[repository] val mongoFormat: Format[UploadedDetails] =
    ((__ \ "_id").format[ObjectId]
      ~ (__ \ "reference").format[Reference]
      ~ (__ \ "status").format[FileStatus]
      ~ (__ \ "requestorPID").format[String]
      ~ (__ \ "requestorEmail").format[String]
      ~ (__ \ "requestorName").format[String]
      ~ (__ \ "details").formatNullable[Details]
      ~ (__ \ "approverDetails").formatNullable[ApproverDetails]
      ~ (__ \ "uploadedDateTime").formatNullable[Instant]
      ~ (__ \ "lastUpdatedDateTime").format[Instant]
      )(UploadedDetails.apply, Tuple.fromProductTyped _)
}

@Singleton
class FileRepository @Inject()(
                                mongoComponent: MongoComponent,
                                metrics: MetricsReporter,
                                config: AppConfig
                              )(using ExecutionContext)
  extends PlayMongoRepository[UploadedDetails](
    collectionName = "file",
    mongoComponent = mongoComponent,
    domainFormat = FileUploadRepoFormat.mongoFormat,
    indexes = Seq(
      IndexModel(
        Indexes.ascending("reference"),
        IndexOptions()
          .name("reference")
          .unique(true)
          .sparse(false)
      ),
      IndexModel(
        descending("createdAt"),
        IndexOptions()
          .unique(false)
          .name("createdAt")
          .expireAfter(config.timeToLive.toLong, TimeUnit.HOURS)
      )
    ),
    replaceIndexes = true
  ) with Logging:

  import FileUploadRepoFormat.given

  override lazy val requiresTtlIndex: Boolean = false

  def createFileRecord(details: UploadedDetails): Future[Boolean] =
    metrics.timeCompletionOfFuture("createFileRecordMongoTimer", {
      collection.insertOne(details)
        .toFuture().transformWith {
          case Success(result) =>
            logger.info(s"Uploaded file has been upsert for reference: ${details.reference.value}")
            Future.successful(result.wasAcknowledged())
          case Failure(exception) =>
            exception match {
              case e: MongoWriteException if e.getCode == 11000 =>
                logger.warn(s"DUPLICATE_EXTERNAL_FILE_REF Duplicate external file reference: ${details.reference.value}")
                Future.failed(new DuplicateReferenceException("Duplicate external file reference"))
              case _ =>
                val errorMsg = s"Uploaded file is not inserted for reference: ${details.reference.value}"
                logger.warn(errorMsg)
                Future.failed(new IllegalStateException(s"$errorMsg: ${exception.getMessage} ${exception.getCause}"))
            }
        }
    })

  def findByReference(reference: Reference): Future[Option[UploadedDetails]] = {
    collection.find(
      equal("reference.value", reference.value)
    ).headOption()
  }

  def findByStatus(status: FileStatus): Future[Option[StatusDetailsModel]] = {
    collection.find(
      equal("status", status.value)
    ).headOption().map(_.map(
      details => 
        StatusDetailsModel(
          reference =   details.reference.value,
          approverEmail = details.requestorEmail,
          approverPID = details.requestorPID,
          status = details.status.value,
          name = details.reference.value,
          uploadedDateTime = details.uploadedDateTime))
    )
  }

  def updateStatusAndDetails(reference: Reference, status: FileStatus, details: Details): Future[Option[UploadedDetails]] =
    updateByReference(reference, Seq(set("status", Codecs.toBson(status)), set("details", Codecs.toBson(details))): _*)

  def updateStatusAndApproverDetails(reference: Reference, status: FileStatus, approverDetails: ApproverDetails, updateUploadedTime: Boolean): Future[Option[UploadedDetails]] = {
    val updates = Seq(
      set("status", Codecs.toBson(status)),
      set("approverDetails", Codecs.toBson(approverDetails))
    ) ++ (if updateUploadedTime then Seq(set("uploadedDateTime", Instant.now())) else Seq.empty)

    updateByReference(reference, updates: _*)
  }

  def updateStatus(reference: Reference, status: FileStatus): Future[Option[UploadedDetails]] =
    updateByReference(reference, set("status", Codecs.toBson(status)))

  private def updateByReference(reference: Reference, updates: Bson*): Future[Option[UploadedDetails]] =
    collection
      .findOneAndUpdate(
        filter = equal("reference.value", reference.value),
        update = combine(updates :+ set("lastUpdatedDateTime", Instant.now()): _*),
        options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()  