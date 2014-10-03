package controllers

import java.util.UUID

import play.api._
import play.api.libs.ws._
import scala.concurrent.duration._
import scala.language.postfixOps
import play.api.libs.Comet
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
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
  def api(uuid: String, token:String) = WebSocket.async[JsValue] { request =>
    println(s"call to api: $uuid")
    def returnAuthExcetion = {
      println("Auth Exception")
      // Just consume and ignore the input
      val in = Iteratee.foreach[JsValue] { event =>}
      // Send a single 'Hello!' message and close
      val out = Enumerator(Json.obj("op" -> "exception", "slot" -> "exception", "msg" -> "Not Authorized").asInstanceOf[JsValue]) >>> Enumerator.eof
      Future {
        (in, out)
      }
    }

    Play.application.configuration.getBoolean("application.auth.enabled") match {
      case Some(authEnable) =>
        if(authEnable) {
          val authUrl = Play.application.configuration.getString("application.auth.url").get.replace("[USERNAME]",uuid).replace("[TOKEN]", token)
          println(s"using AUTH endpoing: $authUrl")
          WS.url(authUrl).get.flatMap{ resp =>
	      println(resp.json)
              //{ 'status': true, 'usertype':PAT/CON/PHY }
              val auth = (resp.json \ "status").as[Boolean]
              val userType = (resp.json \ "usertype").as[String]
              println(s"auth: $auth, userType: $userType" )
              if( auth )
                WebSocketHandler.connect(uuid)
              else
                returnAuthExcetion
          }.fallbackTo( returnAuthExcetion )
        }else {
          WebSocketHandler.connect(uuid)
        }
      case None =>
        WebSocketHandler.connect(uuid)
    }
  }

  // NOTE: there are some issues with comet http chunking to be aware of..
  // See my blog post on the matter => http://affinetechnology.blogspot.ca/2014/03/play-framework-comet-chunking-support.html
  def comet(uuid: String, token:String = "") = Action{
    val enumerator = Await.result(WebSocketHandler.connectComet(uuid), 3.seconds)
    Comet.CometMessage
    // TODO: comet user actors never die .. since there is no disconnect message that is sent
    Ok.chunked((enumerator &> Comet(callback = "parent.cometMessage")) >>> Enumerator.eof )
  }

  def cometSend(uuid: String) = Action.async{ request  =>
    val data = request.body.asFormUrlEncoded.get.apply("data").head
    WebSocketHandler.cometSend(uuid,  Json.parse(data))
    Future.successful(Ok(Json.obj("status" -> "ack")))
  }

}
