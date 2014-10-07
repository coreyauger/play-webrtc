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
    Ok(views.html.index("","",""))
  }


  def test(username: String, role:String) = Action {
    val uid = UUID.randomUUID().toString
    Ok(views.html.index(username, "", role))
  }


  def api(username: String, token:String) = WebSocket.async[JsValue] { request =>
    println(s"call to api: $username")
    def returnAuthExcetion = {
      println("Auth Exception")
      // Just consume and ignore the input
      val in = Iteratee.foreach[JsValue] { event =>}
      // Send a single 'Hello!' message and close
      val out = Enumerator(Json.obj("op" -> "connection", "slot" -> "socket", "data" ->  Json.obj("code" -> 1, "error" -> "Not Authorized")).asInstanceOf[JsValue]) >>> Enumerator.eof
      Future {
        (in, out)
      }
    }

    Play.application.configuration.getBoolean("application.auth.enabled") match {
      case Some(authEnable) =>
        if(authEnable) {
          val authUrl = Play.application.configuration.getString("application.auth.url").get.replace("[USERNAME]",username).replace("[TOKEN]", token)
          println(s"using AUTH endpoing: $authUrl")
          WS.url(authUrl).get.flatMap{ resp =>
	      println(resp.json)
              //{ 'status': true, 'usertype':PAT/CON/PHY }
              val auth = (resp.json \ "status").as[Boolean]
              val userType = (resp.json \ "usertype").as[String]
              println(s"auth: $auth, userType: $userType" )
              if( auth )
                WebSocketHandler.connect(User.create(username,token,userType))
              else
                returnAuthExcetion
          }.fallbackTo( returnAuthExcetion )
        }else {
          WebSocketHandler.connect(User.create("",""))
        }
      case None =>
        WebSocketHandler.connect(User.create("",""))
    }
  }




  def apitest(username: String, token:String, role:String) = WebSocket.async[JsValue] { request =>
    println(s"call to api: $username")
    def returnAuthExcetion = {
      println("Auth in on .. you are not in TEST mode.")
      // Just consume and ignore the input
      val in = Iteratee.foreach[JsValue] { event =>}
      // Send a single 'Hello!' message and close
      val out = Enumerator(Json.obj("op" -> "connection", "slot" -> "socket", "data" ->  Json.obj("code" -> 2, "error" -> "Testing is not allowed while auth is enabled.  Disable authentication in the config")).asInstanceOf[JsValue]) >>> Enumerator.eof
      Future {
        (in, out)
      }
    }

    Play.application.configuration.getBoolean("application.auth.enabled") match {
      case Some(authEnable) =>
        if(authEnable) {
          returnAuthExcetion
        }else {
          WebSocketHandler.connect(User.create(username,token,role))
        }
      case None =>
        returnAuthExcetion
    }
  }


}
