OffloadingController
====================

Controller for Feed Sync Server: updated 18/03/2014

Usage : 
 OffloadingController -resource <resource-path> [OPTIONS]
 -a,--server <arg>      FeedSync Server Address
 -h,--help              Print help
 -p,--port <arg>        FeedSync Server Port
 -panic,--panic <arg>   Panic Time
 -r,--resource <arg>    Resource to offload
 -t,--timelife <arg>    Life time of resource
 
 
Working methods:

- GET list of users
- POST new content
- PUT list of expected receivers
- GET acks from receivers
