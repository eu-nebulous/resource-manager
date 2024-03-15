#!/usr/bin/env bash

# Change directory to RD home
PREVWORKDIR=`pwd`
BASEDIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
cd ${BASEDIR}

# Read JASYPT password (decrypts encrypted configuration settings)
#if [[ -z "$JASYPT_PASSWORD" ]]; then
#    printf "Configuration Password: "
#    read -s JASYPT_PASSWORD
#    export JASYPT_PASSWORD
#fi
# Use this online service to encrypt/decrypt passwords:
# https://www.devglan.com/online-tools/jasypt-online-encryption-decryption


# Setup TERM & INT signal handler
trap 'echo "Signaling server to exit"; kill -TERM "${pid}"; wait "${pid}"; ' SIGTERM SIGINT

# Set JRE command and options
JRE=/opt/java/openjdk/bin/java
#JAVA_ADD_OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util.regex=ALL-UNNAMED --add-opens java.base/sun.nio.cs=ALL-UNNAMED --add-opens java.base/java.nio.charset=ALL-UNNAMED"

# Set shell encoding to UTF-8 (in order to display banner correctly)
export LANG=C.UTF-8

# Print basic env. info
echo "--------------------------------------------------------------------------------"
echo "Env. info:"
echo "LANG: ${LANG}"
echo "USER: $( whoami )"
echo "IP address: `hostname -I`"
echo "--------------------------------------------------------------------------------"
echo "JRE:"
${JRE} -version
echo "--------------------------------------------------------------------------------"
echo "Starting Resource Discovery server..."

# Run RD server
${JRE} \
    $JAVA_OPTS \
    $JAVA_ADD_OPENS \
    -Djasypt.encryptor.password=$JASYPT_PASSWORD \
    -Djava.security.egd=file:/dev/urandom \
    org.springframework.boot.loader.launch.JarLauncher \
    $* &

# Get PID and wait it to exit
pid=$!
echo "Pid: $pid"
wait $pid
echo "Server exited"

cd $PREVWORKDIR
