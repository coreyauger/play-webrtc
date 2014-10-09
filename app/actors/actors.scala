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


sealed abstract class User(val username:String, val uuid:String)
case class Patient(n:String, u:String) extends User(n,u)
case class Physician(n:String, u:String) extends User(n,u)
case class Consultant(n:String, u:String) extends User(n,u)

object User{
  def create(username: String, uuid: String, t:String = "PAT") = {
    t match{
      case "PHY" => Physician(username, uuid)
      case "CON" => Consultant(username, uuid)
      case _ => Patient(username, uuid)
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
  override def toString = toJson.toString()
}


class UserActor(val user: User) extends Actor with ActorLogging{

  implicit val timeout = Timeout(5 second)
  log.info(s"UserActor create: ${user.username}")
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
      val members = (json \ "data" \ "members").as[List[String]]
      log.info(s"User ${user.username} asking to create/join room $name with members: $members")
      user match{
        case (_:Consultant | _:Physician) =>
          println(s"Get room $name")
          (UserActor.lockActor ? GetRoom(name)) map {
            case room:Room =>
              room.members = (user.username :: members).toSet
              println(s"User ${user.username} owns room $room")
              println("users online.......")
              socketmap.keys.foreach(println)
              members.foreach { m =>
                socketmap.get(m) match {
                  case Some((soc, isComet)) =>
                    println(s"Sending room [${room.name}] invite to: $m")
                    soc.push(Json.obj(
                      "slot" -> "room",
                      "op" -> "invite",
                      "data" -> Json.obj(
                        "rooms" -> name
                      )
                    ))
                  case None => println(s"User not online $m")
                }
              }
              pushRoomList
              room.toJson
            case None =>
              println("...")
              log.error("Creating a room FAILED")
              Json.obj("error" -> "create room failed.")
          }
        case Patient(_,_) =>
          UserActor.rooms.get(name) match {
            case Some(room) =>
              if (room.members.contains(user.username)) {
                pushRoomList
                Future.successful(room.toJson)
              } else {
                Future.successful(Json.obj("error" -> "Unauthorized to join this room"))
              }
            case _ => Future.successful(Json.obj("error" -> "PAT can not create a room"))
          }
        case _ =>
          log.error("Unknown USER TYPE")
          Future.successful(Json.obj("error" -> "Unknown user type."))
      }
    }),
    "room-list" -> ((json: JsValue) => {
      println(s"ALL ROOMS.......")
      UserActor.rooms.values.foreach(println)
      Future.successful(Json.obj(
        // only list rooms that this user is a member in
        "rooms" -> UserActor.rooms.filter( r => r._2.members.contains(user.username) ).values.map(_.toJson)
      ))
    }),
    "webrtc-relay" ->((json: JsValue) =>{
      log.info(s"webrtc: $json")
      val data = (json \ "data" ).as[JsObject]
      val room = (json \ "data" \ "room" ).as[String]
      // this is from another user.. to send back down socket channels..
      Future.successful(Json.arr(Json.obj( "room" -> room, "uuid" -> user.username, "data" -> data )) )
    }),
    "webrtc-join" ->((json: JsValue) =>{
      log.info(s"webrtc: $json")
      val room = (json \ "data" \ "room" ).as[String]
      //val offer = (json \ "data" \ "sendOffer" ).as[Boolean]
      log.debug(s"WEBRTC::JOIN room $room")
      // The Joiner is ALWAYS the initiator for the new connections to the peers...
      val members = UserActor.rooms(room).members
      println(s"You: ${user.username}")
      val others = members.filterNot(_ == user.username)
      log.debug("RELAY addPeer Other %s".format(others.mkString(",") ))
      members.filter(_ != user.username).foreach( uid => UserActor.usermap.get(uid) match{
        case Some(ua) =>
          UserActor.route(user.username, ua,JsonRequest("webrtc-relay",Json.obj(
            "room" -> room,
            "slot" -> "webrtc",
            "op" -> "relay",
            "data" -> Json.obj(
              "room" -> room,
              "actors" -> others,
              "type" -> "addPeer",
              "uuid" -> user.username,
              "sendOffer" -> true
            )
          )))
        case None =>
          println(s"User not found: $uid")
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
      socketmap += user.username -> (c.socket, c.isComet)
      sender ! c
      UserActor.rooms.filter( _._2.members.contains(user.username) ).foreach{
        case (name, room) =>
          c.socket.push(Json.obj(
            "slot" -> "room",
            "op" -> "invite",
            "data" -> Json.obj(
              "rooms" -> name
            )
          ))
      }



    case d: UserSocketDisconnect =>
      log.info(s"UserActor::UserSocketDisconnect")
      socketmap -= user.username
      sender ! d
      val numsockets = socketmap.size
      log.info(s"Num sockets left $numsockets for ${user.username}")
      if( socketmap.isEmpty  ){
        UserActor.usermap -= user.username
        // looping through ALL rooms to remove this member is bad !
        user match{
          case (_:Consultant | _:Physician) =>
            UserActor.rooms = UserActor.rooms.filterNot( _._2.members.contains(user.username) )
          case _ => // do nothing...
        }
        //UserActor.rooms.values.foreach( r => r.members -= user.username )
        //UserActor.rooms = UserActor.rooms.filter( _._2.members.size > 0 )
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


  def findUserActor(username: String):Option[ActorRef] ={
    println(s"Actor $username")
    println( usermap.keys )
    usermap.get(username)
  }

  def route(username:String, useractor:ActorRef, jr:JsonRequest) ={
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
              "from" -> username,
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
      println(s"Looking for user actor: ${user.username}")
      val actor = UserActor.usermap.get(user.username) match{
        case Some(actor) => actor
        case None =>
          // TODO: this is a hacky way to get psudeo actors unique to a chat
          val ar = Akka.system.actorOf(Props(new UserActor(user)),user.username)
          UserActor.usermap += user.username -> ar
          ar
      }
      sender ! actor

    case GetRoom(roomName) =>
      println(s"LockActor::GetRoom($roomName)")
      val room = UserActor.rooms.get(roomName) match{
        case Some(r) => r
        case None =>
          val r = new Room(roomName)
          UserActor.rooms += (roomName -> r)
          r
      }
      context.sender ! room
  }
}

// (CA) - One of possibly many web-socket connections (1 per device)
class WebSocketActor(val user: User, val isComet: Boolean = false) extends Actor with ActorLogging{
  log.info(s"WebSocketActor create: ${user.username}")
  var members = Set.empty[String]
  val (socketEnumerator, socketChannel) = Concurrent.broadcast[JsValue]

  val userActor = {
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
          UserActor.route(user.username, ua, JsonRequest(jr.subjectOp, jr.json, Some((socketChannel,isComet))))
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
    println(s"WebSocketHandler: ${user.username}")
    val wsActor = Akka.system.actorOf(Props(new WebSocketActor(user)))
    //connections = connections :+ wsActor
    (wsActor ? Connect(user.username)).map {
      case NowConnected(enumerator) =>
        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] { event =>
          //println(event)
          wsActor ! JsonRequest((event \ "slot").as[String] + "-"+ (event \ "op").as[String], event)
        }.map { _ =>
          wsActor ! Quit(user.username)
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