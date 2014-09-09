#
# Author: Corey Auger
# corey@nxtwv.com
#
#

# create the main "controllers" object that all controllers are constructed off...
webrtcControllers = angular.module('webrtcControllers', [])
.config(($provide, $compileProvider, $filterProvider) ->
  $provide.value('a','value of a')
)


webrtcControllers.controller('HomeCtrl', ($scope, $routeParams, $location, worker) ->

)




webrtcControllers.factory("worker",['$rootScope','$q', ($rootScope,$q) ->
  worker = new window.WalkaboutSocketWorker('uuid', 'username', parseInt(window._chatid), $rootScope)
  worker.controllerOps
])