FROM ubuntu:21.10@sha256:74fd1c59ac5d29527f56a9ac22753571ce4c40a301d083a56d963bde98a7dfdc

ARG UBUNTU_MIRROR=http://archive.ubuntu.com/ubuntu/

ENV LC_ALL=C.UTF-8 LANG=C.UTF-8

ENV UBUNTUDIST=impish

# This prevents questions during package installations
ENV DEBIAN_FRONTEND=noninteractive

RUN echo deb ${UBUNTU_MIRROR} ${UBUNTUDIST} main restricted universe multiverse > /etc/apt/sources.list && \
    echo deb ${UBUNTU_MIRROR} ${UBUNTUDIST}-updates main restricted universe multiverse >> /etc/apt/sources.list && \
    echo deb ${UBUNTU_MIRROR} ${UBUNTUDIST}-backports main restricted universe multiverse >> /etc/apt/sources.list && \
    echo deb ${UBUNTU_MIRROR} ${UBUNTUDIST}-security main restricted universe multiverse >> /etc/apt/sources.list && \
    dpkg --add-architecture i386 && \
    apt-get update -q && \
    apt-get install -qy \
        gnupg2=2.2.20-1ubuntu4 \
        ca-certificates=20210119ubuntu1 \
        wget=1.21-1ubuntu3 \
        git=1:2.32.0-1ubuntu1 \
        p7zip-full=16.02+dfsg-8 \
        make=4.3-4ubuntu1 \
        autotools-dev=20180224.1+nmu1 \
        autoconf=2.69-14 \
        libtool=2.4.6-15 \
        gettext=0.21-4ubuntu3 \
        autopoint=0.21-4ubuntu3 \
        mingw-w64=8.0.0-1 \
        mingw-w64-tools=8.0.0-1 \
        win-iconv-mingw-w64-dev=0.0.8-4

# Official WineHQ signing key
# See https://wiki.winehq.org/Ubuntu
# From https://dl.winehq.org/wine-builds/winehq.key
COPY winehq.key /tmp

ENV WINEDIST=devel
ENV WINEVERSION=7.2~${UBUNTUDIST}-1

RUN echo "78b185fabdb323971d13bd329fefc8038e08559aa51c4996de18db0639a51df6 /tmp/winehq.key" | sha256sum -c - && \
    apt-key add /tmp/winehq.key && \
    echo deb https://dl.winehq.org/wine-builds/ubuntu/ ${UBUNTUDIST} main >> /etc/apt/sources.list && \
    apt-get update -q && \
    apt-get install -qy \
        wine-${WINEDIST}-amd64:amd64=${WINEVERSION} \
        wine-${WINEDIST}-i386:i386=${WINEVERSION} \
        wine-${WINEDIST}:amd64=${WINEVERSION} \
        winehq-${WINEDIST}:amd64=${WINEVERSION} \
        cabextract=1.9-3 \
        xvfb=2:1.20.13-1ubuntu1.1 \
        winbind=2:4.13.17~dfsg-0ubuntu0.21.10.1

RUN rm -rf /var/lib/apt/lists/* && \
    apt-get autoremove -y && \
    apt-get clean

RUN wget -q -O /tmp/winetricks https://raw.githubusercontent.com/Winetricks/winetricks/094a4d8552587fca12a101b6a1104833dad4043f/src/winetricks && \
    chmod +x /tmp/winetricks && \
    echo "662738eb3863282b7438bedba0c17c5e1b47f1d4b0f8a929ff7ac48a3ace13b2 /tmp/winetricks" | sha256sum -c -

ARG USER_ID
ARG GROUP_ID

ENV HOME=/homedir
ENV WINEARCH=win32
ENV WINEPREFIX=${HOME}/wine
ENV WINEDEBUG=-all

RUN mkdir -p ${WINEPREFIX}/drive_c/electroncash ; chown -R ${USER_ID}:${GROUP_ID} ${HOME}

# Make our UID/GID have an actual user name
RUN echo "user:x:${USER_ID}:${GROUP_ID}::${HOME}:/bin/sh" >> /etc/passwd
USER ${USER_ID}:${GROUP_ID}

WORKDIR ${HOME}

RUN wineboot && \
    /tmp/winetricks --optout -q dotnet472 && \
    wineserver -w

RUN /tmp/winetricks --optout -q corefonts && \
    wineserver -w

# This is needed because we don't have dbus in the container and the dbus client libraries are set to abort by default
ENV DBUS_FATAL_WARNINGS=0

RUN wget https://aka.ms/vs/16/release/installer -O vs_installer.opc && \
    7z x -oopc vs_installer.opc && \
    xvfb-run wine opc/Contents/vs_installer.exe install \
        --channelId VisualStudio.16.Release \
        --channelUri "https://aka.ms/vs/16/release/channel" \
        --productId Microsoft.VisualStudio.Product.BuildTools \
        --add "Microsoft.VisualStudio.Workload.VCTools" \
        --add "Microsoft.VisualStudio.Component.Windows10SDK.17763" \
        --includeRecommended \
        --quiet && \
    mkdir "${WINEPREFIX}"/"drive_c/Program Files/Microsoft Visual Studio/Installer" && \
    mv opc/Contents/vswhere.exe "${WINEPREFIX}"/"drive_c/Program Files/Microsoft Visual Studio/Installer" && \
    rm -rf opc && \
    wineserver -w
