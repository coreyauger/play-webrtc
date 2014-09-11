package actors

import java.text.SimpleDateFormat
import java.util.TimeZone

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import play.api.libs.ws.WS
import scala.Predef._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global

sealed abstract class SocketApi
case class NowConnected(enumerator:Enumerator[JsValue]) extends SocketApi
case class CannotConnect(msg: String) extends SocketApi
case class Connect(userid: String) extends SocketApi
case class Quit(userid: String) extends SocketApi
case class UserSocketConnect(socket: Concurrent.Channel[JsValue], isComet: Boolean) extends SocketApi
case class UserSocketDisconnect(socket: Concurrent.Channel[JsValue]) extends SocketApi

sealed abstract class JsonApi
case class JsonResponse(subjectOp: String, json: JsValue) extends JsonApi
case class FutureJsonResponse(f: Future[JsValue]) extends JsonApi
case class JsonRequest(subjectOp: String, json: JsValue, sync:Option[(Concurrent.Channel[JsValue],Boolean)] = None) extends JsonApi



/**
 * Created by corey auger on 08/09/14.
 */

class Room(val name:String){
  var members = Set[String]()
  def toJson = {
    Json.obj(
      "name" -> name,
      "members" -> members
    )
  }
}


class UserActor(val uuid: String, val chatid: String ) extends Actor with ActorLogging{

  implicit val timeout = Timeout(3 second)
  log.info(s"UserActor create: $uuid")
  var socketmap = Map[String,(Concurrent.Channel[JsValue], Boolean)]()


  val dateFormatUtc: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  dateFormatUtc.setTimeZone(TimeZone.getTimeZone("UTC"))


  lazy val ops = Map[String, ((JsValue) => Future[JsValue])](
    "room-join" -> ((json: JsValue) =>{
      val name = (json \ "data" \ "name").as[String]
      // NOTE: this is not syncronized ... you would want something similar to LockActor for this.
      UserActor.rooms.get(name) match {
        case Some(room) =>
          room.members += uuid
          Future.successful(room.toJson)
        case None =>
          val r = new Room(name)
          r.members += uuid
          UserActor.rooms += (name -> r)
          Future.successful(r.toJson)
      }
    }),
    "room-list" -> ((json: JsValue) => {
        Future.successful(Json.obj(
          "rooms" -> UserActor.rooms.values.map(_.toJson)
        ))
    }),
    "webrtc-relay" ->((json: JsValue) =>{
      log.info(s"webrtc: $json")
      val data = (json \ "data" ).as[JsObject]
      val room = (json \ "data" \ "room" ).as[String]
      // this is from another user.. to send back down socket channels..
      Future.successful(Json.arr(Json.obj( "room" -> room, "uuid" -> uuid, "data" -> data )) )
    }),
    "webrtc-join" ->((json: JsValue) =>{
      log.info(s"webrtc: $json")
      val room = (json \ "data" \ "room" ).as[String]
      //val offer = (json \ "data" \ "sendOffer" ).as[Boolean]
      log.debug(s"WEBRTC::JOIN chatid $room")
      // The Joiner is ALWAYS the initiator for the new connections to the peers...
      // TODO: We are sending to more accounts then we need to by getting a list of ALL providers in the chat
        val members = UserActor.rooms(room).members
        println(s"You: $uuid")
        val others = members.filterNot(_ == uuid)
        log.debug("RELAY addPeer Other %s".format(others.mkString(",") ))
        members.foreach( uid => UserActor.usermap.get(uid) match{
          case Some(ua) =>
            UserActor.route(uuid, ua,JsonRequest("webrtc-relay",Json.obj(
              "room" -> room,
              "slot" -> "webrtc",
              "op" -> "relay",
              "data" -> Json.obj(
                "room" -> room,
                "actors" -> others,
                "type" -> "addPeer",
                "uuid" -> uuid,
                "sendOffer" -> true
              )
            )))
          case None =>
            // remove the user
            UserActor.usermap -= uid
        })
      Future.successful( Json.obj() )
    })
  )

  def receive = {
    case jr:JsonRequest =>
      // TODO: this whole case bs is pretty ugly.. lets find a better way to do this.
      val slot = (jr.json \ "slot").asOpt[String]
      val op = (jr.json \ "op").asOpt[String]
      val syncOpt = (jr.json \ "sync").asOpt[Int]
      // this is where we sort out what sockets the user wanted to sync data on.  Values:
      // 0 or not present: Sync down all sockets channels that belong to this user
      // 1: sync ONLY with the socket the message was sent from
      // TODO: send to other actors..
      log.info(s"About to perform op: ${jr.subjectOp}")
      val fJson:Future[JsValue] = ops.get(jr.subjectOp).get.apply(jr.json)
      fJson.onSuccess{
        case json =>
          println(s"returning: $json")
          socketmap.values.foreach {
            case (soc, isComet) =>
              soc.push(Json.obj(
                "slot" -> slot,
                "op" -> op,
                "data" ->json
              ))
              // (CA) - this is super lame...
              // we need to pad come request.. and turn off gzip to all for each request to be pushed more info on my blog.
              if (isComet)
                soc.push(Json.obj("padding" -> Array.fill[Char](25 * 1024)(' ').mkString))
          }
      }


    case c: UserSocketConnect =>
      log.info(s"UserActor::UserSocketConnect")
      socketmap += context.sender.path.name -> (c.socket, c.isComet)
      sender ! c

    case d: UserSocketDisconnect =>
      log.info(s"UserActor::UserSocketDisconnect")
      socketmap -= context.sender.path.name
      sender ! d
      val numsockets = socketmap.size
      log.info(s"Num sockets left $numsockets for $uuid")
      if( socketmap.isEmpty  ){
        UserActor.usermap -= uuid
        // looping through ALL rooms to remove this member is bad !
        UserActor.rooms.values.foreach( r => r.members -= uuid )
        log.info(s"No more sockets USER SHUTDOWN.. good bye !")
        context.stop(self)
      }
  }
}



object UserActor{
  // TODO: for now this is a hack memory storage...
  var usermap = Map[String,ActorRef]()
  val lockActor = Akka.system.actorOf(Props(new LockActor()))
  var rooms = Map[String, Room]()


  def findUserActor(uuid: String):Option[ActorRef] ={
    println(s"Actor $uuid")
    println( usermap.keys )
    usermap.get(uuid)
  }

  def route(uuid:String, useractor:ActorRef, jr:JsonRequest) ={
    val actors = (jr.json \ "data" \ "actors").asOpt[JsArray]  // if this is present then we want to send a message to another users actor..
    println( "JsonRequest: " + jr.json )
    println( "subjectOp: " + jr.subjectOp )
    if( actors != None ){
      // TODO: lookup actor on cluster ...
      val actorList = actors.get.as[List[String]]
      println("CALLING ACTORS *****************************")
      println(s"WebSocketActor::actors $actorList")
      actorList.foreach( a => {
        val chatid = (jr.json \ "data" \ "chatid").asOpt[Long]
        println(chatid)
        findUserActor(a) match{
          case Some(actor) =>
            println("found actor... ")
            actor ! JsonRequest(jr.subjectOp, Json.obj(
              "from" -> uuid,
              "slot" -> (jr.json \ "slot").as[String],
              "op" -> (jr.json \ "op").as[String],
              "data" -> (jr.json \ "data")
            ))
          case None => println(s"Actor($a) NOT found..doing nothing!"); usermap.foreach(println)// think about what we might want to do in this case.. (send email to user since they are not online)
        }
      })
    }else{
      useractor ! jr
    }
  }

}



// TODO: simplify this with an akka Agent
case class GetUserActor(uuid: String, chatid: String)
class LockActor extends Actor{

  def receive = {
    case GetUserActor(uuid, chatid) =>
      val actor = UserActor.usermap.get(uuid) match{
        case Some(actor) => actor
        case None =>
          // TODO: this is a hacky way to get psudeo actors unique to a chat
          val ar = Akka.system.actorOf(Props(new UserActor(uuid,chatid)),uuid)
          UserActor.usermap += uuid -> ar
          ar
      }
      sender ! actor
  }
}

// (CA) - One of possibly many web-socket connections (1 per device)
class WebSocketActor(val uuid: String, val chatid: String = "0", val isComet: Boolean = false) extends Actor with ActorLogging{

  // TODO: consider naming this actor by the ip and port that is in use on the client ?

  log.info(s"WebSocketActor create: $uuid")
  var members = Set.empty[String]
  val (socketEnumerator, socketChannel) = Concurrent.broadcast[JsValue]

  val userActor = {
    // TODO: this is where we will locate the "single" user actor on the cluster...
    implicit val timeout =  Timeout(3 seconds)
    UserActor.lockActor ? GetUserActor(uuid, chatid)
  }

  def receive = {
    case Connect(userid) =>
      // we are connected... so tell the sender
      val scopeSender = sender  // save the sender to use in future below
      userActor.onSuccess{
        case useractor:ActorRef =>
          useractor ! UserSocketConnect(socketChannel, isComet)
          // the user socket is wire for sound... now lets get this party started.
          scopeSender ! NowConnected(socketEnumerator)
      }

    case d: UserSocketDisconnect =>
      // the user actor has removed a ref to this socket.. so we can die
      context.stop(self)  // stop myself.

    case jr:JsonRequest =>
      userActor.map {
        case (ua:ActorRef) =>
          UserActor.route(uuid, ua, JsonRequest(jr.subjectOp, jr.json, Some((socketChannel,isComet))))
        case _ => throw new Exception("Unable to get a User Ref.  !!!!")
      }

    case Quit(username) =>
      userActor.onSuccess {
        case useractor: ActorRef =>
          useractor ! UserSocketDisconnect(socketChannel)
      }
      socketChannel.end() // end the iteratee and close the socket
  }

}





object WebSocketHandler {

  implicit val timeout = Timeout(1 second)

  var connections = List[ActorRef]()

  def cometSend(jid: String, json: JsValue) ={
    val op = (json \ "op").as[String]
    val slot = (json \ "slot").as[String]
    println(s"comet send doing $op")
    UserActor.usermap.get(jid) match{
      case Some(actor) => println(s"found actor... $jid"); actor ! JsonRequest("%s-%s".format(slot,op), json)
      case None => println(s"Actor($jid) NOT found..doing nothing!");
    }
  }

  def connect(uuid: String, chatid: String):scala.concurrent.Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    println(s"WebSocketHandler: $uuid")
    val wsActor = Akka.system.actorOf(Props(new WebSocketActor(uuid, chatid)))
    //connections = connections :+ wsActor
    (wsActor ? Connect(uuid)).map {
      case NowConnected(enumerator) =>
        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] { event =>
          //println(event)
          wsActor ! JsonRequest((event \ "slot").as[String] + "-"+ (event \ "op").as[String], event)
        }.map { _ =>
          wsActor ! Quit(uuid)
        }
        (iteratee,enumerator)

      case Quit(userid) =>
        connections = connections.filterNot(c => c == wsActor)
        val iteratee = Done[JsValue,Unit]((),Input.EOF)
        val enumerator =  Enumerator[JsValue](JsObject(Seq("op" -> JsString("done")))).andThen(Enumerator.enumInput(Input.EOF))
        (iteratee,enumerator)

      case CannotConnect(error) =>
        // Connection error
        // A finished Iteratee sending EOF
        val iteratee = Done[JsValue,Unit]((),Input.EOF)
        // Send an error and close the socket
        val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))
        (iteratee,enumerator)
    }

  }

  def connectComet(uuid: String, chatid: String) = {
    // TODO: comet user actors never die .. since there is no disconnect message that is sent
    val wsActor = Akka.system.actorOf(Props(new WebSocketActor(uuid, chatid, true)))
    println("Sensing Connect to CometActor")
    (wsActor ? Connect(uuid)).map {
      case NowConnected(enumerator) =>
        println("COMET NOW CONNECTED !!!")
        // just return the enumerator for comet
        enumerator

      // TODO: never called..
      case Quit(userid) =>
        connections = connections.filterNot(c => c == wsActor)
        val enumerator =  Enumerator[JsValue](JsObject(Seq("op" -> JsString("done")))).andThen(Enumerator.enumInput(Input.EOF))
        enumerator
      // TODO: never called..
      case CannotConnect(error) =>
        // Send an error and close the socket
        val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))
        enumerator
    }
  }

}