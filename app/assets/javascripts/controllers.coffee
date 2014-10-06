#
# Author: Corey Auger
# corey@nxtwv.com
#

webrtcControllers = angular.module('webrtcControllers', [])


webrtcControllers.controller('HomeCtrl', ($scope, $routeParams, $location, worker) ->
  $scope.user =
    name: ''
    role: ''
  $scope.room =
    name: ''
    members: ''

  $scope.connect = (u) ->
    worker.username = @username
    worker.role = @role
    worker.testing = true
    worker.connect()

  $scope.joinRoom = (name) ->
    $location.path('/room/'+$scope.room.name+'/'+$scope.room.members)
    setTimeout(->
      $scope.$apply()
    ,0)

  worker.onNext({slot:'room', op:'list',data:{}})


).controller('RoomCtrl',($scope, $routeParams, $location, worker) ->

  $scope.room = $routeParams.room
  $scope.members = $routeParams.members.split(',')
  $scope.memberList = []
  $scope.peers = []
  $scope.local = {}
  $scope.msgs = []
  $scope.message = ''
  jidToPeerId = {}
  $scope.chat =
    active: false

  dataChannelList = []

  $scope.toggleChat = ->
    $scope.chat.active = !$scope.chat.active;
    setTimeout(->
      $scope.$apply()
    ,0)

  $scope.sendMessage = (txt) ->
    $scope.msgs.push(txt)
    for dc in dataChannelList
      console.log('dataChannel', dc)
      dc.send(txt)
    $scope.message = ''
    setTimeout( ->
      $scope.$apply()
    ,0)

  $scope.webrtc =
    muteAudio: false
    muteVideo: false
    hangup: ->
      worker.webrtc().stop()
    call: (name) ->
      alert('call')
    toggleMuteVideo: ->
      $scope.webrtc.muteVideo = !$scope.webrtc.muteVideo
      worker.walkabout.webrtcVideoMute($scope.webrtc.muteVideo);
      setTimeout( ->
        $scope.$apply();
      ,0)
    toggleMuteAudio: ->
      $scope.webrtc.muteAudio = !$scope.webrtc.muteAudio
      worker.walkabout.webrtcAudioMute($scope.webrtc.muteAudio);
      setTimeout( ->
        $scope.$apply()
      ,0)

  worker.webrtc().onAddRemoteStream = (uuid, video, dataChannel) ->
    id = $scope.peers.length+1;
    dataChannelList.push(dataChannel)
    $scope.peers.push({
      uuid:uuid
      local: false
      username: ''
      id: id
    })
    jidToPeerId[uuid] = id
    dataChannel.onmessage = (msg) =>
      console.log('onmessage',msg)
      $scope.msgs.push(msg.data)
      setTimeout(->
        $scope.$apply()
      ,0)
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
    id = 0
    $scope.local =
      uuid:worker.uuid()
      username: ''
      local: true
      id: id
    #$scope.peers.push($scope.local)
    jidToPeerId[worker.uuid()] = id
    setTimeout( ->
      $scope.$apply()
      $('#video'+id).append(video)
    ,0)

  roomSubject = worker.subject('room')
  roomSub = roomSubject.filter( (r) -> r.op == 'join' ).subscribe( (ret) ->
    console.log('ret.data.members',ret.data.members)
    worker.webrtc().init($scope.room, true)
  )

  worker.onNext({slot:'room',op:'join',data:{name: $scope.room, members:$scope.members}})

  $scope.$on("$destroy", ->
    worker.webrtc().stop()
    roomSub.dispose()
  )

).controller('AppCtrl',($scope, $routeParams, $location, worker) ->
  # always in scope...
  $scope.roomList = []

  roomSubject = worker.subject('room')
  roomSub = roomSubject.filter( (r) -> r.op == 'list' ).subscribe( (ret) ->
    console.log('room list', ret.data.rooms)
    $scope.roomList = ret.data.rooms
    setTimeout(->
      $scope.$apply()
    ,0)
  )

)


webrtcControllers.factory("worker",['$rootScope','$q', ($rootScope,$q) ->
  window.WorkerData.testing = true
  window.WorkerData.role = 'CON'
  worker = new window.SocketWorker('username',window._uuid)
  worker.controllerOps
])