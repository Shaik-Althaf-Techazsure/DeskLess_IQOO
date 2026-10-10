# syntax=docker/dockerfile:1

# DeskLess Android app, built and handed out from a container: no Android Studio, JDK or SDK on the host.
#   build stage   runs the unit tests and assembles the APK
#   final stage   serves the APK with an install page

# Java 25 is what gradle/gradle-daemon-jvm.properties asks for.
FROM eclipse-temurin:25-jdk-jammy AS build

# Keep these in step with compileSdk in app/build.gradle.kts.
ARG CMDLINE_TOOLS=13114758
ARG ANDROID_PLATFORM=android-37.0
ARG BUILD_TOOLS=36.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/gradle/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*

# fetch URL FILE: a download that picks up where it stopped after a stall or a dropped connection.
RUN printf '%s\n' '#!/bin/sh' \
      'for attempt in $(seq 1 40); do' \
      '  curl -fL -C - --speed-limit 2048 --speed-time 30 -o "$2" "$1" && exit 0' \
      '  sleep 3' \
      'done' \
      'exit 1' > /usr/local/bin/fetch \
 && chmod +x /usr/local/bin/fetch

RUN fetch https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip /tmp/tools.zip \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platforms;${ANDROID_PLATFORM}" "build-tools;${BUILD_TOOLS}"

WORKDIR /src

# The Gradle version the wrapper names, checked against the wrapper's SHA-256.
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
RUN prop() { tr -d '\r' < gradle/wrapper/gradle-wrapper.properties | sed -n "s/^$1=//p" | sed 's/\\//g'; } \
 && fetch "$(prop distributionUrl)" /tmp/gradle.zip \
 && echo "$(prop distributionSha256Sum)  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-* /opt/gradle \
 && rm /tmp/gradle.zip

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY app app

# The server the app signs in and sends reports to. Empty keeps the build's own default; the address can also be
# changed on the app's first screen.
ARG SERVER_URL=""

# -PphoneOnly leaves out the emulator's x86_64 libraries, which halves the APK.
# Downloaded dependencies stay in a build cache, so a rebuild after an edit does not fetch them again.
RUN --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon testDebugUnitTest assembleDebug -PphoneOnly ${SERVER_URL:+-PserverUrl=$SERVER_URL} \
 && cp app/build/outputs/apk/debug/app-debug.apk /DeskLess.apk


FROM caddy:2-alpine

COPY docker/Caddyfile /etc/caddy/Caddyfile
COPY docker/site /srv
COPY --from=build /DeskLess.apk /srv/DeskLess.apk

EXPOSE 80

# With a folder mounted at /out, the APK is also copied there for Office Kit file transfer.
CMD ["sh", "-c", "[ -d /out ] && cp /srv/DeskLess.apk /out/; exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]
