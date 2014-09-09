# (CA) - code converted from here...
# https://github.com/fycth/webrtcexample/blob/master/www/js/WebRTCO.js
# mixed in with things from here:
# https://github.com/anoek/webrtc-group-chat-example/blob/master/client.html

class window.WebRTC
  # <WebRTC Adapter>
  RTCPeerConnection = null
  getUserMedia = null
  attachMediaStream = null
  reattachMediaStream = null
  webrtcDetectedBrowser = null
  #</WebRTC Adapter>
  debug = false

  constructor: (worker) ->
    @worker = worker
    @localStream = null
    @peerMedia = {}
    @peers = {}
    @readyToRock = false

    @USE_VIDEO = true
    @webrtcSubject = @worker.subject('webrtc')
    @webRtcRelay = @webrtcSubject.subscribe( (data) =>
      #msg = data.ret[0].msg
      console.log('RECEIVE S->C\n',data)
      if( @readyToRock && data.ret && data.ret[0] )
        obj = data.ret[0].data
        if( obj )
          if(obj.type == 'addPeer' )
            @removePeer(obj)  # make sure they are not in the peer list
            @addPeer(obj)     # now add and send offer.
          else if( obj.type == 'sessionDescription' )
            @sessionDescription(obj)
          else if( obj.type == 'iceCandidate' )
            @iceCandidate(obj)
          else if( obj.type == 'hangup' )
            @removePeer(obj)
    ,(e) ->
      console.log('onError: ' + e.message)
    , ->
      console.log('onCompleted')
    )

    #  var channelReady;
    # var channel;
    @sdpConstraints = {'mandatory': {'OfferToReceiveAudio':true, 'OfferToReceiveVideo':true }}
    @pc_config = {"iceServers":
          [
              {url:'stun:stun.l.google.com:19302'},
              {url:'stun:stun01.sipphone.com'},
              {url:'stun:stun.ekiga.net'},
              {url:'stun:stun.fwdnet.net'},
              {url:'stun:stun.ideasip.com'},
              {url:'stun:stun.iptel.org'},
              {url:'stun:stun.rixtelecom.se'},
              {url:'stun:stun.schlund.de'},
              {url:'stun:stun.l.google.com:19302'},
              {url:'stun:stun1.l.google.com:19302'},
              {url:'stun:stun2.l.google.com:19302'},
              {url:'stun:stun3.l.google.com:19302'},
              {url:'stun:stun4.l.google.com:19302'},
              {url:'stun:stunserver.org'},
              {url:'stun:stun.softjoys.com'},
              {url:'stun:stun.voiparound.com'},
              {url:'stun:stun.voipbuster.com'},
              {url:'stun:stun.voipstunt.com'},
              {url:'stun:stun.voxgratia.org'},
              {url:'stun:stun.xten.com'},
              {
                  url: 'turn:waturn.cloudapp.net:443?transport=tcp',
                  credential: 'walkaboutpass',
                  username: 'walkabout'
              },
              {
                  url: 'turn:numb.viagenie.ca',
                  credential: 'muazkh',
                  username: 'webrtc@live.com'
              },
              {
                  url: 'turn:192.158.29.39:3478?transport=udp',
                  credential: 'JZEOEt2V3Qb0y27GRntt2u2PAYA=',
                  username: '28224511:1379330808'
              },
              {
                  url: 'turn:192.158.29.39:3478?transport=tcp',
                  credential: 'JZEOEt2V3Qb0y27GRntt2u2PAYA=',
                  username: '28224511:1379330808'
              }]}
  clog: (str) ->
    if (debug)
      window.console.log(str)

  setDebug: (b) ->
    debug = b

  send: (op, data) ->
    #actors = Object.keys( @peers )
    data.peer_id = @worker.jid
    @worker.send({slot:'webrtc',op:op,data:data})

  stop: ->
    console.log('webrtc::stop')
    peerIds = []
    for k,v of @peers
      v.close()
      peerIds.push(k)
    @send('relay', {
      'type':'hangup'
      'actors': peerIds,
      'jid': @worker.jid
    })
    @peers = {}
    @localStream.close()

  muteVideo: (mute) ->
    if @localStream
      @localStream.getVideoTracks()[0].enabled = !mute

  muteAudio: (mute) ->
    if @localStream
      @localStream.getAudioTracks()[0].enabled = !mute

  init: (chatid,offer) ->
    console.log("Initializing...")
    @initWebRTCAdapter()
    @doGetUserMedia( =>
      # once the user has given us access to their
      # microphone/camcorder, join the channel and start peering up
      #@send('join',{chatid:chatid,sendOffer:offer})
      @send('join',{chatid:chatid,sendOffer:offer})
    )

  onAddRemoteStream: null
  onRemoveRemoteStream: null
  onAddLocalStream: null


  initWebRTCAdapter: ->
    if (navigator.mozGetUserMedia)
      webrtcDetectedBrowser = "firefox"

      RTCPeerConnection = mozRTCPeerConnection;
      window.RTCSessionDescription = mozRTCSessionDescription;
      window.RTCIceCandidate = mozRTCIceCandidate;
      getUserMedia = navigator.mozGetUserMedia.bind(navigator);

      attachMediaStream = (element, stream) ->
        element.mozSrcObject = stream
        element.play()

      reattachMediaStream = (to, from) ->
        to.mozSrcObject = from.mozSrcObject
        to.play()

      MediaStream.prototype.getVideoTracks = -> []

      MediaStream.prototype.getAudioTracks = -> []
      @readyToRock = true
      true
    else if (navigator.webkitGetUserMedia)
      webrtcDetectedBrowser = "chrome"

      RTCPeerConnection = webkitRTCPeerConnection
      getUserMedia = navigator.webkitGetUserMedia.bind(navigator)
      attachMediaStream = (element, stream) ->
        element.src = webkitURL.createObjectURL(stream)

      reattachMediaStream = (to, from) ->
        to.src = from.src

      if (!webkitMediaStream.prototype.getVideoTracks)
        webkitMediaStream.prototype.getVideoTracks = -> @videoTracks
        webkitMediaStream.prototype.getAudioTracks = -> @audioTracks


      if (!webkitRTCPeerConnection.prototype.getLocalStreams)
        webkitRTCPeerConnection.prototype.getLocalStreams = -> @localStreams
        webkitRTCPeerConnection.prototype.getRemoteStreams = -> @remoteStreams
      @readyToRock = true
      true
    else
      false

  # When we join a group, our signaling server will send out 'addPeer' events to each pair
  # of users in the group (creating a fully-connected graph of users, ie if there are 6 people
  # in the channel you will connect directly to the other 5.
  addPeer: (config) ->
    console.log('Signaling server said to add peer:', config)
    peer_id = config.jid;
    if (peer_id in @peers)
      # This could happen if the user joins multiple channels where the other peer is also in. */
      console.log("Already connected to peer " + peer_id)
      return

    peer_connection = new RTCPeerConnection(@pc_config, {"optional": [{"DtlsSrtpKeyAgreement": true}]} )
    @peers[peer_id] = peer_connection

    peer_connection.onicecandidate = (event) =>
      if (event.candidate)
        @send('relay', {
                  'type':'iceCandidate'
                  'actors': [peer_id],
                  'ice_candidate': {
                    'sdpMLineIndex': event.candidate.sdpMLineIndex,
                    'candidate': event.candidate.candidate
                  }
        })

    peer_connection.onaddstream = (event) =>
      console.log("onAddStream: " + event)
      remote_media = if @USE_VIDEO
                        $("<video>")
                     else
                        $("<audio>");
      remote_media.attr("autoplay", "true");
      remote_media.attr("controls", "")
      @peerMedia[peer_id] = remote_media

      attachMediaStream(remote_media[0], event.stream)
      if( @onAddRemoteStream != null)
        @onAddRemoteStream(peer_id,remote_media)
      else
        $('body').append(remote_media)

    # Add our local stream
    console.log('adding local stream')
    peer_connection.addStream(@localStream)

    # Only one side of the peer connection should create the
    # offer, the signaling server picks one to be the offerer.
    # The other user will get a 'sessionDescription' event and will
    # create an offer, then send back an answer 'sessionDescription' to us
    if (config.sendOffer)
      console.log("Creating RTC offer to " + peer_id)
      peer_connection.createOffer((local_description) =>
        console.log("Local offer description is: " + local_description)
        peer_connection.setLocalDescription(local_description
        , =>
          @send('relay',
            {
              'type':'sessionDescription',
              'actors': [peer_id],
              'session_description': local_description
            }
          )
          console.log("Offer setLocalDescription succeeded")
        , ->
          Alert("Offer setLocalDescription failed!")
        )
      , (error) ->
        console.log("Error sending offer: "+error)
      )
    peer_connection


  # Peers exchange session descriptions which contains information
  # about their audio / video settings and that sort of stuff. First
  # the 'offerer' sends a description to the 'answerer' (with type
  # "offer"), then the answerer sends one back (with type "answer").
  sessionDescription: (config) ->
    console.log('Remote description received: '+ config)
    peer_id = config.peer_id
    peer = @peers[peer_id]
    if( !peer )
      #alert('could not locate peer for peer id: '+peer_id)
      console.log('[WARN] - could not locate peer for peer id: '+peer_id)
      console.log('this must be the offer...')
      @doGetUserMedia( =>
        peer = @addPeer({jid:peer_id, sendOffer:false})
        # now we have the peer so lets try this again.
        @sessionDescription(config)
      )
    else
      remote_description = config.session_description
      console.log(config.session_description)

      desc = new RTCSessionDescription(remote_description)
      console.log("Description Object: ", desc)

      peer.setRemoteDescription(desc, =>
        console.log("setRemoteDescription succeeded")
        if (remote_description.type == "offer")
          console.log("Creating answer");
          peer.createAnswer((local_description) =>
            console.log("Answer description is: ", local_description);
            peer.setLocalDescription(local_description
            , =>
              @send('relay',
                {
                  'type':'sessionDescription'
                  'actors': [peer_id],
                  'session_description': local_description
                }
              )
              console.log("Answer setLocalDescription succeeded")
            , ->
              alert("Answer setLocalDescription failed!")
            )
          , (error) ->
            console.log("Error creating answer: ", error)
            console.log(peer)
          )
      ,(error) ->
        console.log("setRemoteDescription error: ", error)
      )




  # The offerer will send a number of ICE Candidate blobs to the answerer so they
  # can begin trying to find the best path to one another on the net.
  iceCandidate: (config) ->
    peer = @peers[config.peer_id];
    ice_candidate = config.ice_candidate;
    console.log('peer addIceCandidate')
    if( peer )
      peer.addIceCandidate(new RTCIceCandidate(ice_candidate)
      , ->
        console.log('Added the ICE Candidate.')
      , (err) ->
        console.log('[ERROR] - ICE Candidate.' + err)
      )
    else
      #alert('[ERROR] - no peer for peer_id: '+config.peer_id)
      console.log('[ERROR] - no peer for peer_id: '+config.peer_id)


  # When a user leaves a channel (or is disconnected from the
  # signaling server) everyone will recieve a 'removePeer' message
  # telling them to trash the media channels they have open for those
  # that peer. If it was this client that left a channel, they'll also
  # receive the removePeers. If this client was disconnected, they
  # wont receive removePeers, but rather the
  # signaling_socket.on('disconnect') code will kick in and tear down
  # all the peer sessions.
  removePeer: (config) ->
    console.log('Signaling server said to remove peer:', config)
    peer_id = config.jid;
    if (peer_id in @peerMedia)
      @peerMedia[peer_id].remove()
    if (peer_id in @peers)
      @peers[peer_id].close()
    if( @onRemoveRemoteStream )
      @onRemoveRemoteStream(peer_id)
    delete @peers[peer_id];
    delete @peerMedia[config.jid]


  doGetUserMedia: (callback) ->
    if( @localStream != null)
      if( callback )
        callback()
        return
    constraints = {"audio": true, "video": {"mandatory": {}, "optional": []}};
    try
      console.log("Requested access to local media with mediaConstraints:\n \"" + JSON.stringify(constraints) + "\"");
      getUserMedia(constraints, (stream) =>
        @localStream = stream
        local_media = if @USE_VIDEO
                        $("<video>")
                      else
                        $("<audio>")
        local_media.attr("autoplay", "autoplay");
        local_media.attr("muted", "true"); # always mute ourselves by default
        local_media.attr("controls", "");
        attachMediaStream(local_media[0], stream)
        if( @onAddLocalStream != null )
          @onAddLocalStream(local_media[0])
        else
          $('body').append(local_media)
        callback()
      , (error) ->
        console.log("Failed to get access to local media. Error code was " + error.code);
      )
    catch e
      console.log("getUserMedia failed with exception: " + e.message)

