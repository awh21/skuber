package skuber.json

/*
 * Support for using Play Json formatters with Akka HTTP client
 * This class is basically a copy of:
 * https://github.com/hseeberger/akka-http-json/blob/master/akka-http-play-json/src/main/scala/de/heikoseeberger/akkahttpplayjson/PlayJsonSupport.scala
 * ... but with some logging added to support skuber supportability requirements
*/

/*
 * Copyright 2015 Heiko Seeberger
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

import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.ContentTypeRange
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.server.{ RejectionError, ValidationRejection }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.util.ByteString
import play.api.libs.json.{ JsError, JsValue, Json, Reads, Writes }
import scala.collection.immutable.Seq

/**
  * Automatic to and from JSON marshalling/unmarshalling using an in-scope *play-json* protocol.
  */
object PlayJsonSupportForAkkaHttp extends PlayJsonSupportForAkkaHttp {

  final case class PlayJsonError(error: JsError) extends RuntimeException {
    override def getMessage: String =
      JsError.toJson(error).toString()
  }

}

/**
  * Automatic to and from JSON marshalling/unmarshalling using an in-scope *play-json* protocol.
  */
trait PlayJsonSupportForAkkaHttp {
  import PlayJsonSupportForAkkaHttp._

  def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`)

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
        .forContentTypes(unmarshallerContentTypes: _*)
        .mapWithCharset {
          case (ByteString.empty, _) => throw Unmarshaller.NoContentException
          case (data, charset)       => data.decodeString(charset.nioCharset.name)
        }

  private val jsonStringMarshaller = Marshaller.stringMarshaller(`application/json`)

  /**
    * HTTP entity => `A`
    *
    * @tparam A type to decode
    * @return unmarshaller for `A`
    */
  implicit def unmarshaller[A: Reads]: FromEntityUnmarshaller[A] = {
    def read(json: JsValue) =
      implicitly[Reads[A]]
          .reads(json)
          .recoverTotal { e =>
            println(s"e => $e")
            throw RejectionError(ValidationRejection(JsError.toJson(e).toString, Some(PlayJsonError(e))))
          }
    jsonStringUnmarshaller.map { data =>
      read(Json.parse(data))
    }
  }

  /**
    * `A` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit def marshaller[A](implicit writes: Writes[A],
    printer: JsValue => String = Json.prettyPrint): ToEntityMarshaller[A] =
    jsonStringMarshaller.compose(printer).compose(writes.writes)
}