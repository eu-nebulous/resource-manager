
ARG BUILDER_IMAGE=docker.io/library/maven:3.9.5-eclipse-temurin-17
ARG RUN_IMAGE=docker.io/library/eclipse-temurin:17.0.8.1_1-jre

# ----------------- Builder image -----------------
FROM $BUILDER_IMAGE as rd-builder
ENV BASEDIR /app
WORKDIR ${BASEDIR}
COPY src        ${BASEDIR}/src
COPY pom.xml    ${BASEDIR}/
COPY run.sh     ${BASEDIR}/
RUN mvn -f ${BASEDIR}/pom.xml -DskipTests clean install && \
    java -Djarmode=layertools -jar ${BASEDIR}/target/resource-discovery-*.jar extract

# -----------------   Runtime image   -----------------
FROM $RUN_IMAGE

# Setup environment
ENV BASEDIR /opt/resource-discovery
ENV RD_HOME ${BASEDIR}

# Install required and optional packages
RUN wget  --progress=dot:giga -O /usr/local/bin/dumb-init \
          https://github.com/Yelp/dumb-init/releases/download/v1.2.5/dumb-init_1.2.5_x86_64 && \
    chmod +x /usr/local/bin/dumb-init

#RUN apt-get update && \
#    apt-get install -y netcat vim iputils-ping && \
#    rm -rf /var/lib/apt/lists/*

# Add RD user
ARG RD_USER=rd
RUN mkdir ${RD_HOME} && \
    addgroup ${RD_USER} && \
    adduser --home ${RD_HOME} --no-create-home --ingroup ${RD_USER} --disabled-password ${RD_USER} && \
    chown ${RD_USER}:${RD_USER} ${RD_HOME}

# Set User and Workdir
USER ${RD_USER}
WORKDIR ${BASEDIR}

# Copy files from builder container
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/dependencies          ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/spring-boot-loader    ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/snapshot-dependencies ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/application           ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/run.sh                ${BASEDIR}

EXPOSE 8080

ENTRYPOINT ["dumb-init", "./run.sh"]