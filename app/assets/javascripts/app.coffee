#
# Author: Corey Auger
# corey@nxtwv.com
#

webrtcApp = window.angular.module('webrtcApp', ['ngRoute','ngSanitize','ui.bootstrap','webrtcControllers']).config( ($sceDelegateProvider) ->
  $sceDelegateProvider.resourceUrlWhitelist(
    [
      'self'
      # Allow loading from outer templates domain.
      'https://apps.com/**'
    ]
  )
)

webrtcApp.config(($locationProvider,$routeProvider) ->
  # $locationProvider.html5Mode(true);
  $routeProvider.when('/home',
    templateUrl: '/assets/partials/home.html',
    controller: 'HomeCtrl'
  ).when('/room/:room',
    templateUrl: '/assets/partials/room.html',
    controller: 'RoomCtrl'
  ).otherwise({
    redirectTo: '/home'
  })
)


webrtcApp.run(($rootScope, $location, worker, $modal, $sce) ->

  $rootScope.page =
    header: 'nav'
    title: 'test'
    online: true
    error: ''

  $rootScope.webrtc =
    support: DetectRTC

  $rootScope.detect = window.DetectRTC

  window.addEventListener('online',  ->
    $rootScope.page.online = true
    $rootScope.page.error = ''
    setTimeout(->
      $rootScope.$apply()
    ,0)
  )
  window.addEventListener('offline', ->
    $rootScope.page.online = false
    $rootScope.page.error = 'You have gone <strong>offline</strong>'
    setTimeout(->
      $rootScope.$apply()
    ,0)
  )
)


webrtcApp.directive('errSrc', ->
  link: (scope, element, attrs) ->
    element.bind('error', ->
      element.attr('src', attrs.errSrc)
    )
)



