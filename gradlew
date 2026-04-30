#!/bin/sh
APP_HOME="${APP_HOME:-$(cd "$(dirname "$0")" && pwd -P)}"
APP_BASE_NAME="$(basename "$0")"
DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'

warn () { echo "$*"; }
die ()  { echo; echo "$*"; echo; exit 1; }

cygwin=false; darwin=false; nonstop=false
case "$(uname)" in
  CYGWIN* ) cygwin=true  ;;
  Darwin*  ) darwin=true  ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
    [ ! -x "$JAVACMD" ] && die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
else
    JAVACMD="java"
    command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and 'java' was not found."
fi

if [ "$cygwin" = "false" ] && [ "$darwin" = "false" ] && [ "$nonstop" = "false" ]; then
    MAX_FD_LIMIT="$(ulimit -H -n)"
    case $MAX_FD_LIMIT in
        '' | soft) ;;
        *) ulimit -n "$MAX_FD_LIMIT" 2>/dev/null ;;
    esac
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
