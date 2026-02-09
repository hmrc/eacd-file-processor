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

import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.IndexModel
import uk.gov.hmrc.eacdfileprocessor.models.HelpdeskInitiateRequestModel
import uk.gov.hmrc.eacdfileprocessor.repository.MongoResponses.*
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs.logger
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileRepository @Inject()(mongo: MongoComponent, metrics: MetricsReporter, implicit val ec: ExecutionContext)
  extends PlayMongoRepository[HelpdeskInitiateRequestModel](mongo, "file",
    HelpdeskInitiateRequestModel.formats, Seq(referenceIndex)) {
      
      def createFileRecord(fileData: HelpdeskInitiateRequestModel): Future[MongoCreateResponse] = {
        val DUPLICATE_KEY_ERROR = 11000
        metrics.timeCompletionOfFuture("createFileRecordMongoTimer", {
          collection.insertOne(fileData).toFuture().map { wr =>
            if (wr.wasAcknowledged()) {
              MongoSuccessCreate
            } else {
              logger.error(s"[createFileRecord] - Failed to insert data for file reference: ${fileData.reference}")
              MongoFailedCreate
            }
          }.recoverWith {
            case e: MongoWriteException if e.getCode == DUPLICATE_KEY_ERROR =>
              Future.successful(MongoSuccessCreate)
          }
        })
      }
    }
