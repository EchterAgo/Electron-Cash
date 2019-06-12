#!/bin/bash

set -e

PROJECT_ROOT="$(dirname "$(readlink -e "$0")")/../../.."
CONTRIB="$PROJECT_ROOT/contrib"
DISTDIR="$PROJECT_ROOT/dist"
BUILDDIR="$CONTRIB/build-linux/appimage/build/appimage"
APPDIR="$BUILDDIR/Electron-Cash.AppDir"
CACHEDIR="$CONTRIB/build-linux/appimage/.cache/appimage"

# pinned versions
SQUASHFSKIT_COMMIT="ae0d656efa2d0df2fcac795b6823b44462f19386"
PKG2APPIMAGE_COMMIT="eb8f3acdd9f11ab19b78f5cb15daa772367daf15"


VERSION=`git describe --tags --dirty --always`
APPIMAGE="$DISTDIR/Electron-Cash-$VERSION-x86_64.AppImage"

rm -rf "$BUILDDIR"
mkdir -p "$APPDIR" "$CACHEDIR" "$DISTDIR"


. "$CONTRIB"/base.sh

info "Refreshing submodules ..."
git submodule update --init

info "downloading some dependencies."
download_if_not_exist "$CACHEDIR/functions.sh" "https://raw.githubusercontent.com/AppImage/pkg2appimage/$PKG2APPIMAGE_COMMIT/functions.sh"
verify_hash "$CACHEDIR/functions.sh" "78b7ee5a04ffb84ee1c93f0cb2900123773bc6709e5d1e43c37519f590f86918"

download_if_not_exist "$CACHEDIR/appimagetool" "https://github.com/AppImage/AppImageKit/releases/download/12/appimagetool-x86_64.AppImage"
verify_hash "$CACHEDIR/appimagetool" "d918b4df547b388ef253f3c9e7f6529ca81a885395c31f619d9aaf7030499a13"

download_if_not_exist "$CACHEDIR/Python-$PYTHON_VERSION.tar.xz" "https://www.python.org/ftp/python/$PYTHON_VERSION/Python-$PYTHON_VERSION.tar.xz"
verify_hash "$CACHEDIR/Python-$PYTHON_VERSION.tar.xz" $PYTHON_SRC_TARBALL_HASH

download_if_not_exist "$CACHEDIR/libQt5MultimediaGstTools.so.5.11.3.xz" "https://github.com/cculianu/Electron-Cash-Build-Tools/releases/download/v1.0/libQt5MultimediaGstTools.so.5.11.3.xz"
verify_hash "$CACHEDIR/libQt5MultimediaGstTools.so.5.11.3.xz" "12fbf50f7f5f3fd6b49a8e757846253ae658e96f132956fdcd7107c81b55d819"



info "Building Python"
tar xf "$CACHEDIR/Python-$PYTHON_VERSION.tar.xz" -C "$BUILDDIR"
(
    cd "$BUILDDIR/Python-$PYTHON_VERSION"
    export SOURCE_DATE_EPOCH=1530212462
    LC_ALL=C export BUILD_DATE=$(date -u -d "@$SOURCE_DATE_EPOCH" "+%b %d %Y")
    LC_ALL=C export BUILD_TIME=$(date -u -d "@$SOURCE_DATE_EPOCH" "+%H:%M:%S")
    # Patch taken from Ubuntu python3.6_3.6.8-1~18.04.1.debian.tar.xz
    patch -p1 < "$CONTRIB/build-linux/appimage/patches/python-3.6.8-reproducible-buildinfo.diff"
    ./configure \
      --cache-file="$CACHEDIR/python.config.cache" \
      --prefix="$APPDIR/usr" \
      --enable-ipv6 \
      --enable-shared \
      --with-threads \
      -q
    make -j 4 -s || fail "Could not build Python"
    make -s install > /dev/null || fail "Failed to install Python"
    # When building in docker on macOS, python builds with .exe extension because the
    # case insensitive file system of macOS leaks into docker. This causes the build
    # to result in a different output on macOS compared to Linux. We simply patch
    # sysconfigdata to remove the extension.
    # Some more info: https://bugs.python.org/issue27631
    sed -i -e 's/\.exe//g' "$APPDIR"/usr/lib/python3.6/_sysconfigdata*
)

info "Building squashfskit"
git clone "https://github.com/squashfskit/squashfskit.git" "$BUILDDIR/squashfskit"
(
    cd "$BUILDDIR/squashfskit"
    git checkout -b pinned "$SQUASHFSKIT_COMMIT"
    make -C squashfs-tools XZ_SUPPORT=1 mksquashfs
)
MKSQUASHFS="$BUILDDIR/squashfskit/squashfs-tools/mksquashfs"

#info "Building libsecp256k1"  # make_secp below already prints this
(
    pushd "$PROJECT_ROOT"

    "$CONTRIB"/make_secp || fail "Could not build libsecp"

    find lib -type f -name libsecp\* -exec touch -d '2000-11-11T11:11:11+00:00' {} +

    popd
)

#info "Building libzbar"  # make_zbar below already prints this
(
    pushd "$PROJECT_ROOT"

    "$CONTRIB"/make_zbar || fail "Could not build libzbar"

    find lib -type f -name libzbar\* -exec touch -d '2000-11-11T11:11:11+00:00' {} +

    popd
)


appdir_python() {
  env \
    PYTHONNOUSERSITE=1 \
    LD_LIBRARY_PATH="$APPDIR/usr/lib:$APPDIR/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH+:$LD_LIBRARY_PATH}" \
    "$APPDIR/usr/bin/python3.6" "$@"
}

python='appdir_python'


info "Installing pip"
"$python" -m ensurepip


info "Preparing electrum-locale"
(
    cd "$PROJECT_ROOT"

    pushd "$CONTRIB"/electrum-locale
    if ! which msgfmt > /dev/null 2>&1; then
        fail "Please install gettext"
    fi
    for i in ./locale/*; do
        dir="$PROJECT_ROOT/lib/$i/LC_MESSAGES"
        mkdir -p $dir
        msgfmt --output-file="$dir/electron-cash.mo" "$i/electron-cash.po" || true
    done
    popd
)


info "Installing Electron Cash and its dependencies"
mkdir -p "$CACHEDIR/pip_cache"
"$python" -m pip install --cache-dir "$CACHEDIR/pip_cache" -r "$CONTRIB/deterministic-build/requirements.txt"
"$python" -m pip install --cache-dir "$CACHEDIR/pip_cache" -r "$CONTRIB/deterministic-build/requirements-binaries.txt"
"$python" -m pip install --cache-dir "$CACHEDIR/pip_cache" -r "$CONTRIB/deterministic-build/requirements-hw.txt"
"$python" -m pip install --cache-dir "$CACHEDIR/pip_cache" "$PROJECT_ROOT"


info "Installing missing libQt5MultimediaGstTools for PyQt5 5.11.3"
# Packaging bug in PyQt5 5.11.3, fixed in 5.12.2, see:
# https://www.riverbankcomputing.com/pipermail/pyqt/2019-April/041670.html
xz -k -d "$CACHEDIR/libQt5MultimediaGstTools.so.5.11.3.xz"
mv "$CACHEDIR/libQt5MultimediaGstTools.so.5.11.3" \
  "$APPDIR/usr/lib/python3.6/site-packages/PyQt5/Qt/lib/libQt5MultimediaGstTools.so.5"


info "Copying desktop integration"
cp "$PROJECT_ROOT/electron-cash.desktop" "$APPDIR/electron-cash.desktop"
cp "$PROJECT_ROOT/icons/electron-cash.png" "$APPDIR/electron-cash.png"


# add launcher
info "Adding launcher"
cp "$CONTRIB/build-linux/appimage/apprun.sh" "$APPDIR/AppRun"

info "Finalizing AppDir"
(
    export PKG2AICOMMIT="$PKG2APPIMAGE_COMMIT"
    . "$CACHEDIR/functions.sh"

    cd "$APPDIR"
    # copy system dependencies
    # note: temporarily move PyQt5 out of the way so
    # we don't try to bundle its system dependencies.
    mv "$APPDIR/usr/lib/python3.6/site-packages/PyQt5" "$BUILDDIR"
    copy_deps; copy_deps; copy_deps
    move_lib
    mv "$BUILDDIR/PyQt5" "$APPDIR/usr/lib/python3.6/site-packages"

    # apply global appimage blacklist to exclude stuff
    # move usr/include out of the way to preserve usr/include/python3.6m.
    mv usr/include usr/include.tmp
    delete_blacklisted
    mv usr/include.tmp usr/include
) || fail "Could not finalize AppDir"

# We copy libusb here because it is on the AppImage excludelist and it can cause problems if we use system libusb
info "Copying libusb"
cp -f /usr/lib/x86_64-linux-gnu/libusb-1.0.so "$APPDIR/usr/lib/libusb-1.0.so" || fail "Could not copy libusb"

info "Stripping binaries of debug symbols"
# "-R .note.gnu.build-id" also strips the build id
strip_binaries()
{
  chmod u+w -R "$APPDIR"
  {
    printf '%s\0' "$APPDIR/usr/bin/python3.6"
    find "$APPDIR" -type f -regex '.*\.so\(\.[0-9.]+\)?$' -print0
  } | xargs -0 --no-run-if-empty --verbose -n1 strip -R .note.gnu.build-id
}
strip_binaries

remove_emptydirs()
{
  find "$APPDIR" -type d -empty -print0 | xargs -0 --no-run-if-empty rmdir -vp --ignore-fail-on-non-empty
}
remove_emptydirs


info "Removing some unneeded files to decrease binary size"
rm -rf "$APPDIR"/usr/lib/python3.6/test
rm -rf "$APPDIR"/usr/lib/python3.6/config-3.6m-x86_64-linux-gnu
rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt/translations/qtwebengine_locales
rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt/resources/qtwebengine_*
rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt/qml
for component in Web Designer Qml Quick Location Test Xml ; do
    rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt/lib/libQt5${component}*
    rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt${component}*
done
rm -rf "$APPDIR"/usr/lib/python3.6/site-packages/PyQt5/Qt.so

# these are deleted as they were not deterministic; and are not needed anyway
find "$APPDIR" -path '*/__pycache__*' -delete
rm "$APPDIR"/usr/lib/python3.6/site-packages/pyblake2-*.dist-info/RECORD
rm "$APPDIR"/usr/lib/python3.6/site-packages/hidapi-*.dist-info/RECORD
rm "$APPDIR"/usr/lib/python3.6/site-packages/psutil-*.dist-info/RECORD


find -exec touch -h -d '2000-11-11T11:11:11+00:00' {} +


info "Creating the AppImage"
(
    cd "$BUILDDIR"
    chmod +x "$CACHEDIR/appimagetool"
    "$CACHEDIR/appimagetool" --appimage-extract
    # We build a small wrapper for mksquashfs that removes the -mkfs-fixed-time option
    # that mksquashfs from squashfskit does not support. It is not needed for squashfskit.
    cat > ./squashfs-root/usr/lib/appimagekit/mksquashfs << EOF
#!/bin/sh
args=\$(echo "\$@" | sed -e 's/-mkfs-fixed-time 0//')
"$MKSQUASHFS" \$args
EOF
    env VERSION="$VERSION" ARCH=x86_64 SOURCE_DATE_EPOCH=1530212462 ./squashfs-root/AppRun --no-appstream --verbose "$APPDIR" "$APPIMAGE"
)


info "Done"
ls -la "$DISTDIR"
sha256sum "$DISTDIR"/*
