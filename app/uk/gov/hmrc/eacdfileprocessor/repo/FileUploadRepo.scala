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

package uk.gov.hmrc.eacdfileprocessor.repo

import com.mongodb.client.model.Indexes.descending
import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.*
import org.mongodb.scala.model.Filters.equal
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.upscan.{Details, Reference, UploadedDetails}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats}

import java.net.{URI, URL}
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent
import scala.concurrent.{ExecutionContext, Future}

object FileUploadRepoFormat {

  private given Format[Details] =
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

  private given Format[Reference] =
    Format.at[String](__ \ "value")
      .inmap[Reference](Reference.apply, _.value)

  private given Format[Instant] = MongoJavatimeFormats.instantFormat

  private[repo] val mongoFormat: Format[UploadedDetails] =
    given Format[ObjectId] = MongoFormats.objectIdFormat

    ((__ \ "_id").format[ObjectId]
      ~ (__ \ "reference").format[Reference]
      ~ (__ \ "status").format[String]
      ~ (__ \ "details").format[Details]
      ~ (__ \ "createdAt").format[Instant]
      )(UploadedDetails.apply, Tuple.fromProductTyped _)
}

@Singleton
class FileUploadRepo @Inject()(
                                mongoComponent: MongoComponent,
                                config: AppConfig
                              )(using ExecutionContext)
  extends PlayMongoRepository[UploadedDetails](
    collectionName = "upscanProgressTracker",
    mongoComponent = mongoComponent,
    domainFormat = FileUploadRepoFormat.mongoFormat,
    indexes = Seq(
      IndexModel(Indexes.ascending("reference"), IndexOptions().unique(true)),
      IndexModel(
        descending("createdAt"),
        IndexOptions()
          .unique(false)
          .name("createdAt")
          .expireAfter(config.timeToLive.toLong, TimeUnit.HOURS)
      )
    ),
    replaceIndexes = true
  ):

  //  import FileUploadRepoFormat.given

  override lazy val requiresTtlIndex: Boolean = false

  def insert(details: UploadedDetails): Future[Unit] =
    collection.insertOne(details)
      .toFuture()
      .map(_ => ())

  def findByReference(reference: Reference): Future[Option[UploadedDetails]] = {
    collection.find(
      equal("reference.value", reference.value)
    ).headOption()
  }  