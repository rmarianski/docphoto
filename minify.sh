#!/bin/bash

(cd src/main/resources/public/js && \
  cp plupload/js/plupload.full.js docphoto-min.js && \
  java -jar plovr.jar build config.js >> docphoto-min.js)

cat `find src/main/resources/public/css/google -name '*.css'` \
  `find src/main/resources/public/css/theme -name '*.css'` \
  src/main/resources/public/css/uni-form.css \
  src/main/resources/public/css/docphoto.css \
  > css-input.css

java -jar yui.jar -o src/main/resources/public/css/docphoto-min.css css-input.css

rm css-input.css
