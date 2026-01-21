#!/bin/bash

clojure -A:examples -M -m basic-chat && \
  clojure -A:examples -M -m tool-integration && \
  clojure -A:examples -M -m multi-agent && \
  clojure -A:examples -M -m streaming-chat && \
  clojure -A:examples -M -m config-skill-output
