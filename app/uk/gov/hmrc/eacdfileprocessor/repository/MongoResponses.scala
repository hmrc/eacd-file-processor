/*
 * Copyright 2023 HM Revenue & Customs
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

object MongoResponses {
  sealed trait MongoCreateResponse
  case object MongoSuccessCreate extends MongoCreateResponse
  case object MongoFailedCreate extends MongoCreateResponse

  sealed trait MongoReadResponse
  case object MongoFailedReadMissingDoc extends MongoReadResponse
  case object MongoFailedRead extends MongoReadResponse

  sealed trait MongoUpdateResponse
  case object MongoSuccessUpdate extends MongoUpdateResponse
  case object MongoFailedUpdateMissingDoc extends MongoUpdateResponse
  case class MongoFailedUpdate(reason: String) extends MongoUpdateResponse

  sealed trait MongoDeleteResponse
  case object MongoSuccessDelete extends MongoDeleteResponse
  case object MongoFailedDelete extends MongoDeleteResponse
}
