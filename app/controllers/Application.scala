package controllers

import java.util.UUID

import play.api._
import scala.concurrent.duration._
import scala.language.postfixOps
import play.api.libs.Comet
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.{Json, JsValue}
import play.api.mvc._
import actors._

import scala.concurrent._

object Application extends Controller {

  def index = Action {
    val uid = UUID.randomUUID().toString
    Ok(views.html.index(uid))
  }

  // don't forget to secure this...
  def api(uuid: String, chatid: String = "0") = WebSocket.async[JsValue] { request =>
    println(s"call to api: $uuid")
    WebSocketHandler.connect(uuid, chatid)
  }

  // NOTE: there are some issues with comet http chunking to be aware of..
  // See my blog post on the matter => http://affinetechnology.blogspot.ca/2014/03/play-framework-comet-chunking-support.html
  def comet(uuid: String, chatid: String = "0") = Action{
    val enumerator = Await.result(WebSocketHandler.connectComet(uuid, chatid), 3.seconds)
    Comet.CometMessage
    // TODO: comet user actors never die .. since there is no disconnect message that is sent
    Ok.chunked((enumerator &> Comet(callback = "parent.cometMessage")) >>> Enumerator.eof )
  }

  def cometSend(data:String, uuid: String, chatid: String = "0") = Action.async{ request  =>
    WebSocketHandler.cometSend(uuid,  Json.parse(data))
    Future.successful(Ok(Json.obj("status" -> "ack")))
  }

}