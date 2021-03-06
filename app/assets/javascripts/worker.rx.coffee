
#
# Global struct to hold app data
window.WorkerData =
  name: ''
  testing: false
  providers: []
  Worker: null
  pending: []



#
# CometSocket class
class CometSocket
  constructor: (uuid, token, domain) ->
    @url = uuid
    console.log("CometSocket calling create")
    @$frame = $('#comet')
    if( this.$frame.size() == 0 )
      #$('body').append('<iframe id="comet" src="http://'+domain+':9009/ws/comet/'+username+'/'+token+'" style="visibility: hidden"></iframe>')
      $('body').append('<iframe id="comet" src="https://'+domain+'/ws/comet/'+username+'" style="visibility: hidden"></iframe>')
      @.$frame = $('#comet')
  send: (data) ->
    console.log("CometSocket SEND")
    data.time = new Date().getTime()
    data.rand = Math.random()
    $.post( "/ws/comet/send/", { data: data }
      ,->
        console.log('comet send success')
      , ->
        console.log('comet send fail')
    )

#
# Worker Class
class window.NGWorker
  constructor: (username, token, domain) ->
    @domain = domain || self.location.hostname
    @username = username
    @isConnected = false
    @retrySocket = true
    @retryTimeout = 5000
    @subjects = {}

    @controllerOps =
      username: -> @username
      subject: (subject) => @subject(subject)
      onNext: (data) => @onNext(data)
      webrtc: => @webrtc
      connect: => @connect()
      verifyConnection: =>
        if( !@isConnected && @retrySocket)
          @connect()
    @connect()
    @webrtc = new window.WebRTC(@)
  onSocketClose: null       # callback
  socketRetry: ->
    @isConnected = false
    @retrySocket = true
    console.log('ws: worker socket CLOSED.  trying to reconnect')
    @retryTimeout = @retryTimeout * 2
    if( @onSocketClose? )
      @onSocketClose()
    setTimeout(=>
      @connect()
    ,@retryTimeout)

  connect: -> # This gets implemented in Implementing class See WalkaboutSocketWorker

  onNext: (data) ->

  end: ->

  subject: (subject) ->
    if( !@subjects[subject] )
      @subjects[subject] = new Rx.Subject()
      @wsSubject.filter((s) ->s.slot == subject ).subscribe(@subjects[subject])
    @subjects[subject]

  replaySubject:(subject) ->
    if( !@subjects[subject] )
      @subjects[subject] = new Rx.ReplaySubject()
      @wsSubject.filter((s) ->s.slot == subject ).subscribe(@subjects[subject])
    @subjects[subject]

# end of worker class...




#
# WalkaboutSocketWorker
class window.SocketWorker extends NGWorker
  constructor: (username, token, domain) ->
    super(username, token, domain)
  connect: ->
    if( !@isConnected && @retrySocket)
      @retrySocket = false
      # this is a shit test for android stock.. but seems like the best out there..
      # http://stackoverflow.com/questions/14403766/how-to-detect-the-stock-android-browser
      nua = navigator.userAgent;
      isAndroidStock = (nua.indexOf('Android ') > -1 && nua.indexOf('Chrome') == -1 && nua.indexOf('Firefox') == -1 && nua.indexOf('Opera') == -1)
      if isAndroidStock || !window.WebSocket
        @ws = new CometSocket(@username, @token)
        ws = @ws;
        setTimeout( ->
          ws.onopen({})  # fire the open event..
        ,2500)
      else
        try
          # NOTE: you should always use wss .. regular ws connections will be cached and proxied and therefor be messed up in the real world
          #@ws = new WebSocket('wss://'+@domain+':'+self.location.port+'/api/'+@username+'/'+@token);
          if( window.WorkerData.testing )
            console.log('ws://'+@domain+':'+self.location.port+'/api/test/'+@username+'/'+@token+'/'+window.WorkerData.role)
            @ws = new WebSocket('ws://'+@domain+':'+self.location.port+'/api/test/'+@username+'/'+@token+'/'+window.WorkerData.role)
          else
            console.log('ws://'+@domain+':'+self.location.port+'/api/'+@username+'/'+@token)
            @ws = new WebSocket('ws://'+@domain+':'+self.location.port+'/api/'+@username+'/'+@token)
        catch e
          @socketRetry()

      @ws.onopen = (evt) =>
        console.log('worker websocket CONNECT.')
        @retryTimeout = 5000
        setTimeout(=>
          @isConnected = true
          @retrySocket = true
          console.log('Setting isConnected = ' + @isConnected)
          # Send any pending request..
          console.log("Sending " + WorkerData.pending.length + " items from queue")
          for p of WorkerData.pending
            @onNext( WorkerData.pending[p] )
          WorkerData.pending = []    # clear the queue
        ,0)
      @ws.onerror = (evt) =>
        @socketRetry()

      @ws.onclose = (evt) =>
        @socketRetry()
      #alert('You have been disconnected.  Please refresh you browser.')

      ws = @ws
      @wsObservable = Rx.Observable.create( (obs) ->
        # iframe posts back to here...
        window.cometMessage = (data) ->
          console.log('got a comet msg ',data)
          #obs.onNext(JSON.parse(data))
          obs.onNext(data)
        ws.onmessage = (data) ->
          #console.log('ws: onmessage ' + data.data)
          obs.onNext(JSON.parse(data.data))
        ws.onerror = (err) ->
          console.log('ws: Worker socket ERROR:', err)
        # TODO: propagate the exception onto the observable here?
        ->
          console.log('@wsObservable dispose')
      )
      @wsSubject = new Rx.Subject() if not @wsSubject?
      @wsObservable.subscribe(@wsSubject)

  onNext: (data) ->
    dataStr = JSON.stringify(data)
    console.log('ws: Send: ' + dataStr)
    if( @isConnected )
      @ws.send( dataStr )
    else
      WorkerData.pending.push(data)
  end: ->
    WorkerData.Worker.ws.close()






















