#!/bin/zsh
# Build, check and (only with --publish) release a new version of Holtzman.
#
#   tools/release.sh 0.5.1                       # bump the version, run the tests, build the signed APK, verify it; publishes NOTHING
#   tools/release.sh 0.5.1 --publish --notes notes.md   # the same, then commit the version, push, tag, and create the GitHub pre-release with the APK
#
# Before it starts it checks: you are on main with everything committed (so the release is exactly what is in git), the signing key and the GitHub login work, the
# version is newer than the last tag and the tag does not exist. Without --publish it leaves only the version bump in app/build.gradle.kts, uncommitted: undo it with
#   git checkout app/build.gradle.kts
# The signing key is read from ~/Code/holtzman-release/ (holtzman-release.jks and .storepass, alias holtzman); it is never written anywhere.
set -eu
REPO=${0:A:h:h}; cd $REPO
VERSION=${1:-}; shift || true
PUBLISH=0; NOTES=""
while [ $# -gt 0 ]; do case $1 in --publish) PUBLISH=1;; --notes) NOTES=$2; shift;; *) echo "unknown option $1"; exit 2;; esac; shift; done
[[ $VERSION =~ '^[0-9]+\.[0-9]+\.[0-9]+$' ]] || { echo "usage: tools/release.sh X.Y.Z [--publish --notes FILE]"; exit 2; }
KEYDIR=$HOME/Code/holtzman-release
fail() { echo "STOP: $1"; exit 1 }

echo "== checks"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || fail "not on main (git checkout main)"
[ -z "$(git status --porcelain)" ] || fail "uncommitted changes: commit or stash them first, so the release is what is in git
$(git status --short | head -8)"
git rev-parse "v$VERSION" >/dev/null 2>&1 && fail "tag v$VERSION already exists"
LAST=$(git tag --list 'v*' | sort -V | tail -1); LAST=${LAST#v}
[ "$(printf '%s\n%s\n' "$LAST" "$VERSION" | sort -V | tail -1)" = "$VERSION" ] && [ "$LAST" != "$VERSION" ] || fail "$VERSION is not newer than the last release $LAST"
[ -f $KEYDIR/holtzman-release.jks ] && [ -f $KEYDIR/.storepass ] || fail "signing key not found in $KEYDIR"
if [ $PUBLISH = 1 ]; then
  [ -n "$NOTES" ] && [ -f "$NOTES" ] || fail "--publish needs --notes FILE (what is new, and what was and was not tested)"
  gh auth status >/dev/null 2>&1 || fail "gh is not logged in (gh auth login)"
fi
echo "ok: last release v$LAST, building $VERSION"

echo "== version"
CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+'); NEWCODE=$((CODE + 1))
sed -i '' "s/versionCode = $CODE/versionCode = $NEWCODE/; s/versionName = \"[0-9.]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts
grep -E 'versionCode|versionName' app/build.gradle.kts

echo "== tests"
./gradlew test -q 2>&1 | grep -v '^$' | tail -15 && true
./gradlew test -q >/dev/null 2>&1 || fail "tests failed (see ./gradlew test); the version bump is still in app/build.gradle.kts: git checkout app/build.gradle.kts"

echo "== build"
export HOLTZMAN_RELEASE_STORE_FILE=$KEYDIR/holtzman-release.jks HOLTZMAN_RELEASE_KEY_ALIAS=holtzman
export HOLTZMAN_RELEASE_STORE_PASSWORD=$(cat $KEYDIR/.storepass) HOLTZMAN_RELEASE_KEY_PASSWORD=$(cat $KEYDIR/.storepass)
./gradlew :app:assembleRelease -q 2>&1 | grep -E '^e:|FAIL|error' && fail "the build failed"
APK=app/build/outputs/apk/release/app-release.apk; [ -f $APK ] || fail "no APK was produced"

echo "== verify"
SDK=${ANDROID_HOME:-$HOME/Code/android-sdk}; BT=$(ls -d $SDK/build-tools/* | sort -V | tail -1)
INFO=$($BT/aapt2 dump badging $APK 2>/dev/null | grep -E "^package:")
echo "$INFO" | cut -c1-110
echo "$INFO" | grep -q "versionName='$VERSION'" || fail "the APK does not say version $VERSION"
echo "$INFO" | grep -q "name='com.everettpenne.holtzman'" || fail "wrong package name"
java -jar $BT/lib/apksigner.jar verify --print-certs $APK 2>&1 | grep -E "Signer.*(DN|digest)" | head -2 | cut -c1-120
java -jar $BT/lib/apksigner.jar verify $APK >/dev/null 2>&1 || fail "the signature does not verify"
unzip -l $APK | grep -q "assets/denoiser_int8.tflite" || fail "the denoiser model is not in the APK"
SHA=$(shasum -a 256 $APK | cut -c1-64); SIZE=$(du -h $APK | cut -f1)
echo "APK $SIZE, sha256 $SHA"

if [ $PUBLISH = 0 ]; then
  echo; echo "Built and verified: $REPO/$APK"
  echo "Install it on the phone to check it:  adb install -r $APK"
  echo "Nothing was published. To release:  tools/release.sh $VERSION --publish --notes FILE   (after: git checkout app/build.gradle.kts)"
  exit 0
fi

echo "== publish"
git add app/build.gradle.kts; git commit -qm "Version $VERSION"
git push origin main
git tag "v$VERSION"; git push origin "v$VERSION"
OUT=/tmp/Holtzman-$VERSION.apk; cp $APK $OUT
gh release create "v$VERSION" $OUT --prerelease --title "Holtzman $VERSION (pre-release)" --notes-file "$NOTES"
echo "Released v$VERSION, sha256 $SHA"
