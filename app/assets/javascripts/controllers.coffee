#
# Author: Corey Auger
# corey@nxtwv.com
#

webrtcControllers = angular.module('webrtcControllers', [])


webrtcControllers.controller('HomeCtrl', ($scope, $routeParams, $location, worker) ->
  $scope.room = ''
  $scope.joinRoom = (name) ->
    $location.path('/room/'+name)
    setTimeout(->
      $scope.$apply()
    ,0)
  $scope.detect = window.DetectRTC

  worker.onNext({slot:'room', op:'list',data:{}})


).controller('RoomCtrl',($scope, $routeParams, $location, worker) ->

  $scope.room = $routeParams.room
  $scope.memberList = []
  $scope.peers = []
  jidToPeerId = {}

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


  worker.webrtc().onAddRemoteStream = (uuid, video) ->
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



  roomSubject = worker.subject('room')
  roomSub = roomSubject.filter( (r) -> r.op == 'join' ).subscribe( (ret) ->
    worker.webrtc().init($scope.room,true)
  )


  worker.onNext({slot:'room', op:'list',data:{}})
  worker.onNext({slot:'room',op:'join',data:{name: $scope.room}})

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
  worker = new window.SocketWorker(window._uuid, 'username', parseInt(window._chatid), $rootScope)
  worker.controllerOps
])