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


sealed abstract class User(val uuid:String)
case class Patient(u:String) extends User(u)
case class Physician(u:String) extends User(u)
case class Consultant(u:String) extends User(u)

object User{
  def create(uuid: String, t:String) = {
    t match{
      case "PHY" => Physician(uuid)
      case "CON" => Consultant(uuid)
      case _ => Patient(uuid)
    }
  }
}

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


class UserActor(val user: User) extends Actor with ActorLogging{

  implicit val timeout = Timeout(3 second)
  log.info(s"UserActor create: ${user.uuid}")
  var socketmap = Map[String,(Concurrent.Channel[JsValue], Boolean)]()

  val dateFormatUtc: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  dateFormatUtc.setTimeZone(TimeZone.getTimeZone("UTC"))


  def pushRoomList = {
    socketmap.values.foreach{
      case (soc, isComet) =>
        soc.push(Json.obj(
          "slot" -> "room",
          "op" -> "list",
          "data" -> Json.obj(
            "rooms" -> UserActor.rooms.values.map(_.toJson)
          )
        ))
        // (CA) - this is super lame...
        // we need to pad come request.. and turn off gzip to all for each request to be pushed more info on my blog.
        if (isComet)
          soc.push(Json.obj("padding" -> Array.fill[Char](25 * 1024)(' ').mkString))
    }
  }

  lazy val ops = Map[String, ((JsValue) => Future[JsValue])](
    "room-join" -> ((json: JsValue) =>{
      val name = (json \ "data" \ "name").as[String]
      user match{
        case Consultant(_) | Physician(_) =>
          (UserActor.lockActor ? GetRoom(name)) map {
            case room:Room =>
              room.members += user.uuid
              pushRoomList
              room.toJson
          }
        case _ =>
          Future.successful(Json.obj("error" -> "you do not have permission to create a room"))
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
      Future.successful(Json.arr(Json.obj( "room" -> room, "uuid" -> user.uuid, "data" -> data )) )
    }),
    "webrtc-join" ->((json: JsValue) =>{
      log.info(s"webrtc: $json")
      val room = (json \ "data" \ "room" ).as[String]
      //val offer = (json \ "data" \ "sendOffer" ).as[Boolean]
      log.debug(s"WEBRTC::JOIN room $room")
      // The Joiner is ALWAYS the initiator for the new connections to the peers...
      val members = UserActor.rooms(room).members
      println(s"You: ${user.uuid}")
      val others = members.filterNot(_ == user.uuid)
      log.debug("RELAY addPeer Other %s".format(others.mkString(",") ))
      members.filter(_ != user.uuid).foreach( uid => UserActor.usermap.get(uid) match{
        case Some(ua) =>
          UserActor.route(user.uuid, ua,JsonRequest("webrtc-relay",Json.obj(
            "room" -> room,
            "slot" -> "webrtc",
            "op" -> "relay",
            "data" -> Json.obj(
              "room" -> room,
              "actors" -> others,
              "type" -> "addPeer",
              "uuid" -> user.uuid,
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
      log.info(s"Num sockets left $numsockets for ${user.uuid}")
      if( socketmap.isEmpty  ){
        UserActor.usermap -= user.uuid
        // looping through ALL rooms to remove this member is bad !
        UserActor.rooms.values.foreach( r => r.members -= user.uuid )
        UserActor.rooms = UserActor.rooms.filter( _._2.members.size > 0 )
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
case class GetUserActor(user: User)
case class GetRoom(roomName: String)
class LockActor extends Actor{

  def receive = {
    case GetUserActor(user) =>
      val actor = UserActor.usermap.get(user.uuid) match{
        case Some(actor) => actor
        case None =>
          // TODO: this is a hacky way to get psudeo actors unique to a chat
          val ar = Akka.system.actorOf(Props(new UserActor(user)),user.uuid)
          UserActor.usermap += user.uuid -> ar
          ar
      }
      sender ! actor

    case GetRoom(roomName) =>
      val room = UserActor.rooms.get(roomName) match{
        case Some(room) => room
        case None =>
          val r = new Room(roomName)
          UserActor.rooms += (roomName -> r)
      }
      sender ! room
  }
}

// (CA) - One of possibly many web-socket connections (1 per device)
class WebSocketActor(val user: User, val isComet: Boolean = false) extends Actor with ActorLogging{

  // TODO: consider naming this actor by the ip and port that is in use on the client ?

  log.info(s"WebSocketActor create: ${user.uuid}")
  var members = Set.empty[String]
  val (socketEnumerator, socketChannel) = Concurrent.broadcast[JsValue]

  val userActor = {
    // TODO: this is where we will locate the "single" user actor on the cluster...
    implicit val timeout =  Timeout(3 seconds)
    UserActor.lockActor ? GetUserActor(user)
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
          UserActor.route(user.uuid, ua, JsonRequest(jr.subjectOp, jr.json, Some((socketChannel,isComet))))
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


  def connect( user: User):scala.concurrent.Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    println(s"WebSocketHandler: ${user.uuid}")
    val wsActor = Akka.system.actorOf(Props(new WebSocketActor(user)))
    //connections = connections :+ wsActor
    (wsActor ? Connect(user.uuid)).map {
      case NowConnected(enumerator) =>
        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] { event =>
          //println(event)
          wsActor ! JsonRequest((event \ "slot").as[String] + "-"+ (event \ "op").as[String], event)
        }.map { _ =>
          wsActor ! Quit(user.uuid)
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


}