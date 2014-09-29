play-webrtc
==================

WebRTC is awesome!  But if you are here you already know that.  Just to review webRTC offers voice video and data transmission directly between peers that support the protocol.  This include modern browsers (chrome, FF, opera) as well as open source code that include builds for android, ios, linux, mac, and windows.

To get a good understanding of how webRTC works you should take a look at the following article which covers everything in great detail:
[http://www.html5rocks.com/en/tutorials/webrtc/infrastructure/](http://www.html5rocks.com/en/tutorials/webrtc/infrastructure/)


### What is play-webrtc 
Simply put play-webrtc is an out of the box relay server that uses reactive websockets with a comet fallback.  It includes code to setup and relay data necessary for setting up a webRTC connection between 2 or more peers.

### Components include:
Play front end to render the web application.
Coffeescript code to connect to the reactive websocket and negotiate sending data between peers.
Iteratee websocket connection.
Akka actor system for message passing and backend logic.

These component serve as a demonstration and starting point for integrating webRTC and possibly other api requests into your own application.

### Additional things you will need:
In a production environment you will want to configure and run your own TURN server.  For information on the setup and configuration of TURN please refer to the following documentation.
https://code.google.com/p/rfc5766-turn-server/wiki/turnserver

### A Note on websockets:
In a production environment you will want your websocket to be a secure connection.  The reasons for this go beyond just encrypting your data.  Websocket data that is now encrypted will fall victim to routers and proxies that have “smart” http caching policies.  This will ultimately render your websocket useless in these environments.  The solution is to use wss:// and no ws://



Setup
-----

1. [Download Typesafe Activator](http://typesafe.com/platform/getstarted) (or copy it over from a USB)
2. Extract the zip and run the `activator` or `activator.bat` script from a non-interactive shell
3. Your browser should open to the Activator UI: [http://localhost:8888](http://localhost:8888)


Create an application
---------------------

##Getting Started

Whether you use our angular JS code or want to integrate with your own, you will need to include 2 javascript files and their dependencies. 

Using our example page
Take a look at what gets included on:
/views/index.scala.html
/views/main.scala.html

####Integration with your own
The first dependency is on RxJS.  If you have not heard of reactive extensions for javascript… you are missing out.  You need to head on over to this page https://github.com/Reactive-Extensions/RxJS  and take a look at what they provide.  Here are the 2 include that you require.

```html
<script src="/javascripts/ext/rxjs/2.2.20/rx.lite.compat.min.js""></script>
<script src="/javascripts/ext/rxjs/2.2.20/rx.async.min.js""></script>
```

Once you have these dependencies added you can add the 2 required js files.

```html
<script src='@routes.Assets.at("javascripts/webrtc.js")' type="text/javascript"></script>
<script src='@routes.Assets.at("javascripts/worker.rx.js")' type="text/javascript"></script>
```

### Initializing
Next thing we need to do is initialize the Websocket Rx worker class.  This can be seen in the controller.coffee file where we create a factory worker.


webrtcControllers.factory("worker",['$rootScope','$q', ($rootScope,$q) ->
  worker = new window.SocketWorker(window._uuid, 'username')
  worker.controllerOps
])

Notice that we pass in some form of a user id (_uuid) and a username to the SocketWorker.  The line below that is angular specific and simple exposes some operations from the worker.  In a non angular environment you can simple initialize the class like so:

```coffee
worker = new window.SocketWorker(window._uuid, 'username')
```

### Handling Events
Next you will want to setup the common webrtc event handlers.  The 3 most important ones being the following:
```javascript
onAddRemoteStream
onRemoveRemoteStream
onAddLocalStream
```

These let you know when someone has joined as well as when your local video feed is ready.  The methods simply hand you a video object that you can insert into the DOM at whatever location you desire.  In our angular code this looks like the following.

```coffee
 worker.webrtc().onAddRemoteStream = (uuid, video, dataChannel) ->
    id = $scope.peers.length+1;
    $scope.peers.push({
      uuid:uuid,
      username: '',
      id: id
    })
    jidToPeerId[uuid] = id
    setTimeout(->
      $scope.$apply()
      $('#video'+id).append(video)
    ,0)


  worker.webrtc().onRemoveRemoteStream = (uuid) ->
    $scope.peers = $scope.peers.filter((p) ->
      p.uuid != uuid
    )
    setTimeout(->
      $scope.$apply()
    ,0)


  worker.webrtc().onAddLocalStream = (video) ->
    id = $scope.peers.length+1
    $scope.peers.push({
      uuid:worker.uuid(),
      username: '',
      id: id
    })
    jidToPeerId[worker.uuid()] = id
    setTimeout( ->
      $scope.$apply()
      $('#video'+id).append(video)
    ,0)
```


Note that in a non angular setting your would simply use 

```javascript
worker.webrtc.onAddRemoteStream = function(uuid, video){
	// .. do stuff with video .. for eg:
	$(‘body’).append(video);
}
```


### Starting the Session
We first subscribe to our websocket stream to receive events about the appropriate message that we are interested in.  The “room” category and the “join” event.

```coffee
roomSubject = worker.subject('room')
  roomSub = roomSubject.filter( (r) -> r.op == 'join' ).subscribe( (ret) ->
    console.log('ret.data.members',ret.data.members)
    worker.webrtc().init($scope.room, true)
  )
```

This simply will initialize a webrtc session with anyone that joins the room.

All that is left to do now is announce that we have joined the room.

```coffee
worker.onNext({slot:'room',op:'join',data:{name: $scope.room}})
```




Open in an IDE
--------------

If you want to use an IDE (Eclipse or IntelliJ), click on *Code*, select *Open*, and then select your IDE.  This will walk you through the steps to generate the project files and open the project.  Alternatively you can edit files in the Activator UI.


Update Dependencies
-------------------

AngularJS
[https://angularjs.org/](https://angularjs.org/)
Bootstrap
[http://getbootstrap.com/components/](http://getbootstrap.com/components/)
RxJS
[https://github.com/Reactive-Extensions/RxJS](https://github.com/Reactive-Extensions/RxJS)

Other information
-----------------

Read the tutorial html
/tutorial/index.html
