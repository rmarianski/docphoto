#!/bin/bash

(cd resources/public/js && \
  cp plupload/js/plupload.full.js docphoto-min.js && \
  java -jar ../../../plovr.jar build config.js >> docphoto-min.js)

cat `find resources/public/css/google -name '*.css'` \
  resources/public/css/uni-form.css \
  resources/public/css/docphoto.css \
  > css-input.css

java -jar yui.jar -o resources/public/css/docphoto-min.css css-input.css

rm css-input.css
