FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/otzivi /app/data

# MAX platform-api2 can use Russian Trusted CA certificates. Import both
# certificates from the official Gosuslugi CDN into the JVM truststore.
ADD https://gu-st.ru/content/Other/doc/russian_trusted_root_ca.cer /tmp/russian-ca/russian_trusted_root_ca.cer
ADD https://gu-st.ru/content/Other/doc/russian_trusted_sub_ca.cer /tmp/russian-ca/russian_trusted_sub_ca.cer
RUN set -eux; \
    keytool -importcert -noprompt -storepass changeit \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -alias russian-trusted-root-ca \
      -file /tmp/russian-ca/russian_trusted_root_ca.cer; \
    keytool -importcert -noprompt -storepass changeit \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -alias russian-trusted-sub-ca \
      -file /tmp/russian-ca/russian_trusted_sub_ca.cer; \
    rm -rf /tmp/russian-ca

COPY --from=build /workspace/target/psihmax-bot-*.jar /app/app.jar
COPY otzivi /app/otzivi

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
