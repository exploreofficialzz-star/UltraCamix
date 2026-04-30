#!/bin/sh
# Gradle wrapper script for Unix/macOS/Linux
# Generated for CamixUltra

##############################################################################
# Attempt to set APP_HOME
##############################################################################
APP_HOME="${APP_HOME:-$(cd "$(dirname "$0")" && pwd -P)}"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"

# Add default JVM options here
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available memory, or 256m if not specified
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

##############################################################################
# OS specific support (must be 'true' or 'false').
##############################################################################
cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* )  cygwin=true  ;;
  Darwin*  ) darwin=true  ;;
  MSYS*    ) msys=true    ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine the Java command to use
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and 'java' was not found."
fi

# Increase maximum file descriptors
if [ "$cygwin" = "false" ] && [ "$darwin" = "false" ] && [ "$nonstop" = "false" ]; then
    MAX_FD_LIMIT="$(ulimit -H -n)"
    case $MAX_FD_LIMIT in
        '' | soft) ;;
        *)
            MAX_FD="$MAX_FD_LIMIT"
            ulimit -n "$MAX_FD" 2>/dev/null || warn "Could not set max file descriptors to $MAX_FD"
    esac
fi

# Collect all arguments
APP_ARGS=""
for ARG in "$@"; do
    APP_ARGS="$APP_ARGS \"$ARG\""
done

# Build the command
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
