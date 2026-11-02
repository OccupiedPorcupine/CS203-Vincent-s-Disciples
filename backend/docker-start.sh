#!/bin/sh
# Starts the backend inside its Docker image (see Dockerfile).
#
# On Vercel, calls to other services over a binding (the ML service) use certificates signed by
# Vercel's own CA. Vercel adds that CA to the system bundle, but Java only trusts its own cacerts
# file, so copy cacerts and add the CA to the copy. Elsewhere the file is absent and this is skipped.
set -e

VERCEL_CA=/usr/local/share/ca-certificates/vercel-proxy-ca.crt

if [ -f "$VERCEL_CA" ]; then
  TRUSTSTORE=/tmp/cacerts
  cp "$JAVA_HOME/lib/security/cacerts" "$TRUSTSTORE"
  keytool -importcert -noprompt -alias vercel-proxy-ca -file "$VERCEL_CA" \
    -keystore "$TRUSTSTORE" -storepass changeit > /dev/null
  exec java -Djavax.net.ssl.trustStore="$TRUSTSTORE" -Djavax.net.ssl.trustStorePassword=changeit -jar app.jar
fi

exec java -jar app.jar
