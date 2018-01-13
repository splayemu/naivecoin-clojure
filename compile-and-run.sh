#!/bin/bash
lein clean
lein compile
lein uberjar
HTTP_PORT=3002 P2P_port=6002 java -jar target/blockchain-0.1.0-SNAPSHOT-standalone.jar
