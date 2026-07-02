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

import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object FileRecordValidationErrorFormats {

  private given Format[ObjectId] = MongoFormats.objectIdFormat

  private given Format[Instant] = MongoJavatimeFormats.instantFormat

  val fileRecordValidationErrorFormat: Format[FileRecordValidationError] =
    ((__ \ "_id").format[ObjectId]
      ~ (__ \ "reference").format[Reference]
      ~ (__ \ "fileName").format[String]
      ~ (__ \ "recordDetail").format[String]
      ~ (__ \ "errorMessage").format[String]
      ~ (__ \ "creationDateTime").format[Instant]
      )(FileRecordValidationError.apply, Tuple.fromProductTyped)
}

@Singleton
class FileRecordValidationErrorRepository @Inject()(mongoComponent: MongoComponent)(using ExecutionContext)
  extends PlayMongoRepository[FileRecordValidationError](
    collectionName = "file-record-validation-error",
    mongoComponent = mongoComponent,
    domainFormat = FileRecordValidationErrorFormats.fileRecordValidationErrorFormat,
    indexes = Seq.empty,
    replaceIndexes = true
  ) {

  def create(error: FileRecordValidationError): Future[Unit] =
    collection.insertOne(error).toFuture().map(_ => ())

  def countByReference(reference: Reference): Future[Int] =
    collection
      .countDocuments(
        Filters.or(
          Filters.equal("reference", reference.value),
          Filters.equal("reference.value", reference.value)
        )
      )
      .toFuture()
      .map(_.toInt)
}
